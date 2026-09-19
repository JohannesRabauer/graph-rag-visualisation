package com.graphraglens.core.port;

/**
 * Port for parsing source documents (e.g. PDF, text) during ingestion.
 *
 * <p>Implementations declare which filenames they can handle via {@link
 * #supports(String)}; {@code IngestCorpus} dispatches to the first matching
 * parser. Actual text extraction (a {@code parse(...)} method) is out of
 * scope for this story and arrives with Story 2.4.
 */
public interface DocumentParserPort {

    /**
     * @param filename the uploaded file's original filename
     * @return true iff this adapter can handle a file with that filename
     */
    boolean supports(String filename);
}
