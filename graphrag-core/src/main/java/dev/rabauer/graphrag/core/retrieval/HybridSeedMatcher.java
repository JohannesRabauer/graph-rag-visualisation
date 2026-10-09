package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.port.GraphReadPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Fuses several matchers with reciprocal-rank fusion: each matcher is asked
 * for up to {@code candidates} seeds, and an Entity's fused score is the sum of
 * {@code 1 / (k + rank)} over the matchers that returned it (rank 1-based).
 * Ties keep the order in which Entities were first seen (first matcher
 * first). Only the order of each matcher's result counts, never its scores,
 * so matchers on different scales (identifier 0 to 100, semantic 1/rank,
 * keyword counts) fuse fairly; see {@link SeedMatchers#fuseByRank}. Typical use: {@code new HybridSeedMatcher(List.of(new
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
    public List<SeedMatch> match(String question, String corpusId, GraphReadPort graph, int limit) {
        if (limit < 1) {
            return List.of();
        }
        List<List<SeedMatch>> rankings = new ArrayList<>();
        for (SeedMatcher matcher : matchers) {
            List<SeedMatch> matches = matcher.match(question, corpusId, graph, Math.max(limit, candidates));
            // Only the order counts, never the scale. A matcher that breaks the best-first contract
            // is ranked by its scores (stable, so equal scores keep their order).
            List<SeedMatch> ranked = new ArrayList<>(matches);
            ranked.sort(Comparator.comparingDouble(SeedMatch::score).reversed());
            rankings.add(ranked);
        }
        return SeedMatchers.fuseByRank(rankings, k, limit);
    }
}
