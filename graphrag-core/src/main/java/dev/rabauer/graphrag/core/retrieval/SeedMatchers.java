package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.port.EmbeddingPort;

import java.util.List;

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
}
