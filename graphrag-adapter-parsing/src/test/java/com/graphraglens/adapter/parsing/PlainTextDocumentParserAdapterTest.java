package com.graphraglens.adapter.parsing;

import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.domain.UploadedFile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void parseDecodesUtf8Text() {
        UploadedFile file = new UploadedFile("notes.txt", "hello".getBytes(StandardCharsets.UTF_8));

        UploadedDocument parsed = adapter.parse(file);

        assertEquals("notes.txt", parsed.filename());
        assertEquals("hello", parsed.content());
    }
}
