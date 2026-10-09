package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.community.CommunityDetector;
import dev.rabauer.graphrag.core.community.GraphCommunities;
import dev.rabauer.graphrag.core.community.WeightedEdge;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityStats;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Member order and number, hierarchy level, maximum size and statistics of the
 * Communities {@link DetectCommunities} writes.
 */
class DetectCommunitiesHierarchyTest {

    private static final String CORPUS = "repo";

    /** A grouping with several levels: level i cuts the nodes into chunks of {@code sizes[i]}; the last is the coarsest. */
    private static CommunityDetector levels(int... sizes) {
        return new CommunityDetector() {
            @Override
            public List<List<String>> detect(List<String> nodeIds, List<WeightedEdge> edges) {
                return detectHierarchy(nodeIds, edges).getLast();
            }

            @Override
            public List<List<List<String>>> detectHierarchy(List<String> nodeIds, List<WeightedEdge> edges) {
                List<List<List<String>>> levels = new ArrayList<>();
                for (int size : sizes) {
                    List<List<String>> groups = new ArrayList<>();
                    for (int from = 0; from < nodeIds.size(); from += size) {
                        groups.add(List.copyOf(nodeIds.subList(from, Math.min(nodeIds.size(), from + size))));
                    }
                    levels.add(groups);
                }
                return levels;
            }
        };
    }

    private static String name(int index) {
        return "n%02d".formatted(index);
    }

    /** {@code count} Entities n01.., the first two thirds in module "core", the rest in module "ui". */
    private static TestGraphStore store(int count) {
        TestGraphStore store = new TestGraphStore();
        List<Entity> entities = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            entities.add(new Entity(name(i), "Class", "", List.of(),
                    Map.of("module", i <= count * 2 / 3 ? "core" : "ui"), null));
        }
        store.persistEntities(CORPUS, entities);
        return store;
    }

    /** Records what each summary call receives. */
    private static final class Recording implements LlmPort {
        final List<List<String>> members = new ArrayList<>();
        final List<CommunityStats> stats = new ArrayList<>();

        @Override
        public GraphExtraction extract(Corpus corpus) {
            return GraphExtraction.empty();
        }

        @Override
        public boolean summarizesCommunities() {
            return true;
        }

        @Override
        public CommunitySummary summarizeCommunity(Collection<Entity> group, Collection<Relationship> relationships,
                                                   CommunityStats communityStats) {
            members.add(group.stream().map(Entity::name).toList());
            stats.add(communityStats);
            return new CommunitySummary("t", "s");
        }
    }

    private static List<Integer> sizes(TestGraphStore store, List<Community> communities) {
        return communities.stream().map(community -> store.memberIdentities(CORPUS, community.id()).size()).toList();
    }

    // -- Member order ---------------------------------------------------------------------

    @Test
    void theSummaryGetsTheFirstMembersInStoredOrderByDefault() {
        TestGraphStore store = store(30);
        Recording llm = new Recording();

        new DetectCommunities(store, llm, DetectCommunities.Options.defaults().withDetector(levels(30))).run(CORPUS);

        assertEquals(IntStream.rangeClosed(1, 25).mapToObj(DetectCommunitiesHierarchyTest::name).toList(),
                llm.members.getFirst());
    }

    @Test
    void aMemberOrderChoosesWhichMembersAreSummarizedAndInWhichOrder() {
        TestGraphStore store = store(30);
        Recording llm = new Recording();
        DetectCommunities.Options options = DetectCommunities.Options.defaults().withDetector(levels(30))
                .withSummaryMemberOrder(Comparator.comparing(Entity::name).reversed());

        new DetectCommunities(store, llm, options).run(CORPUS);

        // n30 down to n06: the 25 names that sort last.
        assertEquals(IntStream.rangeClosed(6, 30).map(i -> 36 - i).mapToObj(DetectCommunitiesHierarchyTest::name).toList(),
                llm.members.getFirst());
        // The Community still has every member.
        assertEquals(30, store.memberIdentities(CORPUS, "community-1").size());
    }

    @Test
    void theNumberOfSummarizedMembersIsAnOption() {
        TestGraphStore store = store(30);
        Recording llm = new Recording();

        new DetectCommunities(store, llm,
                DetectCommunities.Options.defaults().withDetector(levels(30)).withMaxSummaryMembers(10)).run(CORPUS);

        assertEquals(10, llm.members.getFirst().size());
        assertThrows(IllegalArgumentException.class, () -> DetectCommunities.Options.defaults().withMaxSummaryMembers(0));
    }

    // -- Statistics -----------------------------------------------------------------------

    @Test
    void theSummaryPortGetsTheSizeAndTheCountsByModule() {
        TestGraphStore store = store(30);
        Recording llm = new Recording();

        new DetectCommunities(store, llm, DetectCommunities.Options.defaults().withDetector(levels(30))).run(CORPUS);

        CommunityStats stats = llm.stats.getFirst();
        assertEquals(30, stats.memberCount());
        assertEquals(25, stats.listedMembers());
        assertEquals(Map.of("core", 20, "ui", 10), stats.attributeCounts().get("module"));
        assertEquals(List.of("core", "ui"), List.copyOf(stats.attributeCounts().get("module").keySet()));
        assertTrue(!stats.attributeCounts().containsKey("package"), "an attribute no member has is left out");
    }

    @Test
    void theCountedAttributesAreAnOption() {
        TestGraphStore store = store(6);
        Recording llm = new Recording();

        new DetectCommunities(store, llm, DetectCommunities.Options.defaults().withDetector(levels(6))
                .withStatsAttributes(List.of("missing"))).run(CORPUS);

        assertEquals(6, llm.stats.getFirst().memberCount());
        assertTrue(llm.stats.getFirst().attributeCounts().isEmpty());
    }

    @Test
    void aPortThatOnlyKnowsTheOlderOverloadStillWorks() {
        TestGraphStore store = store(6);
        List<Integer> seen = new ArrayList<>();
        LlmPort old = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus corpus) {
                return GraphExtraction.empty();
            }

            @Override
            public CommunitySummary summarizeCommunity(Collection<Entity> members,
                                                       Collection<Relationship> relationships) {
                seen.add(members.size());
                return new CommunitySummary("t", "s");
            }
        };

        new DetectCommunities(store, old, DetectCommunities.Options.defaults().withDetector(levels(6))).run(CORPUS);

        assertEquals(List.of(6), seen);
    }

    // -- Hierarchy ------------------------------------------------------------------------

    @Test
    void theCoarsestLevelIsTheDefault() {
        TestGraphStore store = store(12);

        List<Community> communities = new DetectCommunities(store, null,
                DetectCommunities.Options.defaults().withDetector(levels(3, 6))).run(CORPUS).communities();

        assertEquals(List.of(6, 6), sizes(store, communities));
    }

    @Test
    void aHierarchyLevelChoosesHowFineTheCommunitiesAre() {
        for (Map.Entry<Integer, List<Integer>> expected : Map.of(
                0, List.of(3, 3, 3, 3), 1, List.of(6, 6), 7, List.of(6, 6)).entrySet()) {
            TestGraphStore store = store(12);

            List<Community> communities = new DetectCommunities(store, null, DetectCommunities.Options.defaults()
                    .withDetector(levels(3, 6)).withHierarchyLevel(expected.getKey())).run(CORPUS).communities();

            assertEquals(expected.getValue(), sizes(store, communities), "level " + expected.getKey());
        }
    }

    @Test
    void aMaximumSizeDescendsTheHierarchyUntilTheCommunitiesFit() {
        DetectCommunities.Options options = DetectCommunities.Options.defaults().withDetector(levels(2, 4, 12))
                .withMinCommunitySize(2);

        assertEquals(List.of(12), run(options));
        assertEquals(List.of(4, 4, 4), run(options.withMaxCommunitySize(5)));
        assertEquals(List.of(2, 2, 2, 2, 2, 2), run(options.withMaxCommunitySize(3)));
        // Nothing is finer than level 0: those Communities stay as they are.
        assertEquals(List.of(2, 2, 2, 2, 2, 2), run(options.withMaxCommunitySize(1)));
    }

    private static List<Integer> run(DetectCommunities.Options options) {
        TestGraphStore store = store(12);
        return sizes(store, new DetectCommunities(store, null, options).run(CORPUS).communities());
    }

    @Test
    void onlyTheOversizedCommunitiesAreSplit() {
        // Level 1: one group of the first 8 nodes, then pairs; level 0: pairs.
        CommunityDetector uneven = new CommunityDetector() {
            @Override
            public List<List<String>> detect(List<String> nodeIds, List<WeightedEdge> edges) {
                return detectHierarchy(nodeIds, edges).getLast();
            }

            @Override
            public List<List<List<String>>> detectHierarchy(List<String> nodeIds, List<WeightedEdge> edges) {
                List<List<String>> fine = new ArrayList<>();
                for (int from = 0; from < nodeIds.size(); from += 2) {
                    fine.add(nodeIds.subList(from, from + 2));
                }
                List<List<String>> coarse = new ArrayList<>(List.of(nodeIds.subList(0, 8)));
                coarse.addAll(fine.subList(4, fine.size()));
                return List.of(fine, coarse);
            }
        };

        List<Integer> sizes = run(DetectCommunities.Options.defaults().withDetector(uneven).withMaxCommunitySize(4)
                .withMinCommunitySize(2));

        // The group of 8 became pairs; the pairs of level 1 were already small enough.
        assertEquals(List.of(2, 2, 2, 2, 2, 2), sizes);
        assertEquals(List.of(8, 2, 2), run(DetectCommunities.Options.defaults().withDetector(uneven)
                .withMinCommunitySize(2)));
    }

    @Test
    void theHierarchyOfTheDefaultDetectorIsUsedWithoutADetector() {
        TestGraphStore store = store(6);
        store.persistRelationships(CORPUS, List.of(
                new Relationship(name(1), "Class", "CALLS", name(2), "Class"),
                new Relationship(name(2), "Class", "CALLS", name(3), "Class"),
                new Relationship(name(3), "Class", "CALLS", name(1), "Class"),
                new Relationship(name(4), "Class", "CALLS", name(5), "Class"),
                new Relationship(name(5), "Class", "CALLS", name(6), "Class"),
                new Relationship(name(6), "Class", "CALLS", name(4), "Class")));

        List<Community> communities = new DetectCommunities(store, null,
                DetectCommunities.Options.defaults().withHierarchyLevel(0)).run(CORPUS).communities();

        assertEquals(List.of(3, 3), sizes(store, communities));
    }

    @Test
    void theSelectionIsAlsoAvailableOnTheGroupingItself() {
        List<List<List<String>>> hierarchy = levels(2, 4).detectHierarchy(
                List.of("a", "b", "c", "d", "e", "f", "g", "h"), List.of());

        assertEquals(hierarchy.get(1), GraphCommunities.select(hierarchy, -1, 0));
        assertEquals(hierarchy.get(0), GraphCommunities.select(hierarchy, 0, 0));
        assertEquals(hierarchy.get(0), GraphCommunities.select(hierarchy, -1, 3));
        assertEquals(List.of(), GraphCommunities.select(List.of(), 0, 0));
    }

    @Test
    void theOptionsValidateAndKeepTheirDefaults() {
        DetectCommunities.Options defaults = DetectCommunities.Options.defaults();

        assertEquals(25, defaults.maxSummaryMembers());
        assertEquals(-1, defaults.hierarchyLevel());
        assertEquals(0, defaults.maxCommunitySize());
        assertEquals(null, defaults.summaryMemberOrder());
        assertEquals(List.of("module", "package"), defaults.statsAttributes());
        assertEquals(defaults, new DetectCommunities.Options(3, null, 1, Integer.MAX_VALUE, null,
                FailurePolicy.FAIL_RUN, false));
        assertThrows(IllegalArgumentException.class, () -> defaults.withHierarchyLevel(-2));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxCommunitySize(-1));
    }
}
