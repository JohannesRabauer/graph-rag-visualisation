package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.RankedChunk;
import dev.rabauer.graphrag.core.domain.RetrievalStep;

import java.util.List;

/**
 * Result of {@link AnswerVectorBaseline}.
 *
 * <p>{@code noChunks} distinguishes the "vector index not yet built for this
 * corpus" outcome from an ordinary answer. This is the vector-baseline
 * counterpart to {@link GlobalSearchAnswer}'s {@code noAnswer} / {@link DriftSearchAnswer}'s
 * {@code noAnswer} shape — a deliberate, expected outcome that the web
 * layer surfaces as {@code noAnswer: true} rather than an error.
 *
 * <p>{@code steps} is the ordered trace of what the retrieval process touched:
 * one {@code VECTOR_QUERY_EMBEDDED} step, then up to k {@code VECTOR_CHUNK}
 * steps in descending similarity order, then one {@code SYNTHESIS} step
 * (absent when the synthesizing LLM said the chunks do not answer the
 * question). Empty when no chunks are available ({@code noChunks == true}).
 *
 * <p>{@code queryProjection} is the query embedding's own 2D position in the
 * corpus's already-settled projection (Story 8.5) — {@code {0.0, 0.0}} when
 * no projection model exists yet for the corpus.
 *
 * <p>With an answer-synthesizing LLM the answer carries inline {@code [i]}
 * markers that index into {@code citations} ({@code citations[i-1]}), each
 * citing one retrieved chunk ({@link Citation#textUnitId()} is the chunk id).
 * Offline, {@code citations} is empty. When the model said the chunks do not
 * answer the question, the result has no {@code answer} and a {@code reason}
 * ({@link #notInContext(String, List, double[])}); {@link #noAnswer()} covers
 * both no-answer outcomes.
 *
 * <p>{@code ranking} is the similarity ranking behind the top-k selection: the
 * {@link AnswerVectorBaseline#RANKING_SIZE} highest-scoring chunks in
 * descending score order (the same order and tie-break as the top-k), each
 * marked {@link RankedChunk#used()} when it is one of the top-k chunks that
 * feed the answer. {@code scoredChunkCount} is the total number of chunks
 * scored. Both are empty / {@code 0} when no chunks exist; use
 * {@link #withRanking(List, int)} to attach them to a result.
 */
public record VectorBaselineAnswer(boolean noChunks, String answer, String reason, List<RetrievalStep> steps,
                                    double[] queryProjection, List<Citation> citations,
                                    List<RankedChunk> ranking, int scoredChunkCount) {

    /** Shown when the synthesizing LLM said the retrieved chunks do not answer the question. */
    public static final String NOT_IN_CONTEXT_REASON =
            "The passages retrieved by vector similarity for this question do not answer it. "
                    + "Try rephrasing the question with words that appear in the documents.";

    public VectorBaselineAnswer {
        steps = steps == null ? List.of() : List.copyOf(steps);
        queryProjection = queryProjection == null || queryProjection.length != 2
                ? new double[]{0.0, 0.0} : queryProjection.clone();
        citations = citations == null ? List.of() : List.copyOf(citations);
        ranking = ranking == null ? List.of() : List.copyOf(ranking);
        scoredChunkCount = Math.max(0, scoredChunkCount);
    }

    /** Without a ranking: every result built before the similarity ranking existed. */
    public VectorBaselineAnswer(boolean noChunks, String answer, String reason, List<RetrievalStep> steps,
                                double[] queryProjection, List<Citation> citations) {
        this(noChunks, answer, reason, steps, queryProjection, citations, List.of(), 0);
    }

    /** Without citations: the offline joined-text answer or the no-chunks outcome. */
    public VectorBaselineAnswer(boolean noChunks, String answer, String reason, List<RetrievalStep> steps,
                                double[] queryProjection) {
        this(noChunks, answer, reason, steps, queryProjection, List.of());
    }

    @Override
    public double[] queryProjection() {
        return queryProjection.clone();
    }

    /** This result with the given similarity ranking and scored-chunk count; everything else is kept. */
    public VectorBaselineAnswer withRanking(List<RankedChunk> ranking, int scoredChunkCount) {
        return new VectorBaselineAnswer(noChunks, answer, reason, steps, queryProjection, citations, ranking,
                scoredChunkCount);
    }

    /** Whether there is no answer: no chunks yet, or the chunks do not answer the question. */
    public boolean noAnswer() {
        return noChunks || answer == null;
    }

    public static VectorBaselineAnswer matched(String answer, List<RetrievalStep> steps, double[] queryProjection) {
        return new VectorBaselineAnswer(false, answer, null, steps, queryProjection);
    }

    /** A generated answer whose {@code [i]} markers index into {@code citations}. */
    public static VectorBaselineAnswer synthesized(String answer, List<RetrievalStep> steps, double[] queryProjection,
                                                   List<Citation> citations) {
        return new VectorBaselineAnswer(false, answer, null, steps, queryProjection, citations);
    }

    /** The retrieved chunks do not answer the question; the steps so far are kept. */
    public static VectorBaselineAnswer notInContext(String reason, List<RetrievalStep> steps,
                                                    double[] queryProjection) {
        return new VectorBaselineAnswer(false, null, reason, steps, queryProjection, List.of());
    }

    public static VectorBaselineAnswer noChunksYet() {
        return new VectorBaselineAnswer(true, null,
                "The vector index for this corpus is not ready yet — it may still be building alongside "
                        + "the knowledge graph. Try again once ingestion completes.",
                List.of(), null);
    }
}
