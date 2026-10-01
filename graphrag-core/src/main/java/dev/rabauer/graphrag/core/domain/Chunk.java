package dev.rabauer.graphrag.core.domain;

/**
 * One retrieval-unit slice of a document's text, produced by {@link
 * dev.rabauer.graphrag.core.usecase.ConstructVectorIndex} while chunking a corpus.
 *
 * @param id           unique identifier for this chunk; never null
 * @param corpusId     the corpus this chunk was produced from; never null
 * @param ordinal      this chunk's position among all chunks of the corpus
 *                     (corpus-wide, across documents), starting at 0
 * @param text         the chunk's text content; never null
 * @param documentName the name of the document the chunk was cut from;
 *                     {@code ""} when unknown (e.g. chunks of older corpora)
 */
public record Chunk(String id, String corpusId, int ordinal, String text, String documentName) {

    public Chunk {
        documentName = documentName == null ? "" : documentName;
    }

    /** A chunk whose document is unknown ({@code documentName} is {@code ""}). */
    public Chunk(String id, String corpusId, int ordinal, String text) {
        this(id, corpusId, ordinal, text, "");
    }
}
