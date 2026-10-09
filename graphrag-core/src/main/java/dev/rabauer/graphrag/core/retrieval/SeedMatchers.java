package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.port.EmbeddingPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Factories for the common {@link SeedMatcher} setups. */
public final class SeedMatchers {

    private SeedMatchers() {
    }

    /**
     * The default: semantic seeds, falling back to keywords when the corpus
     * has no Entity embeddings, for a semantic {@link EmbeddingPort}; the
     * {@link KeywordSeedMatcher} for a null or non-semantic one.
     */
    public static SeedMatcher defaultFor(EmbeddingPort embeddingPort) {
        if (embeddingPort == null || !embeddingPort.isSemantic()) {
            return new KeywordSeedMatcher();
        }
        return firstNonEmpty(new SemanticSeedMatcher(embeddingPort), new KeywordSeedMatcher());
    }

    /**
     * Code questions: identifiers, fused with semantic seeds (RRF) when
     * {@code embeddingPort} is semantic.
     */
    public static SeedMatcher forCode(EmbeddingPort embeddingPort) {
        if (embeddingPort == null || !embeddingPort.isSemantic()) {
            return new IdentifierSeedMatcher();
        }
        return new HybridSeedMatcher(List.of(new IdentifierSeedMatcher(), new SemanticSeedMatcher(embeddingPort)));
    }

    /** The first matcher's seeds, or the next one's when it found none, and so on. */
    public static SeedMatcher firstNonEmpty(SeedMatcher... matchers) {
        List<SeedMatcher> chain = List.of(matchers);
        return (question, corpusId, graph, limit) -> {
            for (SeedMatcher matcher : chain) {
                List<SeedMatch> matches = matcher.match(question, corpusId, graph, limit);
                if (!matches.isEmpty()) {
                    return matches;
                }
            }
            return List.of();
        };
    }

    // -- Composing matchers: scores of different matchers are not comparable -----------------

    /**
     * Reciprocal-rank fusion of ranked lists: an Entity's score is the sum of
     * {@code 1 / (k + rank)} over the lists that hold it (rank 1-based, the
     * position in the list), so only the order of each list counts and
     * matchers on any scale fuse fairly. Ties keep the order in which Entities
     * were first seen. The result is marked {@code hybrid}.
     *
     * @param rankings the lists, each best first
     * @param k        the RRF constant (at least 1; {@link HybridSeedMatcher#DEFAULT_K} is the usual 60)
     * @param limit    the most matches to return
     * @throws IllegalArgumentException if {@code k} is below 1
     */
    public static List<SeedMatch> fuseByRank(List<List<SeedMatch>> rankings, int k, int limit) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be at least 1, was " + k);
        }
        if (limit < 1 || rankings == null) {
            return List.of();
        }
        Map<String, Entity> entities = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<SeedMatch> ranked : rankings) {
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
        // List.sort is stable, so ties keep first-seen order.
        fused.sort(Comparator.comparingDouble(SeedMatch::score).reversed());
        return List.copyOf(fused.subList(0, Math.min(limit, fused.size())));
    }

    /**
     * The matcher with its scores moved onto a common scale: when no score is
     * negative, every score divided by the best one (the best becomes 1, the
     * ratios stay); when some are (cosine similarity), a linear map of the
     * worst to 0 and the best to 1. Results whose scores are all equal get 1.
     * The order and {@code matchedBy} are kept.
     */
    public static SeedMatcher normalized(SeedMatcher matcher) {
        Objects.requireNonNull(matcher, "matcher");
        return (question, corpusId, graph, limit) -> normalize(matcher.match(question, corpusId, graph, limit));
    }

    /**
     * Matchers combined on a common scale: each one's scores are
     * {@linkplain #normalized(SeedMatcher) normalised}, multiplied by its
     * weight and added per Entity. Use it when the strength of a match matters
     * and not only its rank; otherwise prefer {@link HybridSeedMatcher}. Each
     * matcher is asked for up to {@link HybridSeedMatcher#DEFAULT_CANDIDATES} seeds
     * (or {@code limit}, if larger). Ties keep the order in which Entities were
     * first seen; the result is marked {@code weighted}.
     *
     * @param matchers the matchers, at least one
     * @param weights  one weight (finite, not negative) per matcher
     * @throws IllegalArgumentException for no matcher, another number of weights, or a bad weight
     */
    public static SeedMatcher weightedSum(List<SeedMatcher> matchers, List<Double> weights) {
        if (matchers == null || matchers.isEmpty()) {
            throw new IllegalArgumentException("at least one matcher is required");
        }
        if (weights == null || weights.size() != matchers.size()) {
            throw new IllegalArgumentException("one weight per matcher is required");
        }
        if (weights.stream().anyMatch(weight -> weight == null || weight < 0 || !Double.isFinite(weight))) {
            throw new IllegalArgumentException("weights must be finite and not negative");
        }
        List<SeedMatcher> members = List.copyOf(matchers);
        List<Double> factors = List.copyOf(weights);
        return (question, corpusId, graph, limit) -> {
            if (limit < 1) {
                return List.of();
            }
            Map<String, Entity> entities = new LinkedHashMap<>();
            Map<String, Double> sums = new LinkedHashMap<>();
            for (int i = 0; i < members.size(); i++) {
                List<SeedMatch> scaled = normalize(members.get(i).match(question, corpusId, graph,
                        Math.max(limit, HybridSeedMatcher.DEFAULT_CANDIDATES)));
                for (SeedMatch match : scaled) {
                    String identity = match.entity().normalizedIdentity();
                    entities.putIfAbsent(identity, match.entity());
                    sums.merge(identity, factors.get(i) * match.score(), Double::sum);
                }
            }
            List<SeedMatch> combined = new ArrayList<>();
            for (Map.Entry<String, Double> entry : sums.entrySet()) {
                combined.add(new SeedMatch(entities.get(entry.getKey()), entry.getValue(), "weighted"));
            }
            combined.sort(Comparator.comparingDouble(SeedMatch::score).reversed());
            return List.copyOf(combined.subList(0, Math.min(limit, combined.size())));
        };
    }

    /**
     * The matcher with its results checked against the {@link SeedMatcher} score
     * contract: not null, at most {@code limit} matches, finite scores, best
     * first. A violation throws {@link IllegalStateException} naming it. Wrap a
     * matcher with it in tests, or in an application that composes matchers it
     * did not write.
     */
    public static SeedMatcher validated(SeedMatcher matcher) {
        Objects.requireNonNull(matcher, "matcher");
        return (question, corpusId, graph, limit) -> {
            List<SeedMatch> matches = matcher.match(question, corpusId, graph, limit);
            if (matches == null) {
                throw new IllegalStateException("A SeedMatcher must return a list, not null");
            }
            if (matches.size() > limit) {
                throw new IllegalStateException("A SeedMatcher returned " + matches.size()
                        + " matches for a limit of " + limit);
            }
            double previous = Double.POSITIVE_INFINITY;
            for (SeedMatch match : matches) {
                if (!Double.isFinite(match.score())) {
                    throw new IllegalStateException("A SeedMatcher returned a score that is not finite: "
                            + match.score() + " for " + match.entity().name());
                }
                if (match.score() > previous) {
                    throw new IllegalStateException("A SeedMatcher must return its matches best first, but "
                            + match.entity().name() + " (" + match.score() + ") follows a lower score ("
                            + previous + ")");
                }
                previous = match.score();
            }
            return matches;
        };
    }

    private static List<SeedMatch> normalize(List<SeedMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        double best = matches.stream().mapToDouble(SeedMatch::score).max().orElse(0);
        double worst = matches.stream().mapToDouble(SeedMatch::score).min().orElse(0);
        List<SeedMatch> scaled = new ArrayList<>(matches.size());
        for (SeedMatch match : matches) {
            double score;
            if (best == worst) {
                score = 1.0;
            } else if (worst >= 0) {
                score = match.score() / best;
            } else {
                score = (match.score() - worst) / (best - worst);
            }
            scaled.add(match.withScore(score));
        }
        return List.copyOf(scaled);
    }
}
