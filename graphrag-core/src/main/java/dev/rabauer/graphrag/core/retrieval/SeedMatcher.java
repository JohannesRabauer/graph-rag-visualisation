package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.port.GraphStorePort;

import java.util.List;

/**
 * Finds the Entities a question is about: the seeds Local Search expands
 * from. Implementations: {@link KeywordSeedMatcher} (the default without a
 * semantic embedding model), {@link SemanticSeedMatcher},
 * {@link IdentifierSeedMatcher} (code identifiers) and
 * {@link HybridSeedMatcher} (reciprocal-rank fusion); see {@link SeedMatchers}.
 */
@FunctionalInterface
public interface SeedMatcher {

    /**
     * @param question the question; may be null
     * @param corpusId the corpus to search
     * @param graph    where the Entities are read from
     * @param limit    the most seeds to return (at least 1)
     * @return at most {@code limit} matches, best first; empty when nothing
     *         matches; never null
     */
    List<SeedMatch> match(String question, String corpusId, GraphStorePort graph, int limit);
}
