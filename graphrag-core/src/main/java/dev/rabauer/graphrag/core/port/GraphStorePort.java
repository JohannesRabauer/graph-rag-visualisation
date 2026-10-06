package dev.rabauer.graphrag.core.port;

import dev.rabauer.graphrag.core.community.GraphCommunities;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * @return the persisted Text Units of {@code corpusId}; empty by default
     */
    default Collection<TextUnit> textUnits(String corpusId) {
        return List.of();
    }

    default Optional<TextUnit> textUnit(String corpusId, String textUnitId) {
        if (corpusId == null || corpusId.isBlank() || textUnitId == null || textUnitId.isBlank()) {
            return Optional.empty();
        }
        return textUnits(corpusId).stream()
                .filter(textUnit -> textUnitId.equals(textUnit.id()))
                .findFirst();
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

    /**
     * Groups the Entities of {@code corpusId} into Communities and returns each
     * group as a list of member Entity identities (the
     * {@link Entity#normalizedIdentity()} / {@link Entity#identityOf(String, String)}
     * format). Every Entity of the corpus appears in exactly one group; an
     * Entity without relationships is its own single-member group.
     *
     * <p>The default implementation runs the core's deterministic,
     * modularity-based detector ({@link GraphCommunities#defaultDetector()}:
     * Louvain with a Leiden-style connectivity refinement, seed 42, resolution
     * 1.0, weighted by {@link Relationship#weight()}) over
     * {@link #entities(String)} and {@link #relationships(String)}, so any
     * store gets modularity-based Communities without a graph-algorithm
     * plugin. Groups and members are in the order of {@link #entities(String)}.
     * Graph stores with a native algorithm (for example GDS Leiden) may
     * override it; callers must not rely on the order an override returns. The
     * former connected-components grouping is available as
     * {@link dev.rabauer.graphrag.core.community.ConnectedComponentsCommunityDetector}.</p>
     *
     * @param corpusId the corpus whose Entities are grouped
     * @return the member-identity groups; empty when the corpus has no Entities
     */
    default List<List<String>> detectCommunities(String corpusId) {
        return GraphCommunities.detect(entities(corpusId), relationships(corpusId));
    }

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

    /**
     * @return at most {@code k} Entities of {@code corpusId}, most similar to
     *         {@code query} first; empty by default or when the corpus has no
     *         embedded Entities (callers then fall back to keyword matching)
     */
    default List<Entity> similarEntities(String corpusId, float[] query, int k) {
        return List.of();
    }

    /**
     * @return at most {@code k} Communities of {@code corpusId}, most similar
     *         to {@code query} first; empty by default or when the corpus has
     *         no embedded Communities (callers then fall back to keyword
     *         matching)
     */
    default List<Community> similarCommunities(String corpusId, float[] query, int k) {
        return List.of();
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
