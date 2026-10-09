package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.port.GraphReadPort;

import java.util.List;

/**
 * Finds the Entities a question is about: the seeds Local Search expands
 * from. Implementations: {@link KeywordSeedMatcher} (the default without a
 * semantic embedding model), {@link SemanticSeedMatcher},
 * {@link IdentifierSeedMatcher} (code identifiers) and
 * {@link HybridSeedMatcher} (reciprocal-rank fusion); see {@link SeedMatchers}.
 *
 * <p><b>The score contract.</b> Every {@link SeedMatch#score()} is a finite
 * number and a higher score is better, and a result is sorted best first. The
 * scale is the matcher's own (identifier 0 to 100 and more, reciprocal-rank
 * fusion about 0.03, keyword counts), so scores of different matchers, or of
 * one matcher on different questions, must not be compared or added. Compose
 * matchers by rank ({@link HybridSeedMatcher}, {@link SeedMatchers#fuseByRank})
 * or on a common scale ({@link SeedMatchers#normalized(SeedMatcher)},
 * {@link SeedMatchers#weightedSum}); {@link SeedMatchers#validated(SeedMatcher)}
 * checks the contract on a matcher's results while developing it.
 */
@FunctionalInterface
public interface SeedMatcher {

    /**
     * @param question the question; may be null
     * @param corpusId the corpus to search
     * @param graph    where the Entities are read from
     * @param limit    the most seeds to return (at least 1)
     * @return at most {@code limit} matches, best first, with finite scores;
     *         empty when nothing matches; never null
     */
    List<SeedMatch> match(String question, String corpusId, GraphReadPort graph, int limit);
}
