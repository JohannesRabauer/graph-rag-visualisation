package com.graphraglens.adapter.parsing;

import com.graphraglens.core.port.DocumentParserPort;

import java.nio.charset.StandardCharsets;

/**
 * Handles plain-text ({@code .txt}) uploads.
 */
public class PlainTextDocumentParserAdapter implements DocumentParserPort {

    private static final String SUPPORTED_EXTENSION = ".txt";

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(SUPPORTED_EXTENSION);
    }

    @Override
    public String extract(String filename, byte[] content) {
        if (content == null) {
            return "";
        }
        return new String(content, StandardCharsets.UTF_8);
    }
}
