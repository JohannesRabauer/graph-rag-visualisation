package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.community.ConnectedComponentsCommunityDetector;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.usecase.CommunityDetectionResult.SummaryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectCommunitiesOptionsTest {

    private static final String CORPUS = "repo";

    /** {@code count} disjoint triangles T{i}a, T{i}b, T{i}c — one Community each. */
    private static TestGraphStore triangles(int count) {
        TestGraphStore store = new TestGraphStore();
        List<Entity> entities = new ArrayList<>();
        List<Relationship> relationships = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String a = "T" + i + "a";
            String b = "T" + i + "b";
            String c = "T" + i + "c";
            for (String name : List.of(a, b, c)) {
                entities.add(new Entity(name, "Class", name + " does things.", List.of()));
            }
            relationships.add(new Relationship(a, "Class", "CALLS", b, "Class"));
            relationships.add(new Relationship(b, "Class", "CALLS", c, "Class"));
            relationships.add(new Relationship(c, "Class", "CALLS", a, "Class"));
        }
        store.persistEntities(CORPUS, entities);
        store.persistRelationships(CORPUS, relationships);
        return store;
    }

    /** Summarizes with a counter; fails for members whose name starts with {@code failFor}. */
    private static class CountingLlm implements LlmPort {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final List<String> summarizedFirstMembers = Collections.synchronizedList(new ArrayList<>());
        private final boolean summarizes;
        private final String failFor;
        private final long sleepMs;

        CountingLlm(boolean summarizes, String failFor, long sleepMs) {
            this.summarizes = summarizes;
            this.failFor = failFor;
            this.sleepMs = sleepMs;
        }

        @Override
        public GraphExtraction extract(Corpus corpus) {
            return GraphExtraction.empty();
        }

        @Override
        public boolean summarizesCommunities() {
            return summarizes;
        }

        @Override
        public CommunitySummary summarizeCommunity(Collection<Entity> members, Collection<Relationship> relationships) {
            calls.incrementAndGet();
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                String first = members.iterator().next().name();
                summarizedFirstMembers.add(first);
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
                if (failFor != null && first.startsWith(failFor)) {
                    throw new IllegalStateException("model returned garbage for " + first);
                }
                return new CommunitySummary("Group " + first, "Generated summary of " + first + ".");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    @Test
    void theDefaultRunIsCompleteAndReportsEveryGeneratedSummary() {
        CountingLlm llm = new CountingLlm(false, null, 0);

        CommunityDetectionResult result = new DetectCommunities(triangles(3), llm, DetectCommunities.Options.defaults())
                .run(CORPUS);

        assertEquals(CommunityDetectionResult.Status.COMPLETE, result.status());
        assertTrue(result.isComplete());
        assertEquals(List.of("community-1", "community-2", "community-3"),
                result.communities().stream().map(Community::id).toList());
        assertEquals(3, result.count(SummaryStatus.GENERATED));
        assertEquals(List.of("T1a", "T2a", "T3a"), llm.summarizedFirstMembers);
        assertEquals("Group T1a", result.communities().getFirst().title());
        assertTrue(result.communities().getFirst().attributes().isEmpty());
    }

    @Test
    void withoutAPortEverySummaryIsDeterministic() {
        CommunityDetectionResult result = new DetectCommunities(triangles(2), null, DetectCommunities.Options.defaults())
                .run(CORPUS);

        assertEquals(2, result.count(SummaryStatus.DETERMINISTIC));
        assertEquals(CommunityDetectionResult.Status.COMPLETE, result.status());
    }

    @Test
    void aDetectorOptionReplacesTheStoresGrouping() {
        TestGraphStore store = triangles(2);
        store.persistRelationships(CORPUS, List.of(new Relationship("T1a", "Class", "CALLS", "T2a", "Class")));

        CommunityDetectionResult modularity = new DetectCommunities(store, null, DetectCommunities.Options.defaults())
                .run(CORPUS);
        CommunityDetectionResult components = new DetectCommunities(store, null,
                DetectCommunities.Options.defaults().withDetector(new ConnectedComponentsCommunityDetector()))
                .run(CORPUS);

        assertEquals(2, modularity.communities().size());
        assertEquals(1, components.communities().size());
    }

    @Test
    void failFirstPropagatesTheSummaryFailureUnchanged() {
        CountingLlm llm = new CountingLlm(false, "T2", 0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new DetectCommunities(triangles(3), llm, DetectCommunities.Options.defaults()).run(CORPUS));

        assertEquals("model returned garbage for T2a", failure.getMessage());
    }

    @Test
    void isolatedFailuresFallBackPerItemAndMakeTheRunPartial() {
        TestGraphStore store = triangles(3);
        CountingLlm llm = new CountingLlm(false, "T2", 0);

        CommunityDetectionResult result = new DetectCommunities(store, llm,
                DetectCommunities.Options.defaults().withFailurePolicy(FailurePolicy.ISOLATE_ITEM)).run(CORPUS);

        assertEquals(CommunityDetectionResult.Status.PARTIAL, result.status());
        assertEquals(2, result.count(SummaryStatus.GENERATED));
        assertEquals(1, result.count(SummaryStatus.FAILED));
        CommunityDetectionResult.SummaryOutcome failed = result.summaries().get(1);
        assertEquals("community-2", failed.communityId());
        assertEquals("model returned garbage for T2a", failed.error());
        assertEquals("This community centers on T2a, T2b, T2c.", result.communities().get(1).summary());
        assertEquals(3, store.communities(CORPUS).size());
        assertEquals(9, store.communityMemberships(CORPUS).size());
    }

    @Test
    void theSummaryBudgetSkipsTheRestButPersistsEveryCommunity() {
        TestGraphStore store = triangles(4);
        CountingLlm llm = new CountingLlm(false, null, 0);

        CommunityDetectionResult result = new DetectCommunities(store, llm,
                DetectCommunities.Options.defaults().withMaxSummaries(2)).run(CORPUS);

        assertEquals(2, llm.calls.get());
        assertEquals(List.of(SummaryStatus.GENERATED, SummaryStatus.GENERATED, SummaryStatus.SKIPPED_BUDGET,
                SummaryStatus.SKIPPED_BUDGET), result.summaries().stream()
                .map(CommunityDetectionResult.SummaryOutcome::status).toList());
        assertEquals(CommunityDetectionResult.Status.PARTIAL, result.status());
        assertEquals(4, store.communities(CORPUS).size());
        assertEquals("T3a & T3b", result.communities().get(2).title());
    }

    @Test
    void anExhaustedWallTimeSkipsEverySummary() {
        CountingLlm llm = new CountingLlm(false, null, 0);

        CommunityDetectionResult result = new DetectCommunities(triangles(2), llm,
                DetectCommunities.Options.defaults().withMaxWallTime(Duration.ZERO)).run(CORPUS);

        assertEquals(0, llm.calls.get());
        assertEquals(2, result.count(SummaryStatus.SKIPPED_BUDGET));
        assertEquals(2, result.communities().size());
    }

    @Test
    @Timeout(20)
    void summarizesInParallelUpToTheBoundAndKeepsCommunityOrder() {
        CountingLlm llm = new CountingLlm(true, null, 150);

        CommunityDetectionResult result = new DetectCommunities(triangles(6), llm,
                DetectCommunities.Options.defaults().withParallelism(3)).run(CORPUS);

        assertEquals(6, llm.calls.get());
        assertTrue(llm.maxInFlight.get() > 1, "expected concurrent summaries");
        assertTrue(llm.maxInFlight.get() <= 3, "at most 3 summaries in flight");
        assertEquals(List.of("Group T1a", "Group T2a", "Group T3a", "Group T4a", "Group T5a", "Group T6a"),
                result.communities().stream().map(Community::title).toList());
    }

    @Test
    void aDeterministicPortIsNeverSummarizedInParallel() {
        CountingLlm llm = new CountingLlm(false, null, 20);

        new DetectCommunities(triangles(4), llm, DetectCommunities.Options.defaults().withParallelism(4)).run(CORPUS);

        assertEquals(1, llm.maxInFlight.get());
    }

    @Test
    @Timeout(20)
    void theWallTimeBoundsAParallelRun() {
        CountingLlm llm = new CountingLlm(true, null, 3_000);
        long started = System.nanoTime();

        CommunityDetectionResult result = new DetectCommunities(triangles(4), llm,
                DetectCommunities.Options.defaults().withParallelism(2).withMaxWallTime(Duration.ofMillis(300)))
                .run(CORPUS);

        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2_500);
        assertEquals(4, result.count(SummaryStatus.SKIPPED_BUDGET));
        assertEquals(CommunityDetectionResult.Status.PARTIAL, result.status());
    }

    @Test
    void reusesStoredSummariesByContentHashAndRegeneratesChangedCommunities() {
        TestGraphStore store = triangles(2);
        DetectCommunities.Options reuse = DetectCommunities.Options.defaults().withReuseSummaries(true);
        new DetectCommunities(store, new CountingLlm(false, null, 0), reuse).run(CORPUS);
        Community first = store.communities(CORPUS).iterator().next();
        assertEquals(Set.of(DetectCommunities.CONTENT_HASH_ATTRIBUTE, DetectCommunities.SUMMARY_STATUS_ATTRIBUTE),
                first.attributes().keySet());

        store.persistEntities(CORPUS, List.of(new Entity("T2b", "Class", "Now does something else.", List.of())));
        CountingLlm second = new CountingLlm(false, null, 0);
        CommunityDetectionResult rerun = new DetectCommunities(store, second, reuse).run(CORPUS);

        assertEquals(List.of(SummaryStatus.REUSED, SummaryStatus.GENERATED), rerun.summaries().stream()
                .map(CommunityDetectionResult.SummaryOutcome::status).toList());
        assertEquals(List.of("T2a"), second.summarizedFirstMembers);
        assertEquals("Group T1a", rerun.communities().getFirst().title());
        assertEquals("REUSED", rerun.communities().getFirst().attributes().get(
                DetectCommunities.SUMMARY_STATUS_ATTRIBUTE));
    }

    @Test
    void aFallbackSummaryIsNeverReusedSoTheNextRunRetriesIt() {
        TestGraphStore store = triangles(1);
        DetectCommunities.Options reuse = DetectCommunities.Options.defaults().withReuseSummaries(true)
                .withFailurePolicy(FailurePolicy.ISOLATE_ITEM);
        new DetectCommunities(store, new CountingLlm(false, "T1", 0), reuse).run(CORPUS);

        CountingLlm retry = new CountingLlm(false, null, 0);
        CommunityDetectionResult rerun = new DetectCommunities(store, retry, reuse).run(CORPUS);

        assertEquals(1, retry.calls.get());
        assertEquals(SummaryStatus.GENERATED, rerun.summaries().getFirst().status());
    }

    @Test
    void contentHashIgnoresStorageOrder() {
        List<Entity> members = List.of(new Entity("A", "Class", "a", List.of()), new Entity("B", "Class", "b", List.of()));
        List<Relationship> internal = List.of(new Relationship("A", "Class", "CALLS", "B", "Class"),
                new Relationship("B", "Class", "CALLS", "A", "Class"));

        assertEquals(DetectCommunities.contentHash(members, internal),
                DetectCommunities.contentHash(members.reversed(), internal.reversed()));
        assertFalse(DetectCommunities.contentHash(members, internal)
                .equals(DetectCommunities.contentHash(members, internal.subList(0, 1))));
    }

    @Test
    void reportsProgressOncePerCommunityInOrder() {
        List<String> events = new ArrayList<>();

        new DetectCommunities(triangles(3), null, DetectCommunities.Options.defaults()).run(CORPUS,
                (done, total, communityId, status) -> events.add(done + "/" + total + " " + communityId + " " + status),
                null);

        assertEquals(List.of("1/3 community-1 DETERMINISTIC", "2/3 community-2 DETERMINISTIC",
                "3/3 community-3 DETERMINISTIC"), events);
    }

    @Test
    void anEmptyGraphOrTooSmallGroupsAreAnEmptyResult() {
        TestGraphStore pair = new TestGraphStore();
        pair.persistEntities(CORPUS, List.of(new Entity("A", "Class"), new Entity("B", "Class")));
        pair.persistRelationships(CORPUS, List.of(new Relationship("A", "Class", "CALLS", "B", "Class")));

        assertEquals(CommunityDetectionResult.Status.EMPTY, new DetectCommunities(new TestGraphStore()).run(CORPUS).status());
        CommunityDetectionResult tooSmall = new DetectCommunities(pair).run(CORPUS);
        assertEquals(CommunityDetectionResult.Status.EMPTY, tooSmall.status());
        assertTrue(pair.communities(CORPUS).isEmpty());
    }

    @Test
    void validatesItsOptions() {
        DetectCommunities.Options defaults = DetectCommunities.Options.defaults();
        assertThrows(IllegalArgumentException.class, () -> defaults.withMinCommunitySize(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withParallelism(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxSummaries(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxWallTime(Duration.ofSeconds(-1)));
        assertSame(FailurePolicy.FAIL_RUN, defaults.withFailurePolicy(null).failurePolicy());
    }
}
