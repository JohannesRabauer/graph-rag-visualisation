package com.graphraglens.adapter.neo4j;

import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.CommunityMembership;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryGraphStoreAdapterTest {

    @Test
    void storesEntitiesAndRelationshipsByIdentity() {
        InMemoryGraphStoreAdapter adapter = new InMemoryGraphStoreAdapter();

        adapter.persistEntities(java.util.List.of(new Entity("Sherlock Holmes", "Person"), new Entity("Dr. Watson", "Person")));
        adapter.persistRelationships(java.util.List.of(new Relationship("Sherlock Holmes", "Person", "met", "Dr. Watson", "Person")));

        assertEquals(2, adapter.entities().size());
        assertEquals(1, adapter.relationships().size());
    }

    @Test
    void readsScopedEntitiesAndRelationshipsByCorpusId() {
        InMemoryGraphStoreAdapter adapter = new InMemoryGraphStoreAdapter();

        adapter.persistEntities("corpus-a", List.of(new Entity("Sherlock Holmes", "Person")));
        adapter.persistEntities("corpus-b", List.of(new Entity("Professor Moriarty", "Person")));
        adapter.persistRelationships("corpus-a", List.of(
                new Relationship("Sherlock Holmes", "Person", "met", "Dr. Watson", "Person")));

        assertEquals(1, adapter.entities("corpus-a").size());
        assertEquals(1, adapter.entities("corpus-b").size());
        assertEquals(1, adapter.relationships("corpus-a").size());
        assertEquals(0, adapter.relationships("corpus-b").size());
    }

    @Test
    void roundTripsDescriptionsSourceIdsAndWeight() {
        InMemoryGraphStoreAdapter adapter = new InMemoryGraphStoreAdapter();
        Entity entity = new Entity("Ada Lovelace", "Person", "A mathematician.", List.of("u0", "u1"));
        Relationship relationship = new Relationship("Ada Lovelace", "Person", "wrote_about", "Engine", "Concept",
                "Ada wrote about the Engine.", List.of("u0", "u1"), 2);

        adapter.persistEntities("corpus-a", List.of(entity));
        adapter.persistRelationships("corpus-a", List.of(relationship));

        assertEquals(entity, adapter.entities("corpus-a").getFirst());
        assertEquals(relationship, adapter.relationships("corpus-a").getFirst());
    }

    @Test
    void storesTextUnitsPerCorpusById() {
        InMemoryGraphStoreAdapter adapter = new InMemoryGraphStoreAdapter();
        TextUnit unit = new TextUnit("corpus-a::doc-0::tu-0", "corpus-a", "a.txt", 0, "Text");

        adapter.persistTextUnits("corpus-a", List.of(unit));
        adapter.persistTextUnits("corpus-a", List.of(unit));

        assertEquals(List.of(unit), adapter.textUnits("corpus-a"));
        assertEquals(0, adapter.textUnits("corpus-b").size());
        assertEquals(unit, adapter.textUnit("corpus-a", unit.id()).orElseThrow());
        assertTrue(adapter.textUnit("corpus-a", "missing").isEmpty());
        assertTrue(adapter.textUnit("corpus-b", unit.id()).isEmpty());
    }

    @Test
    void retypeEntityMovesEntityKeyAndUpdatesRelationshipEndpointTypes() {
        InMemoryGraphStoreAdapter adapter = new InMemoryGraphStoreAdapter();
        adapter.persistEntities("corpus-a", List.of(new Entity("Jaguar", "Animal")));
        adapter.persistRelationships("corpus-a", List.of(
                new Relationship("Jaguar", "Animal", "appears_in", "Market", "Concept"),
                new Relationship("Market", "Concept", "features", "Jaguar", "Animal")));

        adapter.retypeEntity("corpus-a", Entity.identityOf("Jaguar", "Animal"),
                new Entity("Jaguar", "Organization", "A company.", List.of("u1")));

        assertEquals(List.of(new Entity("Jaguar", "Organization", "A company.", List.of("u1"))),
                adapter.entities("corpus-a"));
        assertEquals(2, adapter.relationships("corpus-a").size());
        assertEquals("Organization", adapter.relationships("corpus-a").stream()
                .filter(relationship -> relationship.type().equals("appears_in")).findFirst().orElseThrow().sourceType());
        assertEquals("Organization", adapter.relationships("corpus-a").stream()
                .filter(relationship -> relationship.type().equals("features")).findFirst().orElseThrow().targetType());
        assertEquals("jaguar::organization", adapter.entities().getFirst().normalizedIdentity());
    }

    @Test
    void readsScopedCommunitiesAndMembershipsByCorpusId() {
        InMemoryGraphStoreAdapter adapter = new InMemoryGraphStoreAdapter();

        adapter.persistCommunities("corpus-a", List.of(new Community("community-a", "A summary")));
        adapter.persistCommunities("corpus-b", List.of(new Community("community-b", "B summary")));
        adapter.persistCommunityMemberships("corpus-a",
                List.of(new CommunityMembership("community-a", "sherlock holmes::person")));

        assertEquals(1, adapter.communities("corpus-a").size());
        assertEquals(1, adapter.communities("corpus-b").size());
        assertEquals(1, adapter.communityMemberships("corpus-a").size());
        assertEquals(0, adapter.communityMemberships("corpus-b").size());
    }
}
