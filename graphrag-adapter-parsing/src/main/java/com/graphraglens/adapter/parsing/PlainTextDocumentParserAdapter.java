package com.graphraglens.adapter.parsing;

import com.graphraglens.core.port.DocumentParserPort;

/**
 * First real {@link DocumentParserPort} implementation: handles plain-text
 * ({@code .txt}) uploads (Story 2.1). PDF support (Story 2.2) is a separate
 * adapter, added later.
 */
public class PlainTextDocumentParserAdapter implements DocumentParserPort {

    private static final String SUPPORTED_EXTENSION = ".txt";

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(SUPPORTED_EXTENSION);
    }
}
