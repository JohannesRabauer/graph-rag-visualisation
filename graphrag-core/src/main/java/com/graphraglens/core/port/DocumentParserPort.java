package com.graphraglens.core.port;

import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.domain.UploadedFile;

/**
 * Port for parsing source documents (e.g. PDF, text) during ingestion.
 *
 * <p>Implementations declare which filenames they can handle via {@link
 * #supports(String)}; {@code IngestCorpus} dispatches each uploaded file to
 * one matching parser for text extraction/validation.
 */
public interface DocumentParserPort {

    /**
     * @param filename the uploaded file's original filename
     * @return true iff this adapter can handle a file with that filename
     */
    boolean supports(String filename);

    /**
     * Parses and validates one uploaded file once dispatch has selected this
     * parser.
     *
     * @param file uploaded filename + bytes
     * @return normalized uploaded document for ingestion
     */
    UploadedDocument parse(UploadedFile file);
}
