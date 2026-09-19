package com.graphraglens.core.port;

import java.nio.charset.StandardCharsets;

/**
 * Port for parsing source documents (e.g. PDF, text) during ingestion.
 *
 * <p>Implementations declare which filenames they can handle via {@link
 * #supports(String)} and then extract text from the raw file bytes when the
 * upload is accepted. The raw bytes are intentionally kept web-layer specific;
 * the parser is the boundary responsible for turning file content into the text
 * the core later ingests.
 */
public interface DocumentParserPort {

    /**
     * @param filename the uploaded file's original filename
     * @return true iff this adapter can handle a file with that filename
     */
    boolean supports(String filename);

    /**
     * @param filename the uploaded file's original filename
     * @param content the raw file bytes in their original format
     * @return the document text in a plain-string form suitable for downstream
     *         graph construction
     */
    default String extract(String filename, byte[] content) {
        if (content == null) {
            return "";
        }
        return new String(content, StandardCharsets.UTF_8);
    }
}
