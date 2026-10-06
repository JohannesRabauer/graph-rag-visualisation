package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.usecase.KeywordMatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The keyword matcher Local Search has always used: scores
 * {@code name + " " + type} by {@link KeywordMatcher} (whole-word overlap plus
 * a small typo tolerance) and keeps Entities scoring above zero, highest
 * first, ties in stored order. With {@code limit} 1 it returns exactly the
 * classic single best-matching seed.
 */
public final class KeywordSeedMatcher implements SeedMatcher {

    @Override
    public List<SeedMatch> match(String question, String corpusId, GraphStorePort graph, int limit) {
        Set<String> tokens = KeywordMatcher.tokenize(question);
        Collection<Entity> entities = graph.entities(corpusId);
        if (tokens.isEmpty() || entities == null || limit < 1) {
            return List.of();
        }
        List<SeedMatch> matches = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity == null) {
                continue;
            }
            int score = KeywordMatcher.score(entity.name() + " " + entity.type(), tokens);
            if (score > 0) {
                matches.add(new SeedMatch(entity, score, "keyword"));
            }
        }
        // List.sort is stable, so ties keep stored order.
        matches.sort(Comparator.comparingDouble(SeedMatch::score).reversed());
        return List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
    }
}
