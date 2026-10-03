package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.ProjectionModel;
import dev.rabauer.graphrag.core.domain.RankedChunk;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Answers a question using a deliberately plain vector-similarity baseline.
 *
 * <p>The retrieval path is intentionally simple and contains no GraphRAG
 * logic: embed the query, compute cosine similarity against every persisted
 * {@link EmbeddedChunk} for the corpus, take the top-k most similar chunks,
 * and synthesize a plain-language answer from those chunks. No graph
 * traversal, no community summaries, no hybrid mixing.
 *
 * <p>With an answer-synthesizing {@link LlmPort}
 * ({@link LlmPort#synthesizesAnswers()}) the answer is written by
 * {@link LlmPort#synthesizeAnswer} over the top-k chunks as numbered
 * "Source passage" items, and its {@code [n]} citations are resolved by the
 * same {@link CitationResolver} GraphRAG uses — so both sides of a comparison
 * stand on equal footing. Otherwise {@link LlmPort#synthesizeFromChunks}
 * joins the chunk texts, as before.
 *
 * <p>This use case exists to make the contrast with GraphRAG's graph-based
 * retrieval path visible, not to compete with it in quality.
 */
public class AnswerVectorBaseline {

    private static final int TOP_K = 5;

    /**
     * How many of the highest-scoring chunks the similarity ranking
     * ({@link VectorBaselineAnswer#ranking()}) shows: the top-k that feed the
     * answer plus the runners-up below the cut-off.
     */
    public static final int RANKING_SIZE = 12;

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

    /** This baseline with its embedding and LLM calls timed by {@code clock}. */
    AnswerVectorBaseline timed(StageClock clock) {
        return new AnswerVectorBaseline(clock.embedding(embeddingPort), vectorStorePort, clock.llm(llmPort));
    }

    public VectorBaselineAnswer answer(String question, String corpusId) {
        Collection<EmbeddedChunk> allChunks = vectorStorePort.chunks(corpusId);
        if (allChunks == null || allChunks.isEmpty()) {
            return VectorBaselineAnswer.noChunksYet();
        }
        List<ScoredChunk> scored = new ArrayList<>(allChunks.size());
        VectorBaselineAnswer result = answer(question, corpusId, allChunks, scored);
        return result.withRanking(ranking(scored), scored.size());
    }

    /**
     * The top {@link #RANKING_SIZE} of {@code scored} (already in descending
     * score order), the first {@code TOP_K} marked as used.
     */
    private static List<RankedChunk> ranking(List<ScoredChunk> scored) {
        int size = Math.min(RANKING_SIZE, scored.size());
        List<RankedChunk> ranking = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ScoredChunk sc = scored.get(i);
            Chunk chunk = sc.chunk().chunk();
            ranking.add(new RankedChunk(i + 1, chunk.id(), chunk.documentName(),
                    LocalContextAssembler.excerpt(chunk.text()), sc.score(), i < TOP_K));
        }
        return ranking;
    }

    private VectorBaselineAnswer answer(String question, String corpusId, Collection<EmbeddedChunk> allChunks,
                                        List<ScoredChunk> scored) {
        float[] queryEmbedding = embeddingPort.embed(question == null ? "" : question);

        List<RetrievalStep> steps = new ArrayList<>();
        steps.add(new RetrievalStep(RetrievalStep.Kind.VECTOR_QUERY_EMBEDDED, "query",
                question == null ? "" : question));

        // Score and sort in descending similarity order, then take top-k.
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

        Optional<ProjectionModel> projectionModel = vectorStorePort.projectionModel(corpusId);
        double[] queryProjection = projectionModel
                .map(model -> TwoDProjection.project(model, queryEmbedding))
                .orElse(new double[]{0.0, 0.0});

        if (llmPort.synthesizesAnswers()) {
            return synthesizedAnswer(question, topChunks, steps, queryProjection);
        }

        String synthesized = llmPort.synthesizeFromChunks(question, topChunks);
        steps.add(new RetrievalStep(RetrievalStep.Kind.SYNTHESIS, "", synthesized));
        return VectorBaselineAnswer.matched(synthesized, steps, queryProjection);
    }

    /**
     * The synthesizing path: the top-k chunks become numbered, citable
     * "Source passage" context items (the chunk id stands in for the Text
     * Unit id), the LLM writes a cited answer, and its {@code [n]} markers are
     * resolved by {@link CitationResolver} exactly as for GraphRAG.
     */
    private VectorBaselineAnswer synthesizedAnswer(String question, List<Chunk> topChunks,
                                                   List<RetrievalStep> steps, double[] queryProjection) {
        List<LocalContextAssembler.Item> items = new ArrayList<>();
        Map<String, Citation> citationsByChunk = new LinkedHashMap<>();
        for (Chunk chunk : topChunks) {
            if (chunk == null || chunk.text() == null || citationsByChunk.containsKey(chunk.id())) {
                continue;
            }
            citationsByChunk.put(chunk.id(),
                    new Citation(chunk.id(), chunk.documentName(), LocalContextAssembler.excerpt(chunk.text())));
            items.add(new LocalContextAssembler.Item("TEXT_UNIT:" + chunk.id(), RetrievalStep.Kind.TEXT_UNIT,
                    chunk.text(), chunk.id()));
        }
        List<ContextItem> context = LocalContextAssembler.number(items);

        SynthesizedAnswer synthesized = llmPort.synthesizeAnswer(question, context);
        if (LocalContextAssembler.isNotInContext(synthesized)) {
            return VectorBaselineAnswer.notInContext(VectorBaselineAnswer.NOT_IN_CONTEXT_REASON, steps,
                    queryProjection);
        }
        CitationResolver.Resolution resolution =
                CitationResolver.resolve(synthesized.text(), context, citationsByChunk);
        if (resolution.text().isBlank()) {
            return VectorBaselineAnswer.notInContext(VectorBaselineAnswer.NOT_IN_CONTEXT_REASON, steps,
                    queryProjection);
        }
        steps.add(new RetrievalStep(RetrievalStep.Kind.SYNTHESIS, "", resolution.text()));
        return VectorBaselineAnswer.synthesized(resolution.text(), steps, queryProjection, resolution.citations());
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
