package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.CachingGraphReadPort;
import dev.rabauer.graphrag.core.port.GraphReadPort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.retrieval.DriftRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.GlobalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;
import dev.rabauer.graphrag.core.retrieval.SeedMatch;
import dev.rabauer.graphrag.core.retrieval.SeedMatcher;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CORPUS;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.JPA_REPOSITORY;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.PAYMENT;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.PLACE_ORDER;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CHARGE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.SAVE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.SERVICE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.REPOSITORY;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retrieval and import read what they need by identity, not the whole corpus on every call. */
class CorpusReadsTest {

    /** Counts the whole-corpus reads that reach it and forwards everything to {@code delegate}. */
    private static final class Counting implements GraphReadPort {
        final GraphReadPort delegate;
        int entityReads;
        int relationshipReads;
        int targetedEntityReads;
        int targetedRelationshipReads;

        Counting(GraphReadPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public Collection<TextUnit> textUnits(String corpusId) {
            return delegate.textUnits(corpusId);
        }

        @Override
        public Optional<TextUnit> textUnit(String corpusId, String textUnitId) {
            return delegate.textUnit(corpusId, textUnitId);
        }

        @Override
        public Collection<Entity> entities(String corpusId) {
            entityReads++;
            return delegate.entities(corpusId);
        }

        @Override
        public Optional<Entity> entity(String corpusId, String identity) {
            targetedEntityReads++;
            return delegate.entity(corpusId, identity);
        }

        @Override
        public List<Entity> entities(String corpusId, Collection<String> identities) {
            targetedEntityReads++;
            return delegate.entities(corpusId, identities);
        }

        @Override
        public Collection<Relationship> relationships(String corpusId) {
            relationshipReads++;
            return delegate.relationships(corpusId);
        }

        @Override
        public List<Relationship> relationshipsTouching(String corpusId, Collection<String> identities) {
            targetedRelationshipReads++;
            return delegate.relationshipsTouching(corpusId, identities);
        }

        @Override
        public Collection<Community> communities(String corpusId) {
            return delegate.communities(corpusId);
        }

        @Override
        public Collection<CommunityMembership> communityMemberships(String corpusId) {
            return delegate.communityMemberships(corpusId);
        }
    }

    private static TestGraphStore storeWithPackages() {
        TestGraphStore store = SmallCodeGraph.store();
        store.persistCommunities(CORPUS, List.of(
                new Community("pkg:order", "com.shop.order", "Order placement and persistence."),
                new Community("pkg:payment", "com.shop.payment", "Charging customers for payments.")));
        store.persistCommunityMemberships(CORPUS, List.of(
                new CommunityMembership("pkg:order", id(SERVICE, "Class")),
                new CommunityMembership("pkg:order", id(PLACE_ORDER, "Method")),
                new CommunityMembership("pkg:order", id(REPOSITORY, "Interface")),
                new CommunityMembership("pkg:order", id(SAVE, "Method")),
                new CommunityMembership("pkg:order", id(JPA_REPOSITORY, "Class")),
                new CommunityMembership("pkg:payment", id(PAYMENT, "Class")),
                new CommunityMembership("pkg:payment", id(CHARGE, "Method"))));
        return store;
    }

    /** Seeds by identity lookups only, the way a semantic matcher asks a vector index. */
    private static SeedMatcher placeOrderSeed() {
        return (question, corpusId, graph, limit) -> graph.entity(corpusId, id(PLACE_ORDER, "Method"))
                .map(entity -> List.of(new SeedMatch(entity, 1, "test"))).orElse(List.of());
    }

    private static List<String> identifiers(RetrievalResult result) {
        return result.items().stream().map(item -> item.kind() + ":" + item.identifier()).toList();
    }

    @Test
    void globalRetrievalReadsTheMembersOfThePickedCommunitiesByIdentity() {
        TestGraphStore store = storeWithPackages();
        Counting counting = new Counting(store);
        GlobalRetrievalOptions options = GlobalRetrievalOptions.defaults().withMaxCommunities(1);

        RetrievalResult result = new RetrieveGlobalContext(counting, null, placeOrderSeed(), options)
                .retrieve("Who calls placeOrder?", CORPUS);
        RetrievalResult expected = new RetrieveGlobalContext(store, null, placeOrderSeed(), options)
                .retrieve("Who calls placeOrder?", CORPUS);

        assertEquals(RetrievalResult.Status.MATCHED, result.status());
        assertEquals(identifiers(expected), identifiers(result));
        assertEquals("pkg:order", result.items().getFirst().identifier());
        assertEquals(0, counting.entityReads, "no read of the whole Entity set");
        assertEquals(0, counting.relationshipReads, "no read of the whole Relationship set");
        assertTrue(counting.targetedEntityReads > 0 && counting.targetedRelationshipReads > 0);
    }

    @Test
    void driftRetrievalReadsNoWholeCorpusEither() {
        TestGraphStore store = storeWithPackages();
        Counting counting = new Counting(store);

        RetrievalResult result = new RetrieveDriftContext(counting, null, null, placeOrderSeed(),
                DriftRetrievalOptions.defaults()).retrieve("Who calls placeOrder?", CORPUS);

        assertEquals(RetrievalResult.Status.MATCHED, result.status());
        assertEquals(0, counting.entityReads);
        assertEquals(0, counting.relationshipReads);
    }

    @Test
    void theAnswerPathReadsByIdentityToo() {
        TestGraphStore store = storeWithPackages();
        Counting counting = new Counting(store);
        LlmPort llm = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus corpus) {
                return GraphExtraction.empty();
            }

            @Override
            public boolean synthesizesAnswers() {
                return true;
            }

            @Override
            public SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context) {
                return new SynthesizedAnswer(false, "Order placement.");
            }
        };

        new AnswerGlobalSearch(counting, null, llm).answer("order placement persistence", CORPUS);

        assertEquals(0, counting.entityReads);
        assertEquals(0, counting.relationshipReads);
    }

    @Test
    void aCachingViewReadsTheCorpusOnceForManyQuestions() {
        TestGraphStore store = storeWithPackages();
        Counting counting = new Counting(store);
        RetrieveGlobalContext global = new RetrieveGlobalContext(new CachingGraphReadPort(counting));

        for (String question : List.of("placeOrder", "charge payments", "save the order")) {
            assertEquals(RetrievalResult.Status.MATCHED, global.retrieve(question, CORPUS).status(), question);
        }

        // The keyword matcher reads every Entity to score the members; the view does that once.
        assertEquals(1, counting.entityReads);
    }

    @Test
    void importReadsOnlyTheMissingEndpointsOfTheStore() {
        TestGraphStore store = SmallCodeGraph.store();
        ImportKnowledgeGraph importer = new ImportKnowledgeGraph(store);
        Relationship toStored = new Relationship("com.shop.Brand", "Class", "USES", SERVICE, "Class");
        Relationship toNowhere = new Relationship("com.shop.Brand", "Class", "USES", "com.shop.Gone", "Class");

        ImportResult result = importer.run(CORPUS, KnowledgeGraphImport.of(List.of(),
                        List.of(new Entity("com.shop.Brand", "Class")), List.of(toStored, toNowhere)),
                ImportKnowledgeGraph.Options.defaults().withDetectCommunities(false).withEmbed(false));

        assertEquals(0, store.entityReads, "the endpoints are looked up by identity");
        assertEquals(1, result.placeholderEntities());
        assertTrue(store.entities(CORPUS).stream().anyMatch(entity -> entity.name().equals("com.shop.Gone")));
        assertEquals(1, store.entities(CORPUS).stream().filter(entity -> entity.name().equals(SERVICE)).count());
    }
}
