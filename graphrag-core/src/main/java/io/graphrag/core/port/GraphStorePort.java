package io.graphrag.core.port;

import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.CommunityMembership;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.TextUnit;

import java.util.Collection;
import java.util.List;

/**
 * Port for persisting and querying the knowledge graph.
 */
public interface GraphStorePort {

    /**
     * Persists entities into the graph store.
     *
     * @param entities the entities to persist into the graph store; never
     *                 null
     */
    void persistEntities(Collection<Entity> entities);

    /**
     * Persists relationships into the graph store.
     *
     * @param relationships the relationships to persist into the graph
     *                       store; never null
     */
    void persistRelationships(Collection<Relationship> relationships);

    default void persistEntities(String corpusId, Collection<Entity> entities) {
        persistEntities(entities);
    }

    default void persistRelationships(String corpusId, Collection<Relationship> relationships) {
        persistRelationships(relationships);
    }

    default void persistCommunities(Collection<Community> communities) {
        // Optional for graph-store implementations that support first-class community nodes.
    }

    default void persistCommunities(String corpusId, Collection<Community> communities) {
        persistCommunities(communities);
    }

    default void persistCommunityMemberships(Collection<CommunityMembership> memberships) {
        // Optional for graph-store implementations that support regular membership relationships.
    }

    default void persistCommunityMemberships(String corpusId, Collection<CommunityMembership> memberships) {
        persistCommunityMemberships(memberships);
    }

    /**
     * Persists the Text Units an extraction ran over, scoped by corpus
     * (AD-20). Optional: the default is a no-op.
     */
    default void persistTextUnits(String corpusId, Collection<TextUnit> textUnits) {
        // Optional for graph-store implementations that keep source passages.
    }

    /**
     * @return the persisted Text Units of {@code corpusId}; empty by default
     */
    default Collection<TextUnit> textUnits(String corpusId) {
        return List.of();
    }

    default Collection<Entity> entities() {
        return List.of();
    }

    default Collection<Entity> entities(String corpusId) {
        return entities();
    }

    default Collection<Relationship> relationships() {
        return List.of();
    }

    default Collection<Relationship> relationships(String corpusId) {
        return relationships();
    }

    default Collection<Community> communities() {
        return List.of();
    }

    default Collection<Community> communities(String corpusId) {
        return communities();
    }

    default Collection<CommunityMembership> communityMemberships() {
        return List.of();
    }

    default Collection<CommunityMembership> communityMemberships(String corpusId) {
        return communityMemberships();
    }

    default void persist(GraphExtraction extraction) {
        if (extraction == null) {
            return;
        }
        persistEntities(extraction.entities());
        persistRelationships(extraction.relationships());
    }

    default void persist(String corpusId, GraphExtraction extraction) {
        if (extraction == null) {
            return;
        }
        persistEntities(corpusId, extraction.entities());
        persistRelationships(corpusId, extraction.relationships());
    }

    default void persistEntitiesAndRelationships(GraphExtraction extraction) {
        persist(extraction);
    }

    default void persistEntitiesAndRelationships(String corpusId, GraphExtraction extraction) {
        persist(corpusId, extraction);
    }
}
