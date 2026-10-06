package dev.rabauer.graphrag.core.domain;

import java.util.Map;

/**
 * One source passage: for text corpora an overlapping passage of a
 * document's text, produced by {@link
 * dev.rabauer.graphrag.core.usecase.TextUnitSplitter} as the unit of Knowledge Graph
 * extraction (AD-24); for imported graphs any unit the caller chooses, such
 * as the source of one class or method. Distinct from the Vector Baseline's
 * much smaller {@link Chunk}.
 *
 * @param id           stable identifier; split passages use
 *                     {@code {corpusId}::doc-{documentIndex}::tu-{ordinal}}; never null
 * @param corpusId     the corpus this unit belongs to; never null
 * @param documentName the document (or file) this unit was cut from
 * @param ordinal      this unit's position among the units of the same
 *                     document, starting at 0
 * @param text         the passage text; never null
 * @param attributes   free-form, string-valued facts; never null, sorted by key
 * @param locator      where the passage lives (for code: path and line
 *                     range); null when unknown
 */
public record TextUnit(String id, String corpusId, String documentName, int ordinal, String text,
                       Map<String, String> attributes, SourceLocator locator) {

    /** A Text Unit without attributes and locator. */
    public TextUnit(String id, String corpusId, String documentName, int ordinal, String text) {
        this(id, corpusId, documentName, ordinal, text, Map.of(), null);
    }

    public TextUnit {
        attributes = Attributes.normalize(attributes);
    }

    /** This Text Unit with {@code locator} instead of its own. */
    public TextUnit withLocator(SourceLocator locator) {
        return new TextUnit(id, corpusId, documentName, ordinal, text, attributes, locator);
    }

    /** This Text Unit with {@code attributes} instead of its own. */
    public TextUnit withAttributes(Map<String, String> attributes) {
        return new TextUnit(id, corpusId, documentName, ordinal, text, attributes, locator);
    }
}
