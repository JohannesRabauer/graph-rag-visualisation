package io.graphrag.core.usecase;

import io.graphrag.core.domain.RetrievalStep;

import java.util.List;

/**
 * Result of {@link AnswerLocalSearch}.
 *
 * <p>Unlike {@link GlobalSearchAnswer}, Local Search has no distinct
 * "noAnswer" wire shape — a corpus with no graph-grounded match for the
 * question still returns an ordinary, successful {@code answer} explaining
 * that in plain language (matching this project's existing, tested
 * contract), with an empty {@code steps} trace.
 */
public record LocalSearchAnswer(String answer, List<RetrievalStep> steps) {

    public LocalSearchAnswer {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static LocalSearchAnswer matched(String answer, List<RetrievalStep> steps) {
        return new LocalSearchAnswer(answer, steps);
    }

    public static LocalSearchAnswer noMatch() {
        return new LocalSearchAnswer(
                "No graph-grounded local match was found for this corpus yet. "
                        + "Try asking about a named entity or relationship visible in the graph.",
                List.of());
    }
}
