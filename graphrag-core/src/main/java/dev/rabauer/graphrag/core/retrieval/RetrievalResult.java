package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.RetrievalTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a retrieval-only Local, Global or DRIFT query: the assembled
 * context items and the full trace, without any synthesized answer. Plain,
 * JSON-serialisable values only (see the trace schema in
 * {@code graphrag-core/README.md}).
 *
 * @param mode     which retrieval ran
 * @param question the question as asked
 * @param corpusId the corpus searched
 * @param status   {@link Status#MATCHED}, {@link Status#NO_MATCH} or
 *                 {@link Status#NO_COMMUNITIES}
 * @param reason   a plain-language explanation when not matched, else {@code ""}
 * @param items    the context items, numbered {@code 1..n}
 * @param trace    every step in touch order, including steps that are not
 *                 items (DRIFT's {@code SUB_QUESTION_SPAWNED})
 * @param warnings visible, non-fatal problems (for example a sub-question
 *                 derivation that failed and fell back); empty when none
 */
public record RetrievalResult(Mode mode, String question, String corpusId, Status status, String reason,
                              List<RetrievedItem> items, RetrievalTrace trace, List<String> warnings) {

    public RetrievalResult {
        question = question == null ? "" : question;
        corpusId = corpusId == null ? "" : corpusId;
        status = status == null ? Status.NO_MATCH : status;
        reason = reason == null ? "" : reason;
        items = items == null ? List.of() : List.copyOf(items);
        trace = trace == null ? new RetrievalTrace("", List.of()) : trace;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** A result without items. */
    public static RetrievalResult empty(Mode mode, String question, String corpusId, Status status, String reason,
                                        List<RetrievalStep> steps, List<String> warnings) {
        return new RetrievalResult(mode, question, corpusId, status, reason, List.of(),
                new RetrievalTrace("", steps), warnings);
    }

    /** Whether any context was found. */
    public boolean matched() {
        return status == Status.MATCHED;
    }

    /**
     * The items as the numbered context {@code LlmPort.synthesizeAnswer}
     * takes, for callers that synthesize themselves.
     */
    public List<ContextItem> toContextItems() {
        List<ContextItem> context = new ArrayList<>();
        for (RetrievedItem item : items) {
            context.add(new ContextItem(item.number(), item.kind(), item.text(),
                    item.kind() == RetrievalStep.Kind.TEXT_UNIT ? item.identifier() : null));
        }
        return List.copyOf(context);
    }

    /** Which retrieval produced a result. */
    public enum Mode {
        LOCAL,
        GLOBAL,
        DRIFT
    }

    /** Whether a retrieval found context. */
    public enum Status {
        /** At least one item was retrieved. */
        MATCHED,
        /** Nothing in the graph matched the question. */
        NO_MATCH,
        /** Global or DRIFT retrieval: the corpus has no Communities. */
        NO_COMMUNITIES
    }
}
