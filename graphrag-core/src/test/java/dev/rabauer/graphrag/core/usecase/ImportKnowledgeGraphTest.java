package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportKnowledgeGraphTest {

    private static final String CORPUS = "atlas";

    private static Entity type(String name) {
        return new Entity(name, "Class", name + " class.", List.of("tu-" + name));
    }

    private static Relationship calls(String source, String target) {
        return new Relationship(source, "Class", "CALLS", target, "Class", "", List.of(), 1);
    }

    /** An LlmPort that fails the test on any extraction call. */
    private static final class NoExtractionAllowed implements LlmPort {
        final List<Collection<Entity>> summarized = new ArrayList<>();

        @Override
        public GraphExtraction extract(Corpus corpus) {
            throw new AssertionError("extract(Corpus) must not be called on import");
        }

        @Override
        public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
            throw new AssertionError("extract(TextUnit) must not be called on import");
        }

        @Override
        public CommunitySummary summarizeCommunity(Collection<Entity> members, Collection<Relationship> relationships) {
            summarized.add(members);
            return new CommunitySummary("Imported group", "Summary of " + members.size() + " members.");
        }
    }

    @Test
    void persistsTextUnitsEntitiesAndRelationshipsWithoutAnyExtractionCall() {
        TestGraphStore store = new TestGraphStore();
        NoExtractionAllowed llm = new NoExtractionAllowed();

        ImportResult result = new ImportKnowledgeGraph(store, llm, null).run(CORPUS, KnowledgeGraphImport.of(
                List.of(new TextUnit("tu-A", CORPUS, "A.java", 0, "class A {}")),
                List.of(type("A"), type("B")),
                List.of(calls("A", "B"))), ImportKnowledgeGraph.Options.defaults().withDetectCommunities(false));

        assertEquals(1, result.textUnits());
        assertEquals(2, result.entities());
        assertEquals(1, result.relationships());
        assertEquals(1, store.textUnits(CORPUS).size());
        assertEquals(2, store.entities(CORPUS).size());
        assertEquals(1, store.relationships(CORPUS).size());
        assertTrue(llm.summarized.isEmpty());
        assertEquals(ImportResult.CommunitySource.NONE, result.communitySource());
    }

    @Test
    void mergesRepeatedEntitiesAndSumsTheWeightsOfRepeatedRelationships() {
        TestGraphStore store = new TestGraphStore();

        ImportResult result = new ImportKnowledgeGraph(store).run(CORPUS, KnowledgeGraphImport.of(List.of(),
                List.of(new Entity("A", "Class", "First.", List.of("tu-1")),
                        new Entity("a", "class", "Second.", List.of("tu-2")), type("B")),
                List.of(new Relationship("A", "Class", "CALLS", "B", "Class", "", List.of("tu-1"), 3),
                        new Relationship("A", "Class", "calls", "B", "Class", "", List.of("tu-2"), 2))),
                ImportKnowledgeGraph.Options.defaults().withDetectCommunities(false));

        assertEquals(2, result.entities());
        Entity merged = store.entity(CORPUS, "a::class");
        assertEquals("First. Second.", merged.description());
        assertEquals(List.of("tu-1", "tu-2"), merged.sourceTextUnitIds());
        Relationship relationship = store.relationships(CORPUS).iterator().next();
        assertEquals(5, relationship.weight());
        assertEquals(List.of("tu-1", "tu-2"), relationship.sourceTextUnitIds());
    }

    @Test
    void createsAPlaceholderForAnEndpointNeitherImportedNorStored() {
        TestGraphStore store = new TestGraphStore();

        ImportResult result = new ImportKnowledgeGraph(store).run(CORPUS,
                KnowledgeGraphImport.of(List.of(), List.of(type("A")), List.of(calls("A", "External"))),
                ImportKnowledgeGraph.Options.defaults().withDetectCommunities(false));

        assertEquals(1, result.placeholderEntities());
        assertEquals(2, result.entities());
        Entity placeholder = store.entity(CORPUS, "external::class");
        assertNotNull(placeholder);
        assertEquals("", placeholder.description());
    }

    @Test
    void neverOverwritesAnAlreadyStoredEndpointWithAPlaceholder() {
        TestGraphStore store = new TestGraphStore();
        store.persistEntities(CORPUS, List.of(new Entity("B", "Class", "Stored earlier.", List.of("tu-B"))));

        ImportResult result = new ImportKnowledgeGraph(store).run(CORPUS,
                KnowledgeGraphImport.of(List.of(), List.of(type("A")), List.of(calls("A", "B"))),
                ImportKnowledgeGraph.Options.defaults().withDetectCommunities(false));

        assertEquals(0, result.placeholderEntities());
        assertEquals("Stored earlier.", store.entity(CORPUS, "b::class").description());
    }

    @Test
    void dropsAndCountsRelationshipsWithAMissingEndpointWhenAskedTo() {
        TestGraphStore store = new TestGraphStore();

        ImportResult result = new ImportKnowledgeGraph(store).run(CORPUS,
                KnowledgeGraphImport.of(List.of(), List.of(type("A"), type("B")),
                        List.of(calls("A", "B"), calls("A", "External"))),
                ImportKnowledgeGraph.Options.defaults().withDetectCommunities(false)
                        .withMissingEndpoints(ImportKnowledgeGraph.MissingEndpoints.DROP));

        assertEquals(1, result.droppedRelationships());
        assertEquals(1, store.relationships(CORPUS).size());
        assertNull(store.entity(CORPUS, "external::class"));
    }

    @Test
    void keepsDanglingRelationshipsAsGivenWhenAskedTo() {
        TestGraphStore store = new TestGraphStore();

        ImportResult result = new ImportKnowledgeGraph(store).run(CORPUS,
                KnowledgeGraphImport.of(List.of(), List.of(type("A")), List.of(calls("A", "External"))),
                ImportKnowledgeGraph.Options.defaults().withDetectCommunities(false)
                        .withMissingEndpoints(ImportKnowledgeGraph.MissingEndpoints.KEEP));

        assertEquals(0, result.placeholderEntities());
        assertEquals(0, result.droppedRelationships());
        assertEquals(1, store.relationships(CORPUS).size());
        assertNull(store.entity(CORPUS, "external::class"));
    }

    @Test
    void persistsSuppliedCommunitiesAsGivenAndSkipsDetectionAndSummaries() {
        TestGraphStore store = new TestGraphStore();
        NoExtractionAllowed llm = new NoExtractionAllowed();
        KnowledgeGraphImport graph = KnowledgeGraphImport.of(List.of(),
                List.of(type("A"), type("B"), type("C")), List.of(calls("A", "B"), calls("B", "C")))
                .withCommunities(
                        List.of(new Community("pkg:com.acme.order", "com.acme.order", "The order package.")),
                        List.of(new CommunityMembership("pkg:com.acme.order", "a::class"),
                                new CommunityMembership("pkg:com.acme.order", "b::class"),
                                new CommunityMembership("pkg:com.acme.billing", "c::class")));

        ImportResult result = new ImportKnowledgeGraph(store, llm, null).run(CORPUS, graph);

        assertEquals(ImportResult.CommunitySource.SUPPLIED, result.communitySource());
        assertTrue(llm.summarized.isEmpty());
        assertEquals(List.of("pkg:com.acme.order", "pkg:com.acme.billing"),
                store.communities(CORPUS).stream().map(Community::id).toList());
        Community supplied = store.communities.get(CORPUS).get("pkg:com.acme.order");
        assertEquals("The order package.", supplied.summary());
        Community derived = store.communities.get(CORPUS).get("pkg:com.acme.billing");
        assertEquals("C", derived.title());
        assertEquals("This community centers on C.", derived.summary());
        assertEquals(List.of("a::class", "b::class"), store.memberIdentities(CORPUS, "pkg:com.acme.order"));
    }

    @Test
    void detectsAndSummarizesCommunitiesWhenNoneAreSupplied() {
        TestGraphStore store = new TestGraphStore();
        NoExtractionAllowed llm = new NoExtractionAllowed();

        ImportResult result = new ImportKnowledgeGraph(store, llm, null).run(CORPUS, KnowledgeGraphImport.of(
                List.of(), List.of(type("A"), type("B"), type("C")),
                List.of(calls("A", "B"), calls("B", "C"), calls("C", "A"))));

        assertEquals(ImportResult.CommunitySource.DETECTED, result.communitySource());
        assertEquals(1, result.communities().size());
        assertEquals("Imported group", result.communities().getFirst().title());
        assertEquals(1, llm.summarized.size());
        assertEquals(3, store.communityMemberships(CORPUS).size());
    }

    @Test
    void honoursTheMinimumCommunitySize() {
        TestGraphStore store = new TestGraphStore();

        ImportResult result = new ImportKnowledgeGraph(store).run(CORPUS,
                KnowledgeGraphImport.of(List.of(), List.of(type("A"), type("B")), List.of(calls("A", "B"))),
                ImportKnowledgeGraph.Options.defaults().withMinCommunitySize(2));

        assertEquals(1, result.communities().size());
        assertThrows(IllegalArgumentException.class,
                () -> ImportKnowledgeGraph.Options.defaults().withMinCommunitySize(0));
    }

    @Test
    void embedsOnlyWithASemanticEmbeddingPortAndWhenAllowed() {
        TestGraphStore store = new TestGraphStore();
        SemanticTestFixtures.FakeEmbeddingPort semantic = new SemanticTestFixtures.FakeEmbeddingPort();
        KnowledgeGraphImport graph = KnowledgeGraphImport.of(List.of(), List.of(type("A")), List.of());

        ImportResult embedded = new ImportKnowledgeGraph(store, null, semantic).run(CORPUS, graph);
        ImportResult offline = new ImportKnowledgeGraph(new TestGraphStore(), null,
                new SemanticTestFixtures.FakeEmbeddingPort(false)).run(CORPUS, graph);
        ImportResult switchedOff = new ImportKnowledgeGraph(new TestGraphStore(), null, semantic).run(CORPUS, graph,
                ImportKnowledgeGraph.Options.defaults().withEmbed(false));

        assertTrue(embedded.embedded());
        assertEquals(1, store.entityEmbeddings.get(CORPUS).size());
        assertFalse(offline.embedded());
        assertFalse(switchedOff.embedded());
    }

    @Test
    void rejectsABlankCorpusIdAndToleratesANullGraph() {
        ImportKnowledgeGraph importer = new ImportKnowledgeGraph(new TestGraphStore());

        assertThrows(IllegalArgumentException.class, () -> importer.run(" ", KnowledgeGraphImport.of(null, null, null)));
        ImportResult empty = importer.run(CORPUS, null);
        assertEquals(0, empty.entities());
        assertTrue(empty.communities().isEmpty());
    }
}
