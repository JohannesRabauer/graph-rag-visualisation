package com.graphraglens.core.domain;

/**
 * Signals that an uploaded PDF contained no extractable text.
 */
public class PdfExtractionException extends RuntimeException {

    private final String filename;

    public PdfExtractionException(String filename) {
        super("No text could be read from \"" + filename + "\". Is it a scanned image PDF?");
        this.filename = filename;
    }

    public String filename() {
        return filename;
    }
}
