package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.usecase.SemanticMatchingException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Seeds by meaning: embeds the question and asks the graph for the most
 * similar Entities ({@link GraphStorePort#similarEntities(String, float[], int)},
 * which a store may answer from any vector store). Empty without a semantic
 * {@link EmbeddingPort} or when the corpus has no Entity embeddings. A
 * failure while embedding or looking up is a {@link SemanticMatchingException}
 * (no silent keyword fallback). The score is rank-based ({@code 1/rank}),
 * since the port returns order, not similarity values.
 */
public final class SemanticSeedMatcher implements SeedMatcher {

    private final EmbeddingPort embeddingPort;

    public SemanticSeedMatcher(EmbeddingPort embeddingPort) {
        this.embeddingPort = embeddingPort;
    }

    @Override
    public List<SeedMatch> match(String question, String corpusId, GraphStorePort graph, int limit) {
        if (embeddingPort == null || !embeddingPort.isSemantic() || limit < 1) {
            return List.of();
        }
        List<Entity> similar;
        try {
            similar = graph.similarEntities(corpusId, embeddingPort.embed(question == null ? "" : question), limit);
        } catch (RuntimeException e) {
            throw new SemanticMatchingException("Semantic Entity matching failed: " + e.getMessage(), e);
        }
        if (similar == null) {
            return List.of();
        }
        List<SeedMatch> matches = new ArrayList<>();
        for (Entity entity : similar.stream().filter(Objects::nonNull).limit(limit).toList()) {
            matches.add(new SeedMatch(entity, 1.0 / (matches.size() + 1), "semantic"));
        }
        return List.copyOf(matches);
    }
}
