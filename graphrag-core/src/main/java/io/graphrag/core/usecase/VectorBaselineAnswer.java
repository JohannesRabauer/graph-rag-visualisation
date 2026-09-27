package io.graphrag.core.usecase;

import io.graphrag.core.domain.RetrievalStep;

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
 * steps in descending similarity order, then one {@code SYNTHESIS} step.
 * Empty when no chunks are available ({@code noChunks == true}).
 *
 * <p>{@code queryProjection} is the query embedding's own 2D position in the
 * corpus's already-settled projection (Story 8.5) — {@code {0.0, 0.0}} when
 * no projection model exists yet for the corpus.
 */
public record VectorBaselineAnswer(boolean noChunks, String answer, String reason, List<RetrievalStep> steps,
                                    double[] queryProjection) {

    public VectorBaselineAnswer {
        steps = steps == null ? List.of() : List.copyOf(steps);
        queryProjection = queryProjection == null || queryProjection.length != 2
                ? new double[]{0.0, 0.0} : queryProjection.clone();
    }

    @Override
    public double[] queryProjection() {
        return queryProjection.clone();
    }

    public static VectorBaselineAnswer matched(String answer, List<RetrievalStep> steps, double[] queryProjection) {
        return new VectorBaselineAnswer(false, answer, null, steps, queryProjection);
    }

    public static VectorBaselineAnswer noChunksYet() {
        return new VectorBaselineAnswer(true, null,
                "The vector index for this corpus is not ready yet — it may still be building alongside "
                        + "the knowledge graph. Try again once ingestion completes.",
                List.of(), null);
    }
}
