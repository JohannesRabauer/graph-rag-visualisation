package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.port.GraphStorePort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fuses several matchers with reciprocal-rank fusion: each matcher is asked
 * for up to {@code candidates} seeds, and an Entity's fused score is the sum of
 * {@code 1 / (k + rank)} over the matchers that returned it (rank 1-based).
 * Ties keep the order in which Entities were first seen (first matcher
 * first). Typical use: {@code new HybridSeedMatcher(List.of(new
 * IdentifierSeedMatcher(), new SemanticSeedMatcher(embeddings)))}.
 */
public final class HybridSeedMatcher implements SeedMatcher {

    /** The usual RRF constant. */
    public static final int DEFAULT_K = 60;
    /** How many seeds each matcher is asked for by default. */
    public static final int DEFAULT_CANDIDATES = 20;

    private final List<SeedMatcher> matchers;
    private final int k;
    private final int candidates;

    public HybridSeedMatcher(List<SeedMatcher> matchers) {
        this(matchers, DEFAULT_K, DEFAULT_CANDIDATES);
    }

    /**
     * @param matchers   the matchers to fuse, at least one
     * @param k          the RRF constant (at least 1)
     * @param candidates how many seeds each matcher is asked for (at least 1)
     */
    public HybridSeedMatcher(List<SeedMatcher> matchers, int k, int candidates) {
        if (matchers == null || matchers.isEmpty()) {
            throw new IllegalArgumentException("at least one matcher is required");
        }
        if (k < 1 || candidates < 1) {
            throw new IllegalArgumentException("k and candidates must be at least 1");
        }
        this.matchers = matchers.stream().filter(Objects::nonNull).toList();
        this.k = k;
        this.candidates = candidates;
    }

    @Override
    public List<SeedMatch> match(String question, String corpusId, GraphStorePort graph, int limit) {
        if (limit < 1) {
            return List.of();
        }
        Map<String, Entity> entities = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (SeedMatcher matcher : matchers) {
            List<SeedMatch> ranked = matcher.match(question, corpusId, graph, Math.max(limit, candidates));
            for (int rank = 0; rank < ranked.size(); rank++) {
                Entity entity = ranked.get(rank).entity();
                String identity = entity.normalizedIdentity();
                entities.putIfAbsent(identity, entity);
                scores.merge(identity, 1.0 / (k + rank + 1), Double::sum);
            }
        }
        List<SeedMatch> fused = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            fused.add(new SeedMatch(entities.get(entry.getKey()), entry.getValue(), "hybrid"));
        }
        fused.sort(Comparator.comparingDouble(SeedMatch::score).reversed());
        return List.copyOf(fused.subList(0, Math.min(limit, fused.size())));
    }
}
