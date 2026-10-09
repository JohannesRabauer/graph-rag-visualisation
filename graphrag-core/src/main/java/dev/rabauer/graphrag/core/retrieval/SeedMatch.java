package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.Entity;

import java.util.Objects;

/**
 * One seed Entity a {@link SeedMatcher} found.
 *
 * @param entity    the matched Entity; never null
 * @param score     the matcher's score: a finite number, higher is better.
 *                  <b>Only comparable within one matcher's result</b>, and
 *                  not even across questions: identifier scores run 0 to 100
 *                  and more, reciprocal-rank fusion gives about 0.03, keyword
 *                  scores are counts. To combine matchers fuse by rank
 *                  ({@link SeedMatchers#fuseByRank}, {@link HybridSeedMatcher}) or
 *                  scale first ({@link SeedMatchers#normalized(SeedMatcher)},
 *                  {@link SeedMatchers#weightedSum}); never add raw scores
 * @param matchedBy which matcher found it ({@code keyword}, {@code semantic},
 *                  {@code identifier}, {@code hybrid}, {@code weighted}, ...)
 */
public record SeedMatch(Entity entity, double score, String matchedBy) {

    public SeedMatch {
        Objects.requireNonNull(entity, "entity");
        matchedBy = matchedBy == null ? "" : matchedBy;
    }

    /** This match with another score (for example a scaled one). */
    public SeedMatch withScore(double value) {
        return new SeedMatch(entity, value, matchedBy);
    }
}
