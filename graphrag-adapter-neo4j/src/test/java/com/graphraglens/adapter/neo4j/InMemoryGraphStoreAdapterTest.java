package com.graphraglens.adapter.neo4j;

import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.CommunityMembership;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        adapter.persistEntities("corpus-a", java.util.List.of(new Entity("Sherlock Holmes", "Person")));
        adapter.persistEntities("corpus-b", java.util.List.of(new Entity("Professor Moriarty", "Person")));
        adapter.persistRelationships("corpus-a", java.util.List.of(
                new Relationship("Sherlock Holmes", "Person", "met", "Dr. Watson", "Person")));

        assertEquals(1, adapter.entities("corpus-a").size());
        assertEquals(1, adapter.entities("corpus-b").size());
        assertEquals(1, adapter.relationships("corpus-a").size());
        assertEquals(0, adapter.relationships("corpus-b").size());
    }

    @Test
    void storesTextUnitsPerCorpusById() {
        InMemoryGraphStoreAdapter adapter = new InMemoryGraphStoreAdapter();
        io.graphrag.core.domain.TextUnit unit =
                new io.graphrag.core.domain.TextUnit("corpus-a::doc-0::tu-0", "corpus-a", "a.txt", 0, "Text");

        adapter.persistTextUnits("corpus-a", java.util.List.of(unit));
        adapter.persistTextUnits("corpus-a", java.util.List.of(unit));

        assertEquals(java.util.List.of(unit), adapter.textUnits("corpus-a"));
        assertEquals(0, adapter.textUnits("corpus-b").size());
    }

    @Test
    void readsScopedCommunitiesAndMembershipsByCorpusId() {
        InMemoryGraphStoreAdapter adapter = new InMemoryGraphStoreAdapter();

        adapter.persistCommunities("corpus-a", java.util.List.of(new Community("community-a", "A summary")));
        adapter.persistCommunities("corpus-b", java.util.List.of(new Community("community-b", "B summary")));
        adapter.persistCommunityMemberships("corpus-a",
                java.util.List.of(new CommunityMembership("community-a", "sherlock holmes::person")));

        assertEquals(1, adapter.communities("corpus-a").size());
        assertEquals(1, adapter.communities("corpus-b").size());
        assertEquals(1, adapter.communityMemberships("corpus-a").size());
        assertEquals(0, adapter.communityMemberships("corpus-b").size());
    }
}
