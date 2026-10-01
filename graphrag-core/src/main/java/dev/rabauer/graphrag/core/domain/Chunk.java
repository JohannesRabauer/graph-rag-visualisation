package dev.rabauer.graphrag.core.domain;

/**
 * One retrieval-unit slice of a document's text, produced by {@link
 * dev.rabauer.graphrag.core.usecase.ConstructVectorIndex} while chunking a corpus.
 *
 * @param id       unique identifier for this chunk; never null
 * @param corpusId the corpus this chunk was produced from; never null
 * @param ordinal  this chunk's position among the other chunks of the same
 *                 document, starting at 0
 * @param text     the chunk's text content; never null
 */
public record Chunk(String id, String corpusId, int ordinal, String text) {
}
