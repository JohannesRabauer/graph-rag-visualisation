package com.graphraglens.core.domain;

/**
 * Signals that a document was selected successfully but could not be read or
 * converted into usable text for later graph construction.
 */
public class UnreadableDocumentException extends RuntimeException {

    private final String filename;

    public UnreadableDocumentException(String filename, String message) {
        super(message);
        this.filename = filename;
    }

    public String filename() {
        return filename;
    }
}
