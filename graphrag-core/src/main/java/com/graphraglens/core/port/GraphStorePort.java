package com.graphraglens.core.port;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.CommunityMembership;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.GraphExtraction;
import com.graphraglens.core.domain.Relationship;

import java.util.Collection;
import java.util.List;

/**
 * Port for persisting and querying the knowledge graph.
 */
public interface GraphStorePort {

    void persistEntities(Collection<Entity> entities);

    void persistRelationships(Collection<Relationship> relationships);

    default void persistCommunities(Collection<Community> communities) {
        // Optional for graph-store implementations that support first-class community nodes.
    }

    default void persistCommunityMemberships(Collection<CommunityMembership> memberships) {
        // Optional for graph-store implementations that support regular membership relationships.
    }

    default Collection<Entity> entities() {
        return List.of();
    }

    default Collection<Relationship> relationships() {
        return List.of();
    }

    default void persist(GraphExtraction extraction) {
        if (extraction == null) {
            return;
        }
        persistEntities(extraction.entities());
        persistRelationships(extraction.relationships());
    }

    default void persistEntitiesAndRelationships(GraphExtraction extraction) {
        persist(extraction);
    }
}
