package dev.rabauer.graphrag.core.community;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        return detector.detect(nodeIds(entities), edges(relationships));
    }

    /**
     * The levels of the hierarchy {@code detector} finds over the Entities'
     * identities, finest first ({@link CommunityDetector#detectHierarchy});
     * empty without Entities.
     */
    public static List<List<List<String>>> detectHierarchy(CommunityDetector detector, Collection<Entity> entities,
                                                           Collection<Relationship> relationships) {
        Objects.requireNonNull(detector, "detector");
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return detector.detectHierarchy(nodeIds(entities), edges(relationships));
    }

    /**
     * One flat partition out of a hierarchy: the groups of level {@code level}
     * (0 is the finest; negative or beyond the last level means the coarsest),
     * then, when {@code maxSize} is at least 1, every group larger than
     * {@code maxSize} replaced by the parts the next finer level cuts it into,
     * and so on down. Groups still larger at level 0 stay whole, and groups
     * that fit are never split.
     *
     * @param levels  the hierarchy, finest first (see {@link CommunityDetector#detectHierarchy})
     * @param level   the starting level; negative = the coarsest
     * @param maxSize the most identities per group; 0 = no limit
     * @return the groups, never null; empty for an empty hierarchy
     */
    public static List<List<String>> select(List<List<List<String>>> levels, int level, int maxSize) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        int start = level < 0 ? levels.size() - 1 : Math.min(level, levels.size() - 1);
        if (maxSize < 1) {
            return levels.get(start);
        }
        List<Map<String, Integer>> groupOf = new ArrayList<>();
        for (List<List<String>> groups : levels) {
            Map<String, Integer> byIdentity = new HashMap<>();
            for (int i = 0; i < groups.size(); i++) {
                for (String identity : groups.get(i)) {
                    byIdentity.putIfAbsent(identity, i);
                }
            }
            groupOf.add(byIdentity);
        }
        List<List<String>> selected = new ArrayList<>();
        for (List<String> group : levels.get(start)) {
            split(groupOf, group, start, maxSize, selected);
        }
        return List.copyOf(selected);
    }

    private static void split(List<Map<String, Integer>> groupOf, List<String> group, int level, int maxSize,
                              List<List<String>> out) {
        if (group.size() <= maxSize || level == 0) {
            out.add(group);
            return;
        }
        Map<String, Integer> finer = groupOf.get(level - 1);
        Map<Integer, List<String>> parts = new LinkedHashMap<>();
        for (String identity : group) {
            parts.computeIfAbsent(finer.getOrDefault(identity, -1), ignored -> new ArrayList<>()).add(identity);
        }
        for (List<String> part : parts.values()) {
            split(groupOf, part, level - 1, maxSize, out);
        }
    }

    private static List<String> nodeIds(Collection<Entity> entities) {
        Set<String> nodeIds = new LinkedHashSet<>();
        for (Entity entity : entities) {
            if (entity != null) {
                nodeIds.add(entity.normalizedIdentity());
            }
        }
        return List.copyOf(nodeIds);
    }

    private static List<WeightedEdge> edges(Collection<Relationship> relationships) {
        List<WeightedEdge> edges = new ArrayList<>();
        if (relationships != null) {
            for (Relationship relationship : relationships) {
                if (relationship != null) {
                    edges.add(new WeightedEdge(relationship.sourceIdentity(), relationship.targetIdentity(),
                            relationship.weight()));
                }
            }
        }
        return edges;
    }
}
