package dev.rabauer.graphrag.core.community;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Deterministic, seedable modularity-based community detector: Louvain local
 * moving and aggregation, with an optional Leiden-style connectivity refinement
 * that splits every community into its connected components before it is
 * reported or aggregated — so every reported community is connected.
 *
 * <p>The graph is undirected: {@code a -> b} and {@code b -> a} are the same
 * edge and parallel edges sum their weights. Edges whose weight is not a
 * positive finite number, and edges with a {@code null} or blank endpoint, are
 * ignored. Self-loops add twice their weight to a node's strength but never
 * cause a move. The visit order of every local-moving pass is a Fisher–Yates
 * permutation drawn from {@code new Random(seed + level)}, so different seeds
 * may yield different (equally valid) partitions while each seed is fully
 * reproducible.
 */
public final class ModularityCommunityDetector implements CommunityDetector {

    private static final double EPSILON = 1.0e-12;

    private final Options options;

    /**
     * Tuning knobs of the detector.
     *
     * @param seed                  seed of the visit-order permutation (level {@code l} uses {@code seed + l})
     * @param resolution            modularity resolution {@code gamma}; higher values yield more, smaller communities
     * @param maxLevels             maximum number of aggregation levels
     * @param maxIterationsPerLevel maximum number of local-moving passes per level
     * @param refineConnectivity    whether to split communities into connected components (Leiden guarantee)
     */
    public record Options(long seed, double resolution, int maxLevels, int maxIterationsPerLevel,
                          boolean refineConnectivity) {

        /** Validates the options; throws {@link IllegalArgumentException} when out of range. */
        public Options {
            if (!(resolution > 0) || !Double.isFinite(resolution)) {
                throw new IllegalArgumentException("resolution must be positive and finite, was " + resolution);
            }
            if (maxLevels < 1) {
                throw new IllegalArgumentException("maxLevels must be >= 1, was " + maxLevels);
            }
            if (maxIterationsPerLevel < 1) {
                throw new IllegalArgumentException(
                        "maxIterationsPerLevel must be >= 1, was " + maxIterationsPerLevel);
            }
        }

        /** Seed 42, resolution 1.0, 10 levels, 50 passes per level, connectivity refinement on. */
        public static Options defaults() {
            return new Options(42L, 1.0, 10, 50, true);
        }

        /** A copy with the given seed. */
        public Options withSeed(long seed) {
            return new Options(seed, resolution, maxLevels, maxIterationsPerLevel, refineConnectivity);
        }

        /** A copy with the given resolution. */
        public Options withResolution(double resolution) {
            return new Options(seed, resolution, maxLevels, maxIterationsPerLevel, refineConnectivity);
        }

        /** A copy with the given maximum number of levels. */
        public Options withMaxLevels(int maxLevels) {
            return new Options(seed, resolution, maxLevels, maxIterationsPerLevel, refineConnectivity);
        }

        /** A copy with the given maximum number of local-moving passes per level. */
        public Options withMaxIterationsPerLevel(int maxIterationsPerLevel) {
            return new Options(seed, resolution, maxLevels, maxIterationsPerLevel, refineConnectivity);
        }

        /** A copy with connectivity refinement switched on or off. */
        public Options withRefineConnectivity(boolean refineConnectivity) {
            return new Options(seed, resolution, maxLevels, maxIterationsPerLevel, refineConnectivity);
        }
    }

    /** A detector with {@link Options#defaults()}. */
    public ModularityCommunityDetector() {
        this(Options.defaults());
    }

    /** A detector with the given options. */
    public ModularityCommunityDetector(Options options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /** The options this detector runs with. */
    public Options options() {
        return options;
    }

    /** The coarsest level of {@link #detectHierarchy} (a single-level hierarchy when nothing merges). */
    @Override
    public List<List<String>> detect(List<String> nodeIds, List<WeightedEdge> edges) {
        List<List<List<String>>> levels = detectHierarchy(nodeIds, edges);
        return levels.isEmpty() ? List.of() : levels.getLast();
    }

    /**
     * Level 0 is the partition after the first local-moving (+ refinement) pass,
     * the last level is the coarsest. Never empty when {@code nodeIds} has at least
     * one id (one level with singletons when there are no usable edges). Each level
     * obeys the {@link CommunityDetector#detect} contract.
     */
    public List<List<List<String>>> detectHierarchy(List<String> nodeIds, List<WeightedEdge> edges) {
        Input input = Input.of(nodeIds, edges);
        if (input.reported == 0) {
            return List.of();
        }
        Graph current = Graph.build(input.ids.size(), input.us, input.vs, input.ws, input.edgeCount);
        int[] membership = identity(input.ids.size());
        List<List<List<String>>> levels = new ArrayList<>();
        for (int level = 0; level < options.maxLevels(); level++) {
            int[] comm = localMoving(current, level);
            comm = options.refineConnectivity() ? splitIntoComponents(current, comm) : renumber(comm);
            int count = 0;
            for (int c : comm) {
                count = Math.max(count, c + 1);
            }
            boolean merged = count < current.n;
            if (level > 0 && !merged) {
                break;
            }
            for (int o = 0; o < membership.length; o++) {
                membership[o] = comm[membership[o]];
            }
            levels.add(groups(membership, input));
            if (!merged) {
                break;
            }
            current = current.aggregate(comm, count);
        }
        return List.copyOf(levels);
    }

    /**
     * Newman–Girvan modularity with resolution {@code gamma} of a partition.
     * Edges follow the same rules as in {@link #detect}; edges with an endpoint
     * outside {@code groups} are ignored. Returns 0 for a graph without edge weight.
     *
     * @param groups     the partition
     * @param edges      the undirected, weighted edges
     * @param resolution the resolution {@code gamma}
     * @return the modularity
     */
    public static double modularity(List<List<String>> groups, List<WeightedEdge> edges, double resolution) {
        Map<String, Integer> groupOf = new HashMap<>();
        int groupCount = groups == null ? 0 : groups.size();
        for (int g = 0; g < groupCount; g++) {
            List<String> group = groups.get(g);
            if (group == null) {
                continue;
            }
            for (String id : group) {
                if (isUsableId(id)) {
                    groupOf.putIfAbsent(id, g);
                }
            }
        }
        double[] intra = new double[groupCount];
        double[] degree = new double[groupCount];
        double m = 0;
        if (edges != null) {
            for (WeightedEdge edge : edges) {
                if (!isUsable(edge)) {
                    continue;
                }
                Integer gu = groupOf.get(edge.source());
                Integer gv = groupOf.get(edge.target());
                if (gu == null || gv == null) {
                    continue;
                }
                double w = edge.weight();
                m += w;
                degree[gu] += w;
                degree[gv] += w;
                if (gu.intValue() == gv.intValue()) {
                    intra[gu] += w;
                }
            }
        }
        if (m <= 0) {
            return 0.0;
        }
        double q = 0;
        for (int g = 0; g < groupCount; g++) {
            double share = degree[g] / (2 * m);
            q += intra[g] / m - resolution * share * share;
        }
        return q;
    }

    private int[] localMoving(Graph g, int level) {
        int n = g.n;
        int[] comm = identity(n);
        if (g.m2 <= 0) {
            return comm;
        }
        double[] tot = g.strength.clone();
        int[] order = identity(n);
        Random random = new Random(options.seed() + level);
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        double gamma = options.resolution();
        double[] neighWeight = new double[n];
        boolean[] seen = new boolean[n];
        int[] touched = new int[n];
        for (int pass = 0; pass < options.maxIterationsPerLevel(); pass++) {
            boolean moved = false;
            for (int i : order) {
                int ci = comm[i];
                double ki = g.strength[i];
                int touchedCount = 0;
                for (int e = g.offsets[i]; e < g.offsets[i + 1]; e++) {
                    int c = comm[g.neighbours[e]];
                    if (!seen[c]) {
                        seen[c] = true;
                        touched[touchedCount++] = c;
                    }
                    neighWeight[c] += g.weights[e];
                }
                tot[ci] -= ki;
                double stayGain = neighWeight[ci] - gamma * ki * tot[ci] / g.m2;
                int best = -1;
                double bestGain = Double.NEGATIVE_INFINITY;
                for (int t = 0; t < touchedCount; t++) {
                    int c = touched[t];
                    if (c == ci) {
                        continue;
                    }
                    double gain = neighWeight[c] - gamma * ki * tot[c] / g.m2;
                    if (gain > bestGain + EPSILON || (Math.abs(gain - bestGain) <= EPSILON && c < best)) {
                        best = c;
                        bestGain = gain;
                    }
                }
                int target = best >= 0 && bestGain > stayGain + EPSILON ? best : ci;
                tot[target] += ki;
                if (target != ci) {
                    comm[i] = target;
                    moved = true;
                }
                for (int t = 0; t < touchedCount; t++) {
                    int c = touched[t];
                    seen[c] = false;
                    neighWeight[c] = 0;
                }
            }
            if (!moved) {
                break;
            }
        }
        return comm;
    }

    /** Splits every community into connected components; labels follow the lowest node index. */
    private static int[] splitIntoComponents(Graph g, int[] comm) {
        int n = g.n;
        int[] label = new int[n];
        Arrays.fill(label, -1);
        int[] stack = new int[n];
        int next = 0;
        for (int s = 0; s < n; s++) {
            if (label[s] != -1) {
                continue;
            }
            int c = comm[s];
            label[s] = next;
            int top = 0;
            stack[top++] = s;
            while (top > 0) {
                int i = stack[--top];
                for (int e = g.offsets[i]; e < g.offsets[i + 1]; e++) {
                    int j = g.neighbours[e];
                    if (label[j] == -1 && comm[j] == c) {
                        label[j] = next;
                        stack[top++] = j;
                    }
                }
            }
            next++;
        }
        return label;
    }

    /** Relabels communities 0..k-1 in order of their lowest node index. */
    private static int[] renumber(int[] comm) {
        int[] mapping = new int[comm.length];
        Arrays.fill(mapping, -1);
        int[] result = new int[comm.length];
        int next = 0;
        for (int i = 0; i < comm.length; i++) {
            if (mapping[comm[i]] == -1) {
                mapping[comm[i]] = next++;
            }
            result[i] = mapping[comm[i]];
        }
        return result;
    }

    private static List<List<String>> groups(int[] membership, Input input) {
        Map<Integer, List<String>> byLabel = new HashMap<>();
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i < input.reported; i++) {
            List<String> group = byLabel.get(membership[i]);
            if (group == null) {
                group = new ArrayList<>();
                byLabel.put(membership[i], group);
                result.add(group);
            }
            group.add(input.ids.get(i));
        }
        List<List<String>> frozen = new ArrayList<>(result.size());
        for (List<String> group : result) {
            frozen.add(List.copyOf(group));
        }
        return List.copyOf(frozen);
    }

    private static int[] identity(int n) {
        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            result[i] = i;
        }
        return result;
    }

    private static boolean isUsableId(String id) {
        return id != null && !id.isBlank();
    }

    private static boolean isUsable(WeightedEdge edge) {
        return edge != null && isUsableId(edge.source()) && isUsableId(edge.target())
                && edge.weight() > 0 && Double.isFinite(edge.weight());
    }

    /** Ids mapped to dense indices: the reported nodeIds first, then extra edge endpoints. */
    private static final class Input {
        final List<String> ids = new ArrayList<>();
        int reported;
        int[] us;
        int[] vs;
        double[] ws;
        int edgeCount;

        static Input of(List<String> nodeIds, List<WeightedEdge> edges) {
            Input input = new Input();
            Map<String, Integer> index = new HashMap<>();
            if (nodeIds != null) {
                for (String id : nodeIds) {
                    if (isUsableId(id) && !index.containsKey(id)) {
                        index.put(id, input.ids.size());
                        input.ids.add(id);
                    }
                }
            }
            input.reported = input.ids.size();
            int capacity = edges == null ? 0 : edges.size();
            input.us = new int[capacity];
            input.vs = new int[capacity];
            input.ws = new double[capacity];
            if (input.reported == 0 || edges == null) {
                return input;
            }
            for (WeightedEdge edge : edges) {
                if (!isUsable(edge)) {
                    continue;
                }
                int u = indexOf(index, input.ids, edge.source());
                int v = indexOf(index, input.ids, edge.target());
                input.us[input.edgeCount] = u;
                input.vs[input.edgeCount] = v;
                input.ws[input.edgeCount] = edge.weight();
                input.edgeCount++;
            }
            return input;
        }

        private static int indexOf(Map<String, Integer> index, List<String> ids, String id) {
            Integer existing = index.get(id);
            if (existing != null) {
                return existing;
            }
            int created = ids.size();
            index.put(id, created);
            ids.add(id);
            return created;
        }
    }

    /** Compressed (CSR) undirected graph with summed parallel edges and separate self-loop weights. */
    private static final class Graph {
        final int n;
        final int[] offsets;
        final int[] neighbours;
        final double[] weights;
        final double[] selfWeight;
        final double[] strength;
        final double m2;

        private Graph(int n, int[] offsets, int[] neighbours, double[] weights, double[] selfWeight) {
            this.n = n;
            this.offsets = offsets;
            this.neighbours = neighbours;
            this.weights = weights;
            this.selfWeight = selfWeight;
            this.strength = new double[n];
            double total = 0;
            for (int i = 0; i < n; i++) {
                double s = 2 * selfWeight[i];
                for (int e = offsets[i]; e < offsets[i + 1]; e++) {
                    s += weights[e];
                }
                strength[i] = s;
                total += s;
            }
            this.m2 = total;
        }

        static Graph build(int n, int[] us, int[] vs, double[] ws, int edgeCount) {
            double[] self = new double[n];
            int[] degree = new int[n + 1];
            for (int e = 0; e < edgeCount; e++) {
                if (us[e] == vs[e]) {
                    self[us[e]] += ws[e];
                } else {
                    degree[us[e]]++;
                    degree[vs[e]]++;
                }
            }
            int[] start = new int[n + 1];
            for (int i = 0; i < n; i++) {
                start[i + 1] = start[i] + degree[i];
            }
            int[] fill = Arrays.copyOf(start, n);
            int[] rawNeighbours = new int[start[n]];
            double[] rawWeights = new double[start[n]];
            for (int e = 0; e < edgeCount; e++) {
                int u = us[e];
                int v = vs[e];
                if (u == v) {
                    continue;
                }
                rawNeighbours[fill[u]] = v;
                rawWeights[fill[u]++] = ws[e];
                rawNeighbours[fill[v]] = u;
                rawWeights[fill[v]++] = ws[e];
            }
            int[] offsets = new int[n + 1];
            int[] position = new int[n];
            Arrays.fill(position, -1);
            int write = 0;
            for (int i = 0; i < n; i++) {
                offsets[i] = write;
                for (int e = start[i]; e < start[i + 1]; e++) {
                    int j = rawNeighbours[e];
                    if (position[j] == -1) {
                        position[j] = write;
                        rawNeighbours[write] = j;
                        rawWeights[write] = rawWeights[e];
                        write++;
                    } else {
                        rawWeights[position[j]] += rawWeights[e];
                    }
                }
                for (int e = offsets[i]; e < write; e++) {
                    position[rawNeighbours[e]] = -1;
                }
            }
            offsets[n] = write;
            return new Graph(n, offsets, Arrays.copyOf(rawNeighbours, write), Arrays.copyOf(rawWeights, write),
                    self);
        }

        /** Collapses every community into one super-node; intra weight becomes the super-node's self-loop. */
        Graph aggregate(int[] comm, int count) {
            int capacity = n + neighbours.length / 2;
            int[] us = new int[capacity];
            int[] vs = new int[capacity];
            double[] ws = new double[capacity];
            int edgeCount = 0;
            for (int i = 0; i < n; i++) {
                if (selfWeight[i] > 0) {
                    us[edgeCount] = comm[i];
                    vs[edgeCount] = comm[i];
                    ws[edgeCount++] = selfWeight[i];
                }
                for (int e = offsets[i]; e < offsets[i + 1]; e++) {
                    int j = neighbours[e];
                    if (j > i) {
                        us[edgeCount] = comm[i];
                        vs[edgeCount] = comm[j];
                        ws[edgeCount++] = weights[e];
                    }
                }
            }
            return build(count, us, vs, ws, edgeCount);
        }
    }
}
