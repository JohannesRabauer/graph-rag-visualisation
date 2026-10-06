package dev.rabauer.graphrag.core.community;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Groups nodes by connected components, ignoring weights: the grouping
 * {@code GraphStorePort.detectCommunities} used before the modularity-based
 * default. Traversal runs through every edge endpoint, also ones outside
 * {@code nodeIds}, but only {@code nodeIds} are reported. Groups and members
 * follow the {@link CommunityDetector} ordering contract.
 */
public final class ConnectedComponentsCommunityDetector implements CommunityDetector {

    @Override
    public List<List<String>> detect(List<String> nodeIds, List<WeightedEdge> edges) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        Set<String> nodes = new LinkedHashSet<>();
        for (String id : nodeIds) {
            if (id != null && !id.isBlank()) {
                nodes.add(id);
            }
        }
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (String node : nodes) {
            adjacency.put(node, new LinkedHashSet<>());
        }
        if (edges != null) {
            for (WeightedEdge edge : edges) {
                if (edge == null || edge.source().isBlank() || edge.target().isBlank()) {
                    continue;
                }
                adjacency.computeIfAbsent(edge.source(), ignored -> new LinkedHashSet<>()).add(edge.target());
                adjacency.computeIfAbsent(edge.target(), ignored -> new LinkedHashSet<>()).add(edge.source());
            }
        }

        Set<String> visited = new HashSet<>();
        List<List<String>> groups = new ArrayList<>();
        for (String start : nodes) {
            if (!visited.add(start)) {
                continue;
            }
            Set<String> component = new HashSet<>();
            Queue<String> pending = new ArrayDeque<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                String current = pending.remove();
                component.add(current);
                for (String neighbour : adjacency.getOrDefault(current, Set.of())) {
                    if (visited.add(neighbour)) {
                        pending.add(neighbour);
                    }
                }
            }
            List<String> members = new ArrayList<>();
            for (String node : nodes) {
                if (component.contains(node)) {
                    members.add(node);
                }
            }
            groups.add(List.copyOf(members));
        }
        return List.copyOf(groups);
    }
}
