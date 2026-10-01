package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.RetrievalStep;

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
 *
 * <p>Story 15.3: when an answer-synthesizing LLM ran, the answer carries
 * inline {@code [i]} markers that index into {@code citations}
 * ({@code citations[i-1]}); otherwise {@code citations} is empty.
 */
public record GlobalSearchAnswer(boolean noAnswer, String answer, String reason, List<RetrievalStep> steps,
                                 List<Citation> citations) {

    public GlobalSearchAnswer {
        steps = steps == null ? List.of() : List.copyOf(steps);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** Without citations: a templated answer or a no-answer outcome. */
    public GlobalSearchAnswer(boolean noAnswer, String answer, String reason, List<RetrievalStep> steps) {
        this(noAnswer, answer, reason, steps, List.of());
    }

    /** Story 15.3: a generated answer whose {@code [i]} markers index into {@code citations}. */
    public static GlobalSearchAnswer synthesized(String answer, List<RetrievalStep> steps, List<Citation> citations) {
        return new GlobalSearchAnswer(false, answer, null, steps, citations);
    }

    /** Story 15.3: the retrieved context does not answer the question; the steps so far are kept. */
    public static GlobalSearchAnswer notInContext(String reason, List<RetrievalStep> steps) {
        return new GlobalSearchAnswer(true, null, reason, steps);
    }

    public static GlobalSearchAnswer matched(String answer, List<RetrievalStep> steps) {
        return new GlobalSearchAnswer(false, answer, null, steps);
    }

    public static GlobalSearchAnswer noCommunitiesYet() {
        return new GlobalSearchAnswer(true, null,
                "This corpus has no Communities to search. Either community detection is still running, or no "
                        + "group of related entities reached the minimum Community size of 3 entities. Try Global "
                        + "Search again once ingestion completes, or use Local Search for specific entities.",
                List.of());
    }
}
