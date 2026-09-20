package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.RetrievalStep;

import java.util.List;

/**
 * Result of {@link AnswerGlobalSearch}.
 *
 * <p>{@code noAnswer} distinguishes the "no Communities exist yet" outcome
 * (AD-13's distinct no-answer response shape) from an ordinary answer.
 * Communities existing but none of them scoring a match is still an
 * ordinary answer ({@code noAnswer() == false}) that explains the lack of a
 * clear match in plain language — only an empty {@code communities()}
 * collection (detection/summary generation not yet complete) is
 * {@code noAnswer}.
 *
 * <p>{@code steps} is the ordered list of {@link RetrievalStep}s — one per
 * Community examined while scoring, in iteration order — regardless of
 * which outcome resulted. This use case stays trace-store-unaware: it only
 * returns the steps it touched; storing them under a {@code traceId} is the
 * web layer's job (Story 5.1).
 */
public record GlobalSearchAnswer(boolean noAnswer, String answer, String reason, List<RetrievalStep> steps) {

    public GlobalSearchAnswer {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static GlobalSearchAnswer matched(String answer, List<RetrievalStep> steps) {
        return new GlobalSearchAnswer(false, answer, null, steps);
    }

    public static GlobalSearchAnswer noCommunitiesYet() {
        return new GlobalSearchAnswer(true, null,
                "No Communities have been detected for this corpus yet — community detection and summary "
                        + "generation may still be running. Try Global Search again once it completes.",
                List.of());
    }
}
