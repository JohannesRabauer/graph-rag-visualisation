package io.graphrag.core.domain;

/**
 * Signals that an uploaded file's extension is not supported by any
 * registered {@code DocumentParserPort}. Carries the rejected filename so
 * callers can compose a plain-language message — no HTTP status concept
 * leaks into {@code graphrag-core} (AD-1); it is {@code graphrag-web}'s job
 * to translate this into a 400 response.
 */
public class UnsupportedFileTypeException extends RuntimeException {

    private final String filename;

    public UnsupportedFileTypeException(String filename) {
        super("Unsupported file type: \"" + filename + "\". Only .txt files are supported.");
        this.filename = filename;
    }

    public String filename() {
        return filename;
    }
}
