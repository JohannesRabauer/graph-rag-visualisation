package dev.rabauer.graphrag.core.community;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runs a {@link CommunityDetector} over a knowledge graph: the nodes are the
 * Entities' {@link Entity#normalizedIdentity() identities} in the given
 * order, the edges the Relationships between their endpoint identities,
 * weighted by {@link Relationship#weight()}. Relationship endpoints without
 * an Entity take part in the optimisation but are never reported.
 */
public final class GraphCommunities {

    private static final CommunityDetector DEFAULT_DETECTOR = new ModularityCommunityDetector();

    private GraphCommunities() {
    }

    /**
     * The detector {@code GraphStorePort.detectCommunities} uses by default:
     * a {@link ModularityCommunityDetector} with
     * {@link ModularityCommunityDetector.Options#defaults()} (seed 42,
     * resolution 1.0, connectivity refinement on).
     */
    public static CommunityDetector defaultDetector() {
        return DEFAULT_DETECTOR;
    }

    /** {@link #detect(CommunityDetector, Collection, Collection)} with the {@link #defaultDetector()}. */
    public static List<List<String>> detect(Collection<Entity> entities, Collection<Relationship> relationships) {
        return detect(DEFAULT_DETECTOR, entities, relationships);
    }

    /**
     * Groups the Entities' identities with {@code detector}.
     *
     * @return every Entity identity in exactly one group, ordered per the
     *         {@link CommunityDetector} contract; empty without Entities
     */
    public static List<List<String>> detect(CommunityDetector detector, Collection<Entity> entities,
                                            Collection<Relationship> relationships) {
        Objects.requireNonNull(detector, "detector");
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        Set<String> nodeIds = new LinkedHashSet<>();
        for (Entity entity : entities) {
            if (entity != null) {
                nodeIds.add(entity.normalizedIdentity());
            }
        }
        List<WeightedEdge> edges = new ArrayList<>();
        if (relationships != null) {
            for (Relationship relationship : relationships) {
                if (relationship != null) {
                    edges.add(new WeightedEdge(relationship.sourceIdentity(), relationship.targetIdentity(),
                            relationship.weight()));
                }
            }
        }
        return detector.detect(List.copyOf(nodeIds), edges);
    }
}
