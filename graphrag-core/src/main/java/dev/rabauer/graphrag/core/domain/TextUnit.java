package dev.rabauer.graphrag.core.domain;

/**
 * One overlapping passage of a document's text, produced by {@link
 * dev.rabauer.graphrag.core.usecase.TextUnitSplitter} as the unit of Knowledge Graph
 * extraction (AD-24): the LLM extracts one Text Unit at a time, and each
 * unit is persisted alongside its extraction result. Distinct from the
 * Vector Baseline's much smaller {@link Chunk}.
 *
 * @param id           stable identifier {@code {corpusId}::doc-{documentIndex}::tu-{ordinal}};
 *                     never null
 * @param corpusId     the corpus this unit was produced from; never null
 * @param documentName the filename of the document this unit was cut from
 * @param ordinal      this unit's position among the units of the same
 *                     document, starting at 0
 * @param text         the passage text; never null
 */
public record TextUnit(String id, String corpusId, String documentName, int ordinal, String text) {
}
