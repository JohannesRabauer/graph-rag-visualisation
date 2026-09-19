package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.CommunityMembership;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.port.GraphStorePort;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

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
    void stillReturnsAnOrdinaryAnswerWhenCommunitiesExistButNoneMatchTheQuestion() {
        StubGraphStore graphStore = new StubGraphStore(List.of(
                new Community("community-1", "This community centers on Baker Street and stray cats.")));

        GlobalSearchAnswer result = new AnswerGlobalSearch(graphStore)
                .answer("Completely unrelated gibberish zzzqqxx?");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        assertFalse(result.answer().isBlank());
    }

    private static final class StubGraphStore implements GraphStorePort {
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
}
