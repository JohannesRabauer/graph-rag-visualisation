package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.CommunityMembership;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.port.GraphStorePort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectCommunitiesTest {

    @Test
    void detectsConnectedEntityClustersAndPersistsThemAsFirstClassCommunities() {
        RecordingGraphStore graphStore = new RecordingGraphStore(
                List.of(
                        new Entity("Sherlock Holmes", "Person"),
                        new Entity("Dr. Watson", "Person"),
                        new Entity("Baker Street", "Location"),
                        new Entity("Irene Adler", "Person")),
                List.of(
                        new Relationship("Sherlock Holmes", "Person", "knows", "Dr. Watson", "Person"),
                        new Relationship("Sherlock Holmes", "Person", "lives_at", "Baker Street", "Location"),
                        new Relationship("Irene Adler", "Person", "rivals", "Sherlock Holmes", "Person")));

        List<Community> communities = new DetectCommunities(graphStore)
                .detect(new Corpus("corpus-1", List.of(new UploadedDocument("demo.txt", "demo content"))));

        assertFalse(communities.isEmpty());
        assertNotNull(graphStore.persistedCommunities);
        assertFalse(graphStore.persistedCommunities.isEmpty());
        assertFalse(graphStore.persistedCommunities.stream()
                .allMatch(community -> community.summary() == null || community.summary().isBlank()));
        assertFalse(graphStore.persistedMemberships.isEmpty());
        assertFalse(graphStore.persistedMemberships.stream()
                .allMatch(member -> member.communityId() == null || member.communityId().isBlank()));
    }

    @Test
    void invokesTheOptionalCallbackOncePerCommunityWithCorrectMemberIdentitiesAfterPersisting() {
        RecordingGraphStore graphStore = new RecordingGraphStore(
                List.of(
                        new Entity("Sherlock Holmes", "Person"),
                        new Entity("Dr. Watson", "Person"),
                        new Entity("Irene Adler", "Person")),
                List.of(
                        new Relationship("Sherlock Holmes", "Person", "knows", "Dr. Watson", "Person")));

        Map<String, List<String>> callbackInvocations = new LinkedHashMap<>();
        Map<String, Integer> persistedCommunityCountAtCallbackTime = new LinkedHashMap<>();

        List<Community> communities = new DetectCommunities(graphStore).detect(
                new Corpus("corpus-1", List.of(new UploadedDocument("demo.txt", "demo content"))),
                (community, memberEntityIdentities) -> {
                    callbackInvocations.put(community.id(), memberEntityIdentities);
                    // The callback fires only after both persistCommunities() and
                    // persistCommunityMemberships() have already run for the full
                    // batch, so every detected Community is already durable by the
                    // time any single callback invocation could throw.
                    persistedCommunityCountAtCallbackTime.put(community.id(), graphStore.persistedCommunities.size());
                });

        assertEquals(communities.size(), callbackInvocations.size());
        for (Community community : communities) {
            assertTrue(callbackInvocations.containsKey(community.id()));
            assertEquals(communities.size(), persistedCommunityCountAtCallbackTime.get(community.id()));
        }

        List<String> holmesWatsonMembers = callbackInvocations.values().stream()
                .filter(members -> members.size() >= 2)
                .findFirst()
                .orElse(List.of());
        assertTrue(holmesWatsonMembers.contains("sherlock holmes::person"));
        assertTrue(holmesWatsonMembers.contains("dr. watson::person"));
    }

    private static final class RecordingGraphStore implements GraphStorePort {
        private final List<Entity> storedEntities;
        private final List<Relationship> storedRelationships;
        private final List<Community> persistedCommunities = new ArrayList<>();
        private final List<CommunityMembership> persistedMemberships = new ArrayList<>();

        private RecordingGraphStore(List<Entity> storedEntities, List<Relationship> storedRelationships) {
            this.storedEntities = storedEntities;
            this.storedRelationships = storedRelationships;
        }

        @Override
        public List<Entity> entities() {
            return storedEntities;
        }

        @Override
        public List<Relationship> relationships() {
            return storedRelationships;
        }

        @Override
        public void persistEntities(java.util.Collection<Entity> entities) {
            // no-op for this focused test
        }

        @Override
        public void persistRelationships(java.util.Collection<Relationship> relationships) {
            // no-op for this focused test
        }

        @Override
        public void persistCommunities(java.util.Collection<Community> communities) {
            persistedCommunities.addAll(communities);
        }

        @Override
        public void persistCommunityMemberships(java.util.Collection<CommunityMembership> memberships) {
            persistedMemberships.addAll(memberships);
        }
    }
}
