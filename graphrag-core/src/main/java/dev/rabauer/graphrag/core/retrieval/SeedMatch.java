package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.Entity;

import java.util.Objects;

/**
 * One seed Entity a {@link SeedMatcher} found.
 *
 * @param entity    the matched Entity; never null
 * @param score     the matcher's score, higher is better; only comparable
 *                  within one matcher's result
 * @param matchedBy which matcher found it ({@code keyword}, {@code semantic},
 *                  {@code identifier}, {@code hybrid}, …)
 */
public record SeedMatch(Entity entity, double score, String matchedBy) {

    public SeedMatch {
        Objects.requireNonNull(entity, "entity");
        matchedBy = matchedBy == null ? "" : matchedBy;
    }
}
