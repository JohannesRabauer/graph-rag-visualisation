package io.graphrag.core.usecase;

import io.graphrag.core.domain.Citation;
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
 *
 * <p>Story 15.3: when an answer-synthesizing LLM ran, the answer carries
 * inline {@code [i]} markers that index into {@code citations}
 * ({@code citations[i-1]}); otherwise {@code citations} is empty.
 */
public record DriftSearchAnswer(boolean noAnswer, String answer, String reason, List<RetrievalStep> steps,
                                List<Citation> citations) {

    public DriftSearchAnswer {
        steps = steps == null ? List.of() : List.copyOf(steps);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** Without citations: a templated answer or a no-answer outcome. */
    public DriftSearchAnswer(boolean noAnswer, String answer, String reason, List<RetrievalStep> steps) {
        this(noAnswer, answer, reason, steps, List.of());
    }

    /** Story 15.3: a generated answer whose {@code [i]} markers index into {@code citations}. */
    public static DriftSearchAnswer synthesized(String answer, List<RetrievalStep> steps, List<Citation> citations) {
        return new DriftSearchAnswer(false, answer, null, steps, citations);
    }

    /** Story 15.3: the retrieved context does not answer the question; the steps so far are kept. */
    public static DriftSearchAnswer notInContext(String reason, List<RetrievalStep> steps) {
        return new DriftSearchAnswer(true, null, reason, steps);
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
