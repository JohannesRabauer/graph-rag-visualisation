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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    void detectWithCallbackInvokesItOncePerCommunityWithMemberIdentities() {
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

        List<Community> notifiedCommunities = new ArrayList<>();
        List<List<String>> notifiedMemberIdentities = new ArrayList<>();
        List<Community> communities = new DetectCommunities(graphStore).detect(
                new Corpus("corpus-1", List.of(new UploadedDocument("demo.txt", "demo content"))),
                (community, memberIdentities) -> {
                    notifiedCommunities.add(community);
                    notifiedMemberIdentities.add(memberIdentities);
                });

        assertFalse(communities.isEmpty());
        assertEquals(communities.size(), notifiedCommunities.size());
        assertEquals(communities.stream().map(Community::id).toList(),
                notifiedCommunities.stream().map(Community::id).toList());
        assertFalse(notifiedMemberIdentities.stream().anyMatch(List::isEmpty));
        assertEquals(graphStore.persistedMemberships.stream().map(CommunityMembership::entityIdentity).sorted().toList(),
                notifiedMemberIdentities.stream().flatMap(List::stream).sorted().toList());
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
