package dev.rabauer.graphrag.adapter.neo4j;

import dev.rabauer.graphrag.core.community.ConnectedComponentsCommunityDetector;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryGraphStoreAdapterCommunitiesTest {

    private static void bridgedTriangles(InMemoryGraphStoreAdapter store) {
        List<Entity> entities = new ArrayList<>();
        for (String name : List.of("A1", "A2", "A3", "B1", "B2", "B3")) {
            entities.add(new Entity(name, "Class"));
        }
        store.persistEntities("c", entities);
        store.persistRelationships("c", List.of(
                new Relationship("A1", "Class", "CALLS", "A2", "Class"),
                new Relationship("A2", "Class", "CALLS", "A3", "Class"),
                new Relationship("A3", "Class", "CALLS", "A1", "Class"),
                new Relationship("B1", "Class", "CALLS", "B2", "Class"),
                new Relationship("B2", "Class", "CALLS", "B3", "Class"),
                new Relationship("B3", "Class", "CALLS", "B1", "Class"),
                new Relationship("A3", "Class", "CALLS", "B1", "Class")));
    }

    @Test
    void usesTheModularityBasedCoreDetectorByDefault() {
        InMemoryGraphStoreAdapter store = new InMemoryGraphStoreAdapter();
        bridgedTriangles(store);

        assertEquals(List.of(
                List.of("a1::class", "a2::class", "a3::class"),
                List.of("b1::class", "b2::class", "b3::class")), store.detectCommunities("c"));
    }

    @Test
    void acceptsAnotherDetector() {
        InMemoryGraphStoreAdapter store = new InMemoryGraphStoreAdapter(new ConnectedComponentsCommunityDetector());
        bridgedTriangles(store);

        assertEquals(1, store.detectCommunities("c").size());
    }
}
