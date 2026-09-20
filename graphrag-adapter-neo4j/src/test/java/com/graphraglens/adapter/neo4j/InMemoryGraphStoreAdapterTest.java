package com.graphraglens.adapter.neo4j;

import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;
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
}
