package io.graphrag.core.usecase;

import io.graphrag.core.domain.Chunk;
import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.EmbeddedChunk;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.ProjectionModel;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.port.EmbeddingPort;
import io.graphrag.core.port.LlmPort;
import io.graphrag.core.port.VectorStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Answers a question using a deliberately plain vector-similarity baseline.
 *
 * <p>The retrieval path is intentionally simple and contains no GraphRAG
 * logic: embed the query, compute cosine similarity against every persisted
 * {@link EmbeddedChunk} for the corpus, take the top-k most similar chunks,
 * and synthesize a plain-language answer from those chunks via
 * {@link LlmPort#synthesizeFromChunks}. No graph traversal, no community
 * summaries, no hybrid mixing.
 *
 * <p>This use case exists to make the contrast with GraphRAG's graph-based
 * retrieval path visible, not to compete with it in quality.
 */
public class AnswerVectorBaseline {

    private static final int TOP_K = 5;

    /**
     * Null-safe default that provides a no-op {@link LlmPort} — the
     * {@code synthesizeFromChunks} default method on the interface itself
     * handles the actual fallback text, so this only needs to satisfy the
     * non-null contract.
     */
    private static final LlmPort DEFAULT_LLM_PORT = corpus -> new GraphExtraction(List.of(), List.of());

    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStorePort;
    private final LlmPort llmPort;

    public AnswerVectorBaseline(EmbeddingPort embeddingPort, VectorStorePort vectorStorePort, LlmPort llmPort) {
        this.embeddingPort = embeddingPort;
        this.vectorStorePort = vectorStorePort;
        this.llmPort = llmPort == null ? DEFAULT_LLM_PORT : llmPort;
    }

    public VectorBaselineAnswer answer(String question, String corpusId) {
        Collection<EmbeddedChunk> allChunks = vectorStorePort.chunks(corpusId);
        if (allChunks == null || allChunks.isEmpty()) {
            return VectorBaselineAnswer.noChunksYet();
        }

        float[] queryEmbedding = embeddingPort.embed(question == null ? "" : question);

        List<RetrievalStep> steps = new ArrayList<>();
        steps.add(new RetrievalStep(RetrievalStep.Kind.VECTOR_QUERY_EMBEDDED, "query",
                question == null ? "" : question));

        // Score and sort in descending similarity order, then take top-k.
        List<ScoredChunk> scored = new ArrayList<>(allChunks.size());
        for (EmbeddedChunk ec : allChunks) {
            double similarity = cosineSimilarity(queryEmbedding, ec.embedding());
            scored.add(new ScoredChunk(ec, similarity));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());

        List<Chunk> topChunks = new ArrayList<>(Math.min(TOP_K, scored.size()));
        for (int i = 0; i < Math.min(TOP_K, scored.size()); i++) {
            ScoredChunk sc = scored.get(i);
            topChunks.add(sc.chunk().chunk());
            steps.add(new RetrievalStep(RetrievalStep.Kind.VECTOR_CHUNK,
                    sc.chunk().chunk().id(),
                    String.format(Locale.ROOT, "score=%.3f", sc.score())));
        }

        String synthesized = llmPort.synthesizeFromChunks(question, topChunks);
        steps.add(new RetrievalStep(RetrievalStep.Kind.SYNTHESIS, "", synthesized));

        Optional<ProjectionModel> projectionModel = vectorStorePort.projectionModel(corpusId);
        double[] queryProjection = projectionModel
                .map(model -> TwoDProjection.project(model, queryEmbedding))
                .orElse(new double[]{0.0, 0.0});

        return VectorBaselineAnswer.matched(synthesized, steps, queryProjection);
    }

    /**
     * Computes cosine similarity between two float vectors.
     *
     * <p>Returns {@code 0.0} if either vector is null, has zero length, or
     * has an L2 norm of zero — never NaN or Infinity.
     */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0.0;
        }
        int len = Math.min(a.length, b.length);
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < len; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        normA = Math.sqrt(normA);
        normB = Math.sqrt(normB);
        if (normA <= 0.0 || normB <= 0.0 || !Double.isFinite(normA) || !Double.isFinite(normB)) {
            return 0.0;
        }
        double result = dot / (normA * normB);
        return Double.isFinite(result) ? result : 0.0;
    }

    private record ScoredChunk(EmbeddedChunk chunk, double score) {
    }
}
