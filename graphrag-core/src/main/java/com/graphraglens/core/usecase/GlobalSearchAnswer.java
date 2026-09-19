package com.graphraglens.core.usecase;

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
 */
public record GlobalSearchAnswer(boolean noAnswer, String answer, String reason) {

    public static GlobalSearchAnswer matched(String answer) {
        return new GlobalSearchAnswer(false, answer, null);
    }

    public static GlobalSearchAnswer noClearMatch(String answer) {
        return new GlobalSearchAnswer(false, answer, null);
    }

    public static GlobalSearchAnswer noCommunitiesYet() {
        return new GlobalSearchAnswer(true, null,
                "No Communities have been detected for this corpus yet — community detection and summary "
                        + "generation may still be running. Try Global Search again once it completes.");
    }
}
