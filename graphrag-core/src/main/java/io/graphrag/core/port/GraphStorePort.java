package io.graphrag.core.port;

import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.CommunityMembership;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.TextUnit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

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
     * <p>The default implementation groups by connected components over
     * {@link #entities(String)} and {@link #relationships(String)}, with groups
     * and their members in the order of {@link #entities(String)}. Graph stores
     * with a native community-detection algorithm (for example GDS Leiden) may
     * override it; callers must not rely on the order an override returns.</p>
     *
     * @param corpusId the corpus whose Entities are grouped
     * @return the member-identity groups; empty when the corpus has no Entities
     */
    default List<List<String>> detectCommunities(String corpusId) {
        return connectedComponents(entities(corpusId), relationships(corpusId));
    }

    private static List<List<String>> connectedComponents(Collection<Entity> entities,
                                                          Collection<Relationship> relationships) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        Set<String> entityIdentities = new LinkedHashSet<>();
        for (Entity entity : entities) {
            if (entity != null) {
                entityIdentities.add(entity.normalizedIdentity());
            }
        }
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (String identity : entityIdentities) {
            adjacency.put(identity, new LinkedHashSet<>());
        }
        if (relationships != null) {
            for (Relationship relationship : relationships) {
                if (relationship == null) {
                    continue;
                }
                String source = Entity.identityOf(relationship.source(), relationship.sourceType());
                String target = Entity.identityOf(relationship.target(), relationship.targetType());
                adjacency.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
                adjacency.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
            }
        }

        Set<String> visited = new HashSet<>();
        List<List<String>> groups = new ArrayList<>();
        for (String start : entityIdentities) {
            if (!visited.add(start)) {
                continue;
            }
            // Traverse through every endpoint (also ones without an Entity, as before),
            // but report only identities that are actual Entities of the corpus.
            Set<String> component = new HashSet<>();
            Queue<String> pending = new ArrayDeque<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                String current = pending.remove();
                component.add(current);
                for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                    if (visited.add(neighbor)) {
                        pending.add(neighbor);
                    }
                }
            }
            List<String> members = new ArrayList<>();
            for (String identity : entityIdentities) {
                if (component.contains(identity)) {
                    members.add(identity);
                }
            }
            groups.add(List.copyOf(members));
        }
        return List.copyOf(groups);
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
