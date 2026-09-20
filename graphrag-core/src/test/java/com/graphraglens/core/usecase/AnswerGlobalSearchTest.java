package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.CommunityMembership;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.domain.RetrievalStep;
import com.graphraglens.core.port.GraphStorePort;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerGlobalSearchTest {

    @Test
    void reportsNoAnswerWhenNoCommunitiesArePersistedYet() {
        AnswerGlobalSearch useCase = new AnswerGlobalSearch(new StubGraphStore(List.of()));

        GlobalSearchAnswer result = useCase.answer("What is this corpus about?");

        assertTrue(result.noAnswer());
        assertNull(result.answer());
        assertNotNull(result.reason());
        assertFalse(result.reason().isBlank());
    }

    @Test
    void answersFromTheBestMatchingCommunitySummaryWhenOneScoresAMatch() {
        StubGraphStore graphStore = new StubGraphStore(List.of(
                new Community("community-1", "This community centers on Baker Street and stray cats."),
                new Community("community-2", "This community centers on Sherlock Holmes and Irene Adler.")));

        GlobalSearchAnswer result = new AnswerGlobalSearch(graphStore).answer("What did Irene Adler do to Holmes?");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        assertTrue(result.answer().contains("Irene Adler"));
        assertNull(result.reason());
    }

    @Test
    void breaksATieBetweenEquallyScoredCommunitiesByLexicographicallySmallestId() {
        // Both communities share the token "adler" and so score identically;
        // only the tie-break (smallest id) should decide the winner. Order of
        // both the list itself and the ids within it is deliberately mixed so
        // the test would fail if the outcome depended on iteration order.
        StubGraphStore graphStore = new StubGraphStore(List.of(
                new Community("community-z", "This community centers on Irene Adler and disguises."),
                new Community("community-a", "This community centers on Irene Adler and photographs.")));

        GlobalSearchAnswer result = new AnswerGlobalSearch(graphStore).answer("Tell me about Adler.");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        assertTrue(result.answer().contains("photographs"));
        assertFalse(result.answer().contains("disguises"));
    }

    @Test
    void reportsOneOrderedStepPerCommunityExaminedInIterationOrder() {
        StubGraphStore graphStore = new StubGraphStore(List.of(
                new Community("community-z", "This community centers on Baker Street and stray cats."),
                new Community("community-a", "This community centers on Irene Adler and photographs.")));

        GlobalSearchAnswer result = new AnswerGlobalSearch(graphStore).answer("What did Irene Adler do to Holmes?");

        assertEquals(2, result.steps().size());
        assertEquals(RetrievalStep.Kind.COMMUNITY, result.steps().get(0).kind());
        assertEquals("community-z", result.steps().get(0).identifier());
        assertEquals(RetrievalStep.Kind.COMMUNITY, result.steps().get(1).kind());
        assertEquals("community-a", result.steps().get(1).identifier());
    }

    @Test
    void reportsZeroStepsWhenNoCommunitiesArePersistedYet() {
        GlobalSearchAnswer result = new AnswerGlobalSearch(new StubGraphStore(List.of())).answer("Anything?");

        assertNotNull(result.steps());
        assertTrue(result.steps().isEmpty());
    }

    @Test
    void stillReturnsAnOrdinaryAnswerWhenCommunitiesExistButNoneMatchTheQuestion() {
        StubGraphStore graphStore = new StubGraphStore(List.of(
                new Community("community-1", "This community centers on Baker Street and stray cats.")));

        GlobalSearchAnswer result = new AnswerGlobalSearch(graphStore)
                .answer("Completely unrelated gibberish zzzqqxx?");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        assertFalse(result.answer().isBlank());
    }

    @Test
    void corpusScopedAnswerReadsOnlyTheRequestedCorpusCommunities() {
        ScopedStubGraphStore graphStore = new ScopedStubGraphStore(Map.of(
                "corpus-a", List.of(new Community("community-a", "This community centers on Irene Adler.")),
                "corpus-b", List.of(new Community("community-b", "This community centers on Moriarty."))));

        GlobalSearchAnswer result = new AnswerGlobalSearch(graphStore).answer("Tell me about Moriarty", "corpus-a");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        assertFalse(result.answer().contains("Moriarty"));
    }

    private static class StubGraphStore implements GraphStorePort {
        private final List<Community> storedCommunities;

        private StubGraphStore(List<Community> storedCommunities) {
            this.storedCommunities = storedCommunities;
        }

        @Override
        public Collection<Community> communities() {
            return storedCommunities;
        }

        @Override
        public void persistEntities(Collection<Entity> entities) {
            // not exercised by this use case
        }

        @Override
        public void persistRelationships(Collection<Relationship> relationships) {
            // not exercised by this use case
        }

        @Override
        public void persistCommunities(Collection<Community> communities) {
            // not exercised by this use case
        }

        @Override
        public void persistCommunityMemberships(Collection<CommunityMembership> memberships) {
            // not exercised by this use case
        }
    }

    private static final class ScopedStubGraphStore extends StubGraphStore {
        private final Map<String, List<Community>> communitiesByCorpus;

        private ScopedStubGraphStore(Map<String, List<Community>> communitiesByCorpus) {
            super(List.of());
            this.communitiesByCorpus = new LinkedHashMap<>(communitiesByCorpus);
        }

        @Override
        public Collection<Community> communities(String corpusId) {
            return communitiesByCorpus.getOrDefault(corpusId, List.of());
        }
    }
}
