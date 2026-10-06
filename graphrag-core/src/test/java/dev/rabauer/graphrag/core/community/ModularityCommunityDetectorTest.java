package dev.rabauer.graphrag.core.community;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModularityCommunityDetectorTest {

    private final ModularityCommunityDetector detector = new ModularityCommunityDetector();

    @Test
    void twoCliquesJoinedByABridgeBecomeTwoCommunities() {
        List<List<String>> groups = detector.detect(twoCliqueIds(), twoCliqueEdges());

        assertEquals(List.of(List.of("A1", "A2", "A3", "A4"), List.of("B1", "B2", "B3", "B4")), groups);
    }

    @Test
    void singleTriangleStaysOneCommunity() {
        List<WeightedEdge> edges = List.of(edge("a", "b"), edge("b", "c"), edge("c", "a"));

        assertEquals(List.of(List.of("a", "b", "c")), detector.detect(List.of("a", "b", "c"), edges));
    }

    @Test
    void pathOfFourIsNoWorseThanAllInOne() {
        List<String> ids = List.of("p1", "p2", "p3", "p4");
        List<WeightedEdge> edges = List.of(edge("p1", "p2"), edge("p2", "p3"), edge("p3", "p4"));

        List<List<String>> groups = detector.detect(ids, edges);

        assertContract(ids, groups);
        assertTrue(ModularityCommunityDetector.modularity(groups, edges, 1.0)
                >= ModularityCommunityDetector.modularity(List.of(ids), edges, 1.0) - 1e-12);
    }

    @Test
    void isolatedNodeIsASingleton() {
        List<String> ids = new ArrayList<>(twoCliqueIds());
        ids.add("lonely");

        List<List<String>> groups = detector.detect(ids, twoCliqueEdges());

        assertEquals(3, groups.size());
        assertEquals(List.of("lonely"), groups.get(2));
    }

    @Test
    void noEdgesYieldsSingletonsInInputOrder() {
        List<List<List<String>>> levels = detector.detectHierarchy(List.of("x", "y", "z"), List.of());

        assertEquals(1, levels.size());
        assertEquals(List.of(List.of("x"), List.of("y"), List.of("z")), levels.getFirst());
    }

    @Test
    void emptyInputYieldsEmptyList() {
        assertEquals(List.of(), detector.detect(List.of(), List.of()));
        assertEquals(List.of(), detector.detect(null, null));
        assertEquals(List.of(), detector.detect(List.of(), twoCliqueEdges()));
        assertEquals(List.of(), detector.detectHierarchy(List.of(), List.of()));
    }

    @Test
    void nullBlankAndDuplicateIdsAreHandled() {
        List<String> ids = new ArrayList<>(List.of("a", "b", "a", " ", "c"));
        ids.add(null);
        List<WeightedEdge> edges = List.of(edge("a", "b"), edge("b", "c"), edge("c", "a"),
                new WeightedEdge(null, "a", 5), new WeightedEdge("a", "b", Double.NaN),
                new WeightedEdge("a", "b", -1), new WeightedEdge("a", "b", Double.POSITIVE_INFINITY));

        assertEquals(List.of(List.of("a", "b", "c")), detector.detect(ids, edges));
    }

    @Test
    void selfLoopNeverCausesAMove() {
        List<WeightedEdge> edges = new ArrayList<>(twoCliqueEdges());
        edges.add(new WeightedEdge("solo", "solo", 3.0));
        List<String> ids = new ArrayList<>(twoCliqueIds());
        ids.add("solo");

        List<List<String>> groups = detector.detect(ids, edges);

        assertEquals(List.of(List.of("A1", "A2", "A3", "A4"), List.of("B1", "B2", "B3", "B4"), List.of("solo")),
                groups);
    }

    @Test
    void endpointsOutsideNodeIdsAreNeverReturned() {
        List<WeightedEdge> edges = new ArrayList<>(twoCliqueEdges());
        edges.add(edge("A1", "ghost1"));
        edges.add(edge("ghost1", "ghost2"));
        edges.add(edge("ghost2", "ghost3"));
        edges.add(edge("ghost3", "ghost1"));

        List<String> ids = twoCliqueIds();
        for (List<List<String>> level : detector.detectHierarchy(ids, edges)) {
            assertContract(ids, level);
        }
    }

    @Test
    void parallelAndReverseEdgesAreSummed() {
        List<String> ids = List.of("A1", "A2", "A3", "B1", "B2", "B3", "X");
        List<WeightedEdge> edges = List.of(
                edge("A1", "A2"), edge("A2", "A3"), edge("A3", "A1"),
                edge("B1", "B2"), edge("B2", "B3"), edge("B3", "B1"),
                new WeightedEdge("A1", "X", 1.0),
                // 0.6 + 0.6 = 1.2 > 1.0: only when summed does X belong with the Bs.
                new WeightedEdge("B1", "X", 0.6),
                new WeightedEdge("X", "B1", 0.6));

        List<List<String>> groups = detector.detect(ids, edges);

        assertEquals(List.of(List.of("A1", "A2", "A3"), List.of("B1", "B2", "B3", "X")), groups);
    }

    @Test
    void sameSeedTwiceGivesIdenticalResults() {
        Graph g = randomGraph(500, 2000, 7);
        ModularityCommunityDetector a = new ModularityCommunityDetector();
        ModularityCommunityDetector b = new ModularityCommunityDetector();

        assertEquals(a.detectHierarchy(g.ids, g.edges), b.detectHierarchy(g.ids, g.edges));
    }

    /**
     * Different seeds may produce different (equally valid) partitions; what the
     * seed guarantees is that each one is reproducible and the contract holds.
     */
    @Test
    void eachSeedIsReproducible() {
        Graph g = randomGraph(300, 900, 11);
        for (long seed = 0; seed < 5; seed++) {
            ModularityCommunityDetector.Options options = ModularityCommunityDetector.Options.defaults()
                    .withSeed(seed);
            List<List<String>> first = new ModularityCommunityDetector(options).detect(g.ids, g.edges);
            List<List<String>> second = new ModularityCommunityDetector(options).detect(g.ids, g.edges);
            assertEquals(first, second);
            assertContract(g.ids, first);
            assertEquals(seed, new ModularityCommunityDetector(options).options().seed());
        }
    }

    @Test
    void highResolutionSplitsIntoMoreCommunities() {
        ModularityCommunityDetector fine = new ModularityCommunityDetector(
                ModularityCommunityDetector.Options.defaults().withResolution(50));

        List<List<String>> coarseGroups = detector.detect(twoCliqueIds(), twoCliqueEdges());
        List<List<String>> fineGroups = fine.detect(twoCliqueIds(), twoCliqueEdges());

        assertTrue(fineGroups.size() > coarseGroups.size());
        assertContract(twoCliqueIds(), fineGroups);
    }

    @Test
    void everyCommunityIsConnectedWithRefinement() {
        for (int seed = 0; seed < 10; seed++) {
            Graph g = randomGraph(400, 700, seed);
            ModularityCommunityDetector d = new ModularityCommunityDetector(
                    ModularityCommunityDetector.Options.defaults().withSeed(seed));
            for (List<List<String>> level : d.detectHierarchy(g.ids, g.edges)) {
                assertContract(g.ids, level);
                for (List<String> group : level) {
                    assertTrue(isConnected(group, g.edges), "disconnected community " + group);
                }
            }
        }
    }

    @Test
    void hierarchyGoesFromFinerToCoarserAndEndsWithDetect() {
        Graph g = ringOfCliques(30, 5);
        List<List<List<String>>> levels = detector.detectHierarchy(g.ids, g.edges);

        assertFalse(levels.isEmpty());
        for (int l = 1; l < levels.size(); l++) {
            assertTrue(levels.get(l).size() <= levels.get(l - 1).size());
        }
        assertEquals(levels.getLast(), detector.detect(g.ids, g.edges));
    }

    @Test
    void twoCliqueResultHasHigherModularityThanAllInOne() {
        List<List<String>> groups = detector.detect(twoCliqueIds(), twoCliqueEdges());

        double split = ModularityCommunityDetector.modularity(groups, twoCliqueEdges(), 1.0);
        double allInOne = ModularityCommunityDetector.modularity(List.of(twoCliqueIds()), twoCliqueEdges(), 1.0);

        assertTrue(split > allInOne);
        assertEquals(0.0, allInOne, 1e-12);
        assertEquals(0.0, ModularityCommunityDetector.modularity(groups, List.of(), 1.0));
    }

    @Test
    void invalidOptionsAreRejected() {
        ModularityCommunityDetector.Options defaults = ModularityCommunityDetector.Options.defaults();
        assertThrows(IllegalArgumentException.class, () -> defaults.withResolution(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withResolution(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.withResolution(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> defaults.withResolution(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxLevels(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxIterationsPerLevel(0));
        assertEquals(new ModularityCommunityDetector.Options(42L, 1.0, 10, 50, true), defaults);
    }

    /**
     * Ring of 30 five-cliques: because of modularity's resolution limit
     * (30 > 5 * 4 + 2 cliques), merging adjacent clique pairs scores higher than
     * one community per clique. Whatever is found must be a union of whole
     * cliques and at least as good as the per-clique partition.
     */
    @Test
    void ringOfCliquesYieldsUnionsOfWholeCliques() {
        int cliques = 30;
        int size = 5;
        Graph g = ringOfCliques(cliques, size);

        List<List<String>> groups = detector.detect(g.ids, g.edges);

        assertContract(g.ids, groups);
        for (List<String> group : groups) {
            Set<String> cliqueIds = new HashSet<>();
            for (String id : group) {
                cliqueIds.add(id.substring(0, id.indexOf('-')));
            }
            assertEquals(cliqueIds.size() * size, group.size(), "not whole cliques: " + group);
        }
        assertTrue(groups.size() >= cliques / 3 && groups.size() <= cliques);
        List<List<String>> perClique = new ArrayList<>();
        for (int c = 0; c < cliques; c++) {
            perClique.add(g.ids.subList(c * size, (c + 1) * size));
        }
        assertTrue(ModularityCommunityDetector.modularity(groups, g.edges, 1.0)
                >= ModularityCommunityDetector.modularity(perClique, g.edges, 1.0) - 1e-9);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void largeGraphRunsQuickly() {
        int groupCount = 200;
        int groupSize = 100;
        Random random = new Random(3);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < groupCount * groupSize; i++) {
            ids.add("n" + i);
        }
        List<WeightedEdge> edges = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            int group = i / groupSize;
            for (int k = 0; k < 4; k++) {
                int j = group * groupSize + random.nextInt(groupSize);
                edges.add(edge(ids.get(i), ids.get(j)));
            }
            edges.add(edge(ids.get(i), ids.get(random.nextInt(ids.size()))));
        }

        List<List<String>> groups = detector.detect(ids, edges);

        assertContract(ids, groups);
        assertTrue(ModularityCommunityDetector.modularity(groups, edges, 1.0) > 0.5);
    }

    private static WeightedEdge edge(String a, String b) {
        return new WeightedEdge(a, b, 1.0);
    }

    private static List<String> twoCliqueIds() {
        return List.of("A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4");
    }

    private static List<WeightedEdge> twoCliqueEdges() {
        List<WeightedEdge> edges = new ArrayList<>();
        for (String prefix : List.of("A", "B")) {
            for (int i = 1; i <= 4; i++) {
                for (int j = i + 1; j <= 4; j++) {
                    edges.add(edge(prefix + i, prefix + j));
                }
            }
        }
        edges.add(edge("A4", "B1"));
        return edges;
    }

    private record Graph(List<String> ids, List<WeightedEdge> edges) {
    }

    private static Graph ringOfCliques(int cliques, int size) {
        List<String> ids = new ArrayList<>();
        List<WeightedEdge> edges = new ArrayList<>();
        for (int c = 0; c < cliques; c++) {
            for (int i = 0; i < size; i++) {
                ids.add("c" + c + "-" + i);
                for (int j = 0; j < i; j++) {
                    edges.add(edge("c" + c + "-" + i, "c" + c + "-" + j));
                }
            }
            edges.add(edge("c" + c + "-0", "c" + ((c + 1) % cliques) + "-" + (size - 1)));
        }
        return new Graph(ids, edges);
    }

    private static Graph randomGraph(int nodes, int edgeCount, long seed) {
        Random random = new Random(seed);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            ids.add("v" + i);
        }
        List<WeightedEdge> edges = new ArrayList<>();
        for (int e = 0; e < edgeCount; e++) {
            edges.add(new WeightedEdge(ids.get(random.nextInt(nodes)), ids.get(random.nextInt(nodes)),
                    0.5 + random.nextDouble()));
        }
        return new Graph(ids, edges);
    }

    private static boolean isConnected(List<String> group, List<WeightedEdge> edges) {
        Set<String> members = new HashSet<>(group);
        Map<String, List<String>> adjacency = new HashMap<>();
        for (WeightedEdge e : edges) {
            if (members.contains(e.source()) && members.contains(e.target())) {
                adjacency.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
                adjacency.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e.source());
            }
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(group.getFirst());
        visited.add(group.getFirst());
        while (!queue.isEmpty()) {
            for (String next : adjacency.getOrDefault(queue.poll(), List.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return visited.size() == members.size();
    }

    private static void assertContract(List<String> ids, List<List<String>> groups) {
        List<String> distinct = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
        List<String> flattened = new ArrayList<>();
        int previousFirst = -1;
        for (List<String> group : groups) {
            assertFalse(group.isEmpty());
            int first = distinct.indexOf(group.getFirst());
            assertTrue(first > previousFirst, "groups not ordered by first member");
            previousFirst = first;
            int previous = -1;
            for (String id : group) {
                int index = distinct.indexOf(id);
                assertTrue(index > previous, "members not in input order or unknown: " + id);
                previous = index;
            }
            flattened.addAll(group);
        }
        assertEquals(distinct.size(), flattened.size());
        assertEquals(new HashSet<>(distinct), new HashSet<>(flattened));
    }
}
