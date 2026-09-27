package io.graphrag.core.usecase;

import io.graphrag.core.domain.RetrievalStep;

import java.util.List;

/**
 * Result of {@link AnswerDriftSearch}.
 *
 * <p>Like {@link GlobalSearchAnswer}, DRIFT Search preserves a distinct
 * {@code noAnswer} outcome — not only for the "no Communities exist yet"
 * case, but also whenever the Community pass yields zero viable
 * sub-questions (no Community scores a match), per this story's own
 * acceptance criteria. Once at least one sub-question is spawned and
 * answered, every outcome is an ordinary answer — even when no spawned
 * Local Search finds a graph-grounded result.
 */
public record DriftSearchAnswer(boolean noAnswer, String answer, String reason, List<RetrievalStep> steps) {

    public DriftSearchAnswer {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static DriftSearchAnswer matched(String answer, List<RetrievalStep> steps) {
        return new DriftSearchAnswer(false, answer, null, steps);
    }

    public static DriftSearchAnswer noCommunitiesYet() {
        return new DriftSearchAnswer(true, null,
                "DRIFT Search cannot run yet because this corpus has no Community summaries. "
                        + "Wait for the Community pass to finish, then try again.",
                List.of());
    }

    public static DriftSearchAnswer noViableSubQuestions(List<RetrievalStep> steps) {
        return new DriftSearchAnswer(true, null,
                "DRIFT's Community pass didn't find a Community summary that clearly matched this question, so no "
                        + "sub-questions could be generated. Try asking about a named person, place, or event.",
                steps);
    }
}
