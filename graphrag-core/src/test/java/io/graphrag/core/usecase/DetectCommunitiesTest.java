package io.graphrag.core.usecase;

import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.CommunityMembership;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.UploadedDocument;
import io.graphrag.core.port.GraphStorePort;
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
        assertEquals("corpus-1", graphStore.lastReadCorpusId);
        assertEquals("corpus-1", graphStore.lastPersistedCommunitiesCorpusId);
        assertEquals("corpus-1", graphStore.lastPersistedMembershipsCorpusId);
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

    @Test
    void honoursAPortOverrideAndOrdersItsGroupsAndMembersByEntityOrder() {
        List<Entity> entities = List.of(
                new Entity("A", "Node"), new Entity("B", "Node"), new Entity("C", "Node"),
                new Entity("D", "Node"), new Entity("E", "Node"));
        RecordingGraphStore graphStore = new RecordingGraphStore(entities, List.of(
                new Relationship("A", "Node", "links", "B", "Node"),
                new Relationship("B", "Node", "links", "C", "Node"),
                new Relationship("C", "Node", "links", "D", "Node"))) {
            @Override
            public List<List<String>> detectCommunities(String corpusId) {
                // Arbitrary order, as e.g. Leiden returns it; E is left out on purpose.
                return List.of(
                        List.of(id("D"), id("B")),
                        List.of(id("C"), id("A")));
            }
        };
        Map<String, List<String>> callbackInvocations = new LinkedHashMap<>();

        List<Community> communities = new DetectCommunities(graphStore).detect(
                new Corpus("corpus-1", List.of(new UploadedDocument("demo.txt", "demo content"))),
                (community, members) -> callbackInvocations.put(community.id(), members));

        assertEquals(List.of("community-1", "community-2", "community-3"),
                communities.stream().map(Community::id).toList());
        assertEquals(Map.of(
                "community-1", List.of(id("A"), id("C")),
                "community-2", List.of(id("B"), id("D")),
                "community-3", List.of(id("E"))), callbackInvocations);
        assertEquals(List.of(
                new CommunityMembership("community-1", id("A")),
                new CommunityMembership("community-1", id("C")),
                new CommunityMembership("community-2", id("B")),
                new CommunityMembership("community-2", id("D")),
                new CommunityMembership("community-3", id("E"))), graphStore.persistedMemberships);
    }

    @Test
    void defaultPortGroupsTwoBridgedCliquesIntoOneCommunityAndKeepsAnIsolatedEntityAlone() {
        List<Entity> entities = new ArrayList<>();
        for (String name : List.of("A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4", "Loner")) {
            entities.add(new Entity(name, "Node"));
        }
        List<Relationship> relationships = new ArrayList<>();
        relationships.addAll(clique("A1", "A2", "A3", "A4"));
        relationships.addAll(clique("B1", "B2", "B3", "B4"));
        relationships.add(new Relationship("A4", "Node", "bridges", "B1", "Node"));
        RecordingGraphStore graphStore = new RecordingGraphStore(entities, relationships);
        Map<String, List<String>> callbackInvocations = new LinkedHashMap<>();

        List<Community> communities = new DetectCommunities(graphStore).detect(
                new Corpus("corpus-1", List.of(new UploadedDocument("demo.txt", "demo content"))),
                (community, members) -> callbackInvocations.put(community.id(), members));

        assertEquals(2, communities.size());
        assertEquals(List.of(id("A1"), id("A2"), id("A3"), id("A4"), id("B1"), id("B2"), id("B3"), id("B4")),
                callbackInvocations.get("community-1"));
        assertEquals(List.of(id("Loner")), callbackInvocations.get("community-2"));
    }

    private static List<Relationship> clique(String... names) {
        List<Relationship> relationships = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            for (int j = i + 1; j < names.length; j++) {
                relationships.add(new Relationship(names[i], "Node", "links", names[j], "Node"));
            }
        }
        return relationships;
    }

    private static String id(String name) {
        return Entity.identityOf(name, "Node");
    }

    private static class RecordingGraphStore implements GraphStorePort {
        private final List<Entity> storedEntities;
        private final List<Relationship> storedRelationships;
        private final List<Community> persistedCommunities = new ArrayList<>();
        private final List<CommunityMembership> persistedMemberships = new ArrayList<>();
        private String lastReadCorpusId;
        private String lastPersistedCommunitiesCorpusId;
        private String lastPersistedMembershipsCorpusId;

        private RecordingGraphStore(List<Entity> storedEntities, List<Relationship> storedRelationships) {
            this.storedEntities = storedEntities;
            this.storedRelationships = storedRelationships;
        }

        @Override
        public List<Entity> entities() {
            return storedEntities;
        }

        @Override
        public List<Entity> entities(String corpusId) {
            lastReadCorpusId = corpusId;
            return storedEntities;
        }

        @Override
        public List<Relationship> relationships() {
            return storedRelationships;
        }

        @Override
        public List<Relationship> relationships(String corpusId) {
            lastReadCorpusId = corpusId;
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
        public void persistCommunities(String corpusId, java.util.Collection<Community> communities) {
            lastPersistedCommunitiesCorpusId = corpusId;
            persistedCommunities.addAll(communities);
        }

        @Override
        public void persistCommunityMemberships(java.util.Collection<CommunityMembership> memberships) {
            persistedMemberships.addAll(memberships);
        }

        @Override
        public void persistCommunityMemberships(String corpusId, java.util.Collection<CommunityMembership> memberships) {
            lastPersistedMembershipsCorpusId = corpusId;
            persistedMemberships.addAll(memberships);
        }
    }
}
