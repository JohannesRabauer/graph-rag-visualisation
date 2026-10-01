package dev.rabauer.graphrag.core.domain;

import java.util.Objects;

/**
 * One numbered item of the bounded context a Local Search answer is
 * synthesized from (Story 15.2): a seed Entity, a Relationship touching a
 * seed, or a Text Unit cited by either. Items are numbered {@code 1..n} in
 * assembly order, which is also their trace-step order, so the LLM's
 * inline {@code [n]} citations can be resolved back to them.
 *
 * @param number     the item's 1-based position in the context
 * @param kind       {@link RetrievalStep.Kind#ENTITY}, {@link RetrievalStep.Kind#RELATIONSHIP},
 *                   {@link RetrievalStep.Kind#TEXT_UNIT}, or (Global and DRIFT
 *                   Search, Story 15.3) {@link RetrievalStep.Kind#COMMUNITY}; never null
 * @param text       the text the prompt shows for this item (description or
 *                   passage); never null
 * @param textUnitId the Text Unit id when {@code kind} is {@code TEXT_UNIT},
 *                   otherwise null
 */
public record ContextItem(int number, RetrievalStep.Kind kind, String text, String textUnitId) {

    public ContextItem {
        Objects.requireNonNull(kind, "kind must not be null");
        text = text == null ? "" : text;
        if (kind != RetrievalStep.Kind.TEXT_UNIT) {
            textUnitId = null;
        }
    }

    public boolean isTextUnit() {
        return kind == RetrievalStep.Kind.TEXT_UNIT;
    }
}
