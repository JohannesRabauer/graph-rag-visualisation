package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.RetrievalStep;

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
 */
public record VectorBaselineAnswer(boolean noChunks, String answer, String reason, List<RetrievalStep> steps) {

    public VectorBaselineAnswer {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static VectorBaselineAnswer matched(String answer, List<RetrievalStep> steps) {
        return new VectorBaselineAnswer(false, answer, null, steps);
    }

    public static VectorBaselineAnswer noChunksYet() {
        return new VectorBaselineAnswer(true, null,
                "The vector index for this corpus is not ready yet — it may still be building alongside "
                        + "the knowledge graph. Try again once ingestion completes.",
                List.of());
    }
}
