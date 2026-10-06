package dev.rabauer.graphrag.core.port;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The write side of the knowledge graph: persisting, detecting Communities
 * and deleting. Used by ingestion, import, community detection and
 * incremental updates ({@code UpdateSources}); the query use cases never
 * need it.
 *
 * <p>The delete methods are optional: their defaults throw
 * {@link UnsupportedOperationException}, so a store without them fails
 * loudly instead of silently keeping stale data. Deleting is idempotent:
 * unknown ids are ignored.
 */
public interface GraphWritePort {

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

    default void retypeEntity(String corpusId, String previousIdentity, Entity resolved) {
        // Optional for graph-store implementations that can rename/re-key nodes.
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
     * Groups the Entities of {@code corpusId} into Communities; see
     * {@link GraphStorePort#detectCommunities(String)}, which provides the
     * default.
     */
    List<List<String>> detectCommunities(String corpusId);

    /**
     * Stores an embedding vector on each Entity of {@code corpusId}, keyed by
     * {@link Entity#normalizedIdentity()}. Optional: the default is a no-op.
     */
    default void persistEntityEmbeddings(String corpusId, Map<String, float[]> byIdentity) {
        // Optional for graph-store implementations that support vector similarity.
    }

    /**
     * Stores an embedding vector on each Community of {@code corpusId}, keyed
     * by {@link Community#id()}. Optional: the default is a no-op.
     */
    default void persistCommunityEmbeddings(String corpusId, Map<String, float[]> byCommunityId) {
        // Optional for graph-store implementations that support vector similarity.
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

    /**
     * Deletes the Text Units of {@code corpusId} with these ids.
     *
     * @throws UnsupportedOperationException by default
     */
    default void deleteTextUnits(String corpusId, Collection<String> textUnitIds) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot delete Text Units");
    }

    /**
     * Deletes the Entities of {@code corpusId} with these identities, together
     * with their Relationships, Community memberships and embeddings.
     *
     * @throws UnsupportedOperationException by default
     */
    default void deleteEntities(String corpusId, Collection<String> identities) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot delete Entities");
    }

    /**
     * Deletes the Relationships of {@code corpusId} with the same
     * {@code (source identity, type, target identity)} as the given ones.
     *
     * @throws UnsupportedOperationException by default
     */
    default void deleteRelationships(String corpusId, Collection<Relationship> relationships) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot delete Relationships");
    }

    /**
     * Deletes every Community of {@code corpusId}, its memberships and
     * embeddings (used before re-detecting).
     *
     * @throws UnsupportedOperationException by default
     */
    default void deleteCommunities(String corpusId) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot delete Communities");
    }
}
