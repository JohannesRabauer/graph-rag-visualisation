package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.GraphReadPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.GraphWritePort;
import dev.rabauer.graphrag.core.retrieval.IdentifierSeedMatcher;
import dev.rabauer.graphrag.core.retrieval.LocalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CORPUS;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.PLACE_ORDER;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.SERVICE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The query use cases need only the read side of the graph. */
class ReadOnlyGraphPortTest {

    /**
     * A read-only view over another store, like an application's own graph:
     * it answers expansion hop by hop and fails the test if the full
     * Relationship list is read.
     */
    private static final class ReadOnlyView implements GraphReadPort {
        private final TestGraphStore delegate;
        final List<Collection<String>> touchingCalls = new ArrayList<>();
        boolean fullRelationshipsRead;

        ReadOnlyView(TestGraphStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Collection<Entity> entities(String corpusId) {
            return delegate.entities(corpusId);
        }

        @Override
        public Collection<Relationship> relationships(String corpusId) {
            fullRelationshipsRead = true;
            return delegate.relationships(corpusId);
        }

        @Override
        public List<Relationship> relationshipsTouching(String corpusId, Collection<String> identities) {
            touchingCalls.add(List.copyOf(identities));
            return delegate.relationships(corpusId).stream()
                    .filter(relationship -> identities.contains(relationship.sourceIdentity())
                            || identities.contains(relationship.targetIdentity()))
                    .toList();
        }

        @Override
        public Optional<TextUnit> textUnit(String corpusId, String textUnitId) {
            return delegate.textUnit(corpusId, textUnitId);
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

    @Test
    void theReadPortIsNotAWritePort() {
        assertFalse(GraphWritePort.class.isAssignableFrom(GraphReadPort.class));
        assertTrue(GraphReadPort.class.isAssignableFrom(GraphStorePort.class));
        assertTrue(GraphWritePort.class.isAssignableFrom(GraphStorePort.class));
    }

    @Test
    void localRetrievalExpandsHopByHopWithoutReadingEveryRelationship() {
        ReadOnlyView view = new ReadOnlyView(SmallCodeGraph.store());

        RetrievalResult result = new RetrieveLocalContext(view, new IdentifierSeedMatcher(),
                LocalRetrievalOptions.defaults().withSeedLimit(1).withMaxHops(2)).retrieve("placeOrder", CORPUS);

        assertEquals(RetrievalResult.Status.MATCHED, result.status());
        assertFalse(view.fullRelationshipsRead);
        assertEquals(List.of(id(PLACE_ORDER, "Method")), view.touchingCalls.getFirst());
        assertEquals(2, view.touchingCalls.size());
    }

    @Test
    void everyAnswerUseCaseRunsOnAReadOnlyPort() {
        TestGraphStore store = SmallCodeGraph.store();
        store.persistCommunities(CORPUS, List.of(new Community("pkg:order", "order", "Order placement.")));
        store.persistCommunityMemberships(CORPUS, List.of(new CommunityMembership("pkg:order", id(SERVICE, "Class")),
                new CommunityMembership("pkg:order", id(PLACE_ORDER, "Method"))));
        GraphReadPort view = new ReadOnlyView(store);

        assertFalse(new AnswerLocalSearch(view).answer("placeOrder", CORPUS).steps().isEmpty());
        assertFalse(new AnswerGlobalSearch(view).answer("Order placement", CORPUS).noAnswer());
        assertFalse(new AnswerDriftSearch(view, null).answer("Order placement", CORPUS).steps().isEmpty());
        assertEquals(RetrievalStep.Kind.COMMUNITY, new RetrieveGlobalContext(view).retrieve("Order placement", CORPUS)
                .items().getFirst().kind());
        assertEquals(RetrievalResult.Status.MATCHED,
                new RetrieveDriftContext(view, null, null, new IdentifierSeedMatcher(), null)
                        .retrieve("placeOrder", CORPUS).status());
    }

    @Test
    void theDefaultLookupsFilterTheCorpusReads() {
        TestGraphStore store = SmallCodeGraph.store();
        GraphReadPort plain = new GraphReadPort() {
            @Override
            public Collection<Entity> entities(String corpusId) {
                return store.entities(corpusId);
            }

            @Override
            public Collection<Relationship> relationships(String corpusId) {
                return store.relationships(corpusId);
            }
        };

        assertEquals(PLACE_ORDER, plain.entity(CORPUS, id(PLACE_ORDER, "Method")).orElseThrow().name());
        assertTrue(plain.entity(CORPUS, "missing::class").isEmpty());
        assertEquals(List.of(SERVICE), plain.entities(CORPUS, List.of(id(SERVICE, "Class"), "missing::class"))
                .stream().map(Entity::name).toList());
        assertEquals(4, plain.relationshipsTouching(CORPUS, List.of(id(PLACE_ORDER, "Method"))).size());
        assertTrue(plain.relationshipsTouching(CORPUS, List.of()).isEmpty());
    }
}
