package dev.rabauer.graphrag.adapter.parsing;

import dev.rabauer.graphrag.core.domain.UnreadableDocumentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void extractsValidUtf8Text() {
        String text = "Sherlock Holmes lived at 221B Baker Street.";
        String extracted = adapter.extract("notes.txt", text.getBytes(StandardCharsets.UTF_8));
        assertEquals(text, extracted);
    }

    @Test
    void returnsEmptyStringForNullContent() {
        assertEquals("", adapter.extract("notes.txt", null));
    }

    @Test
    void rejectsBinaryContentRenamedToTxt() {
        // A PNG file signature followed by non-UTF-8 bytes — not decodable text.
        byte[] binary = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, (byte) 0xFF, (byte) 0xFE, (byte) 0x00, 0x01 };
        UnreadableDocumentException ex = assertThrows(UnreadableDocumentException.class,
                () -> adapter.extract("image.txt", binary));
        assertEquals("image.txt", ex.filename());
    }
}
