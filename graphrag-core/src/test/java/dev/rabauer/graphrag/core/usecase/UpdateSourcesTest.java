package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.SourceLocator;
import dev.rabauer.graphrag.core.domain.Sources;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CHARGE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CORPUS;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CREATE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.PAYMENT;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.PLACE_ORDER;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateSourcesTest {

    private static final String PAYMENT_FILE = "src/main/java/com/shop/payment/PaymentService.java";

    @Test
    void sourcesComeFromTheAttributeThenTheLocatorThenTheDocumentName() {
        assertEquals("a.java", Sources.of(new Entity("A", "Class", "", List.of(), Map.of("source", "a.java"),
                SourceLocator.of("other.java"))));
        assertEquals("b.java", Sources.of(new Entity("B", "Class").withLocator(SourceLocator.of("b.java", 3))));
        assertEquals("", Sources.of(new Entity("C", "Class")));
        assertEquals("doc.txt", Sources.of(new TextUnit("t", "c", "doc.txt", 0, "text")));
    }

    @Test
    void removesAFilesElementsAndTheRelationshipsTouchingThem() {
        TestGraphStore store = SmallCodeGraph.store();

        SourceRemoval removal = new UpdateSources(store).removeBySource(CORPUS, PAYMENT_FILE);

        assertEquals(2, removal.removedTextUnits());
        assertEquals(List.of(id(PAYMENT, "Class"), id(CHARGE, "Method")), removal.removedEntities());
        assertNull(store.stored(CORPUS, id(PAYMENT, "Class")));
        assertNull(store.stored(CORPUS, id(CHARGE, "Method")));
        assertTrue(store.textUnit(CORPUS, "tu-charge").isEmpty());
        // placeOrder -> charge and PaymentService DECLARES charge are gone; the rest stays.
        assertEquals(2, removal.removedRelationships());
        assertEquals(6, store.relationships(CORPUS).size());
        assertTrue(store.relationships(CORPUS).stream().noneMatch(relationship ->
                relationship.targetIdentity().equals(id(CHARGE, "Method"))));
        assertNotNull(store.stored(CORPUS, id(PLACE_ORDER, "Method")));
        assertFalse(removal.communitiesStale());
    }

    @Test
    void reportsTheCommunitiesThatLostAMember() {
        TestGraphStore store = SmallCodeGraph.store();
        new DetectCommunities(store, null, 1).detect(CORPUS);
        String paymentCommunity = store.communityMemberships(CORPUS).stream()
                .filter(membership -> membership.entityIdentity().equals(id(PAYMENT, "Class")))
                .findFirst().orElseThrow().communityId();

        SourceRemoval removal = new UpdateSources(store).removeBySource(CORPUS, PAYMENT_FILE);

        assertTrue(removal.communitiesStale());
        assertTrue(removal.staleCommunityIds().contains(paymentCommunity));
        assertTrue(store.communityMemberships(CORPUS).stream()
                .noneMatch(membership -> membership.entityIdentity().equals(id(PAYMENT, "Class"))));
    }

    @Test
    void removesOrphanedPlaceholdersButKeepsUnrelatedSourcelessEntities() {
        TestGraphStore store = SmallCodeGraph.store();
        store.persistEntities(CORPUS, List.of(new Entity("java.util.Currency", "Class"),
                new Entity("Standalone", "Concept")));
        store.persistRelationships(CORPUS, List.of(new Relationship(CHARGE, "Method", "CALLS", "java.util.Currency",
                "Class", "", List.of(), 1, Map.of(), SourceLocator.of(PAYMENT_FILE, 20))));

        SourceRemoval removal = new UpdateSources(store).removeBySource(CORPUS, PAYMENT_FILE);

        assertEquals(List.of(id("java.util.Currency", "Class")), removal.orphanedEntities());
        assertNull(store.stored(CORPUS, id("java.util.Currency", "Class")));
        assertNotNull(store.stored(CORPUS, id("Standalone", "Concept")));
    }

    @Test
    void textCorporaRemoveEntitiesOnlyTheRemovedDocumentMentionedAndTrimTheOthers() {
        TestGraphStore store = new TestGraphStore();
        store.persistTextUnits("c", List.of(new TextUnit("t1", "c", "a.txt", 0, "Ada met Bob."),
                new TextUnit("t2", "c", "b.txt", 0, "Bob met Cy.")));
        store.persistEntities("c", List.of(new Entity("Ada", "Person", "", List.of("t1")),
                new Entity("Bob", "Person", "", List.of("t1", "t2")), new Entity("Cy", "Person", "", List.of("t2"))));
        store.persistRelationships("c", List.of(
                new Relationship("Ada", "Person", "met", "Bob", "Person", "", List.of("t1"), 1),
                new Relationship("Bob", "Person", "met", "Cy", "Person", "", List.of("t1", "t2"), 2)));

        SourceRemoval removal = new UpdateSources(store).removeBySource("c", "a.txt");

        assertEquals(List.of("ada::person"), removal.removedEntities());
        assertEquals(List.of("t2"), store.stored("c", "bob::person").sourceTextUnitIds());
        assertEquals(1, removal.updatedEntities());
        assertEquals(1, removal.updatedRelationships());
        Relationship kept = store.relationships("c").iterator().next();
        assertEquals(List.of("t2"), kept.sourceTextUnitIds());
    }

    @Test
    void anUnknownSourceRemovesNothing() {
        TestGraphStore store = SmallCodeGraph.store();

        SourceRemoval removal = new UpdateSources(store).removeBySource(CORPUS, "src/Nope.java");

        assertEquals(0, removal.removedTextUnits());
        assertTrue(removal.removedEntities().isEmpty());
        assertEquals(8, store.relationships(CORPUS).size());
        assertThrows(IllegalArgumentException.class, () -> new UpdateSources(store).removeBySource(CORPUS, " "));
    }

    @Test
    void aStoreWithoutDeletesFailsLoudly() {
        GraphStorePort readOnly = new GraphStorePort() {
            final TestGraphStore delegate = SmallCodeGraph.store();

            @Override
            public void persistEntities(Collection<Entity> entities) {
            }

            @Override
            public void persistRelationships(Collection<Relationship> relationships) {
            }

            @Override
            public Collection<Entity> entities(String corpusId) {
                return delegate.entities(corpusId);
            }

            @Override
            public Collection<TextUnit> textUnits(String corpusId) {
                return delegate.textUnits(corpusId);
            }
        };

        assertThrows(UnsupportedOperationException.class,
                () -> new UpdateSources(readOnly).removeBySource(CORPUS, PAYMENT_FILE));
    }

    @Test
    void alsoDeletesTheSourcesChunksFromTheVectorIndex() {
        List<String> deleted = new ArrayList<>();
        VectorStorePort vectors = new VectorStorePort() {
            @Override
            public void persistChunks(String corpusId, Collection<EmbeddedChunk> chunks) {
            }

            @Override
            public void deleteChunksOf(String corpusId, String documentName) {
                deleted.add(corpusId + "/" + documentName);
            }
        };

        SourceRemoval removal = new UpdateSources(SmallCodeGraph.store(), vectors, null, null)
                .removeBySource(CORPUS, PAYMENT_FILE);

        assertTrue(removal.removedChunks());
        assertEquals(List.of(CORPUS + "/" + PAYMENT_FILE), deleted);
    }

    @Test
    void replaceSourceUpsertsAFilesNewGraphWithoutDetecting() {
        TestGraphStore store = SmallCodeGraph.store();
        SourceLocator newChargeAt = SourceLocator.of(PAYMENT_FILE, 15, 34);
        KnowledgeGraphImport newVersion = KnowledgeGraphImport.of(
                List.of(new TextUnit("tu-charge-v2", CORPUS, PAYMENT_FILE, 0, "void charge(Order o) { audit(o); }",
                        Map.of(), newChargeAt)),
                List.of(SmallCodeGraph.method(CHARGE, newChargeAt, "tu-charge-v2"),
                        SmallCodeGraph.type(PAYMENT, "class", SourceLocator.of(PAYMENT_FILE, 1, 50), "tu-charge-v2")),
                List.of(SmallCodeGraph.edge(PAYMENT, "Class", "DECLARES", CHARGE, "Method", 1, newChargeAt)));

        UpdateSources.Replacement replacement = new UpdateSources(store).replaceSource(CORPUS, PAYMENT_FILE,
                newVersion, null);

        assertEquals(2, replacement.removal().removedEntities().size());
        assertEquals(ImportResult.CommunitySource.NONE, replacement.imported().communitySource());
        assertEquals(newChargeAt, store.stored(CORPUS, id(CHARGE, "Method")).locator());
        assertTrue(store.textUnit(CORPUS, "tu-charge").isEmpty());
        assertTrue(store.textUnit(CORPUS, "tu-charge-v2").isPresent());
        // The caller's own edge into the file (placeOrder -> charge) belongs to OrderService.java and was
        // removed with charge; re-importing OrderService.java restores it.
        assertTrue(store.relationships(CORPUS).stream().noneMatch(relationship ->
                relationship.sourceIdentity().equals(id(PLACE_ORDER, "Method"))
                        && relationship.targetIdentity().equals(id(CHARGE, "Method"))));
        assertNotNull(store.stored(CORPUS, id(CREATE, "Method")));
    }

    @Test
    void recomputeDeletesStaleCommunitiesAndReusesUnchangedSummaries() {
        TestGraphStore store = SmallCodeGraph.store();
        Map<String, Integer> calls = new LinkedHashMap<>();
        LlmPort llm = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus corpus) {
                return GraphExtraction.empty();
            }

            @Override
            public boolean summarizesCommunities() {
                return true;
            }

            @Override
            public CommunitySummary summarizeCommunity(Collection<Entity> members, Collection<Relationship> relationships) {
                String first = members.iterator().next().name();
                calls.merge(first, 1, Integer::sum);
                return new CommunitySummary("About " + first, "Summary of " + first + ".");
            }
        };
        DetectCommunities.Options reuse = DetectCommunities.Options.defaults().withMinCommunitySize(2)
                .withReuseSummaries(true);
        new DetectCommunities(store, llm, reuse).run(CORPUS);
        int firstRunCalls = calls.values().stream().mapToInt(Integer::intValue).sum();
        store.persistCommunities(CORPUS, List.of(new Community("community-99", "Stale", "From an older run.")));

        CommunityDetectionResult recomputed = new UpdateSources(store, null, llm, null)
                .recomputeCommunities(CORPUS, reuse);

        assertTrue(store.communities(CORPUS).stream().noneMatch(community -> community.id().equals("community-99")));
        assertEquals(firstRunCalls, calls.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(recomputed.communities().size(), recomputed.count(CommunityDetectionResult.SummaryStatus.REUSED));
    }
}
