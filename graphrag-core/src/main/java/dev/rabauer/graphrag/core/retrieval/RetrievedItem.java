package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.Attributes;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SourceLocator;

import java.util.Map;
import java.util.Objects;

/**
 * One item of a retrieval-only context: an Entity, a Relationship, a Text
 * Unit or a Community, as handed to a consumer (for example a coding agent)
 * instead of a synthesized answer. Items are numbered {@code 1..n} in the
 * order they were added, which is also their order in the trace.
 *
 * @param number     the item's 1-based position
 * @param kind       {@code ENTITY}, {@code RELATIONSHIP}, {@code TEXT_UNIT} or {@code COMMUNITY}
 * @param identifier the element's identity: Entity identity
 *                   ({@code name::type}, lower case), edge id
 *                   ({@code sourceIdentity->TYPE->targetIdentity}), Text Unit
 *                   id or Community id
 * @param label      a short display label (name, edge, excerpt, title)
 * @param text       the full text a model would read (description, passage,
 *                   summary)
 * @param locator    where the element lives ({@code path:start-end}); null when unknown
 * @param attributes the element's attributes; never null
 * @param score      why it was included: the seed score, the Relationship
 *                   weight, the Text Unit rank score or the Community score
 * @param hop        the hop it was reached at ({@code 0} for seeds and
 *                   Communities); {@code -1} for Text Units
 */
public record RetrievedItem(int number, RetrievalStep.Kind kind, String identifier, String label, String text,
                            SourceLocator locator, Map<String, String> attributes, double score, int hop) {

    public RetrievedItem {
        Objects.requireNonNull(kind, "kind");
        identifier = identifier == null ? "" : identifier;
        label = label == null ? "" : label;
        text = text == null ? "" : text;
        attributes = Attributes.normalize(attributes);
    }
}
