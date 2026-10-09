package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.retrieval.LocalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.LocalRetrievalOptions.RelationshipOrdering;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;
import dev.rabauer.graphrag.core.retrieval.SeedMatch;
import dev.rabauer.graphrag.core.retrieval.SeedMatcher;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The caps and the ordering hook of Local expansion: per hop, per new node, by a caller's comparator. */
class LocalExpansionHopsTest {

    private static final String CORPUS = "hops";

    private final TestGraphStore store = new TestGraphStore();

    private static Relationship edge(String source, String type, String target, int weight) {
        return new Relationship(source, "Class", type, target, "Class", "", List.of(), weight);
    }

    private static String id(String name) {
        return Entity.identityOf(name, "Class");
    }

    private void entities(String... names) {
        for (String name : names) {
            store.persistEntities(CORPUS, List.of(new Entity(name, "Class")));
        }
    }

    private RetrievalResult retrieve(Set<String> seeds, LocalRetrievalOptions options) {
        SeedMatcher matcher = (question, corpusId, graph, limit) -> graph.entities(corpusId).stream()
                .filter(entity -> seeds.contains(entity.name())).map(entity -> new SeedMatch(entity, 1, "test"))
                .toList();
        return new RetrieveLocalContext(store, matcher, options).retrieve("q", CORPUS);
    }

    private static LocalRetrievalOptions base() {
        return LocalRetrievalOptions.defaults().withSeedLimit(3).withMaxHops(2).withMaxTextUnits(0);
    }

    private static List<String> entityHops(RetrievalResult result) {
        return result.items().stream().filter(item -> item.kind() == RetrievalStep.Kind.ENTITY)
                .map(item -> item.identifier() + "@" + item.hop()).toList();
    }

    /** Three densely connected seeds A, B, C with heavy edges, one weak edge out (C-X) and X-Y behind it. */
    private void denseSeeds() {
        entities("A", "B", "C", "X", "Y");
        store.persistRelationships(CORPUS, List.of(
                edge("A", "calls", "B", 9), edge("B", "calls", "C", 9), edge("A", "calls", "C", 9),
                edge("C", "calls", "X", 1), edge("X", "calls", "Y", 1)));
    }

    @Test
    void denselyConnectedSeedsUseUpTheCapBeforeAnyNewClassIsReached() {
        denseSeeds();

        RetrievalResult result = retrieve(Set.of("A", "B", "C"), base().withMaxRelationships(3));

        assertEquals(List.of(id("A") + "@0", id("B") + "@0", id("C") + "@0"), entityHops(result));
    }

    @Test
    void aPerHopCapWithNewNodesFirstReachesTheSecondHop() {
        denseSeeds();
        LocalRetrievalOptions options = base().withMaxRelationships(4).withMaxRelationshipsPerHop(3)
                .withOrdering(RelationshipOrdering.NEW_NODES_FIRST);

        // Without them, hop 1 takes all four Relationships and nothing is left for hop 2.
        assertTrue(entityHops(retrieve(Set.of("A", "B", "C"), base().withMaxRelationships(4)))
                .stream().noneMatch(hop -> hop.startsWith(id("Y"))));
        RetrievalResult result = retrieve(Set.of("A", "B", "C"), options);

        assertTrue(entityHops(result).contains(id("X") + "@1"), entityHops(result).toString());
        assertTrue(entityHops(result).contains(id("Y") + "@2"), entityHops(result).toString());
        long hopOne = result.items().stream()
                .filter(item -> item.kind() == RetrievalStep.Kind.RELATIONSHIP && item.hop() == 1).count();
        assertEquals(3, hopOne);
    }

    @Test
    void theNewNodeCapKeepsAHubFromTakingOverAHop() {
        entities("S", "N1", "N2", "N3", "N4", "N5");
        store.persistRelationships(CORPUS, List.of(
                edge("S", "calls", "N1", 5), edge("S", "calls", "N2", 4), edge("S", "calls", "N3", 3),
                edge("S", "calls", "N4", 2), edge("S", "calls", "N5", 1)));

        RetrievalResult result = retrieve(Set.of("S"), base().withMaxNewNodesPerHop(2));

        assertEquals(List.of(id("S") + "@0", id("N1") + "@1", id("N2") + "@1"), entityHops(result));
    }

    @Test
    void theNewNodeCapStillAllowsRelationshipsBetweenIncludedEntities() {
        entities("S", "N1", "N2");
        store.persistRelationships(CORPUS, List.of(
                edge("S", "calls", "N1", 5), edge("S", "calls", "N2", 4), edge("N1", "calls", "N2", 3)));

        RetrievalResult result = retrieve(Set.of("S"), base().withMaxNewNodesPerHop(2));

        long relationships = result.items().stream().filter(item -> item.kind() == RetrievalStep.Kind.RELATIONSHIP)
                .count();
        assertEquals(3, relationships);
    }

    @Test
    void aComparatorReplacesTheWeightOrdering() {
        entities("S", "P", "Q");
        store.persistRelationships(CORPUS, List.of(edge("S", "imports", "Q", 9), edge("S", "calls", "P", 1)));
        Comparator<Relationship> callsFirst = Comparator.comparing(relationship ->
                relationship.type().equals("calls") ? 0 : 1);

        RetrievalResult byWeight = retrieve(Set.of("S"), base().withMaxRelationships(1));
        RetrievalResult byComparator = retrieve(Set.of("S"),
                base().withMaxRelationships(1).withRelationshipComparator(callsFirst));

        assertEquals(List.of(id("S") + "@0", id("Q") + "@1"), entityHops(byWeight));
        assertEquals(List.of(id("S") + "@0", id("P") + "@1"), entityHops(byComparator));
    }

    @Test
    void theNewOptionsDefaultToTheOldBehaviour() {
        LocalRetrievalOptions defaults = LocalRetrievalOptions.defaults();

        assertEquals(Integer.MAX_VALUE, defaults.maxRelationshipsPerHop());
        assertEquals(Integer.MAX_VALUE, defaults.maxNewNodesPerHop());
        assertEquals(null, defaults.relationshipComparator());
        assertEquals(defaults, new LocalRetrievalOptions(3, 1, 50, 25, 10, 100, Set.of(), Set.of(), 1,
                RelationshipOrdering.WEIGHT_DESC, LocalRetrievalOptions.Direction.BOTH, true, true));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxNewNodesPerHop(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxRelationshipsPerHop(-1));
    }
}
