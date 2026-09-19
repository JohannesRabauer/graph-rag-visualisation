package com.graphraglens.adapter.parsing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainTextDocumentParserAdapterTest {

    private final PlainTextDocumentParserAdapter adapter = new PlainTextDocumentParserAdapter();

    @Test
    void supportsLowercaseTxtExtension() {
        assertTrue(adapter.supports("notes.txt"));
    }

    @Test
    void supportsTxtExtensionCaseInsensitively() {
        assertTrue(adapter.supports("NOTES.TXT"));
        assertTrue(adapter.supports("Notes.Txt"));
    }

    @Test
    void rejectsNonTxtExtensions() {
        assertFalse(adapter.supports("notes.pdf"));
        assertFalse(adapter.supports("notes.docx"));
        assertFalse(adapter.supports("notes"));
    }

    @Test
    void rejectsNullFilename() {
        assertFalse(adapter.supports(null));
    }
}
