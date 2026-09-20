package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.RetrievalStep;

import java.util.List;

/**
 * Result of {@link AnswerDriftSearch}.
 *
 * <p>Like {@link GlobalSearchAnswer}, DRIFT Search preserves a distinct
 * {@code noAnswer} outcome for the "no Communities exist yet" case. Once
 * Communities do exist, every other outcome is an ordinary answer — even
 * when no Community scores a match or no spawned Local Search finds a
 * graph-grounded result.
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
}
