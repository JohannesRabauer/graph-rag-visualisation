package io.graphrag.core.usecase;

import io.graphrag.core.domain.Citation;
import io.graphrag.core.domain.RetrievalStep;

import java.util.List;

/**
 * Result of {@link AnswerLocalSearch}.
 *
 * <p>A corpus with no graph-grounded match for the question still returns
 * an ordinary, successful {@code answer} explaining that in plain language
 * ({@link #noMatch()}), with an empty {@code steps} trace.
 *
 * <p>Story 15.2: when an answer-synthesizing LLM ran, the answer carries
 * inline {@code [i]} markers that index into {@code citations}
 * ({@code citations[i-1]}). When the model said the retrieved context does
 * not answer the question, the result is a {@code noAnswer} with a
 * plain-language {@code reason} and the steps recorded so far
 * ({@link #notInContext(String, List)}).
 */
public record LocalSearchAnswer(String answer, List<RetrievalStep> steps, boolean noAnswer, String reason,
                                List<Citation> citations) {

    public LocalSearchAnswer {
        steps = steps == null ? List.of() : List.copyOf(steps);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public LocalSearchAnswer(String answer, List<RetrievalStep> steps) {
        this(answer, steps, false, null, List.of());
    }

    public static LocalSearchAnswer matched(String answer, List<RetrievalStep> steps) {
        return new LocalSearchAnswer(answer, steps);
    }

    /** A generated answer whose {@code [i]} markers index into {@code citations}. */
    public static LocalSearchAnswer synthesized(String answer, List<RetrievalStep> steps, List<Citation> citations) {
        return new LocalSearchAnswer(answer, steps, false, null, citations);
    }

    public static LocalSearchAnswer noMatch() {
        return new LocalSearchAnswer(
                "No graph-grounded local match was found for this corpus yet. "
                        + "Try asking about a named entity or relationship visible in the graph.",
                List.of());
    }

    /** The retrieved context does not answer the question; the steps so far are kept. */
    public static LocalSearchAnswer notInContext(String reason, List<RetrievalStep> steps) {
        return new LocalSearchAnswer(null, steps, true, reason, List.of());
    }
}
