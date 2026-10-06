package dev.rabauer.graphrag.core.domain;

import java.util.Map;

/**
 * One source passage a synthesized answer cites (Story 15.2). The answer's
 * {@code [i]} marker refers to the {@code i}-th citation.
 *
 * @param textUnitId   the cited Text Unit's id; never null
 * @param documentName the document the passage was cut from
 * @param excerpt      the first 200 characters of the passage,
 *                     whitespace-collapsed, with "…" when cut
 * @param locator      the passage's verifiable location (for code:
 *                     {@code path:startLine-endLine}); null when unknown
 * @param attributes   the Text Unit's attributes; never null
 */
public record Citation(String textUnitId, String documentName, String excerpt, SourceLocator locator,
                       Map<String, String> attributes) {

    /** A Citation without locator and attributes. */
    public Citation(String textUnitId, String documentName, String excerpt) {
        this(textUnitId, documentName, excerpt, null, Map.of());
    }

    public Citation {
        textUnitId = textUnitId == null ? "" : textUnitId;
        documentName = documentName == null ? "" : documentName;
        excerpt = excerpt == null ? "" : excerpt;
        attributes = Attributes.normalize(attributes);
    }
}
