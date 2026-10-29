package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.domain.UnreadableDocumentException;
import dev.rabauer.graphrag.core.port.DocumentParserPort;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of a {@link DocumentParserPort}: {@code supports} decides by
 * filename alone, is case-insensitive on the extension and is {@code false}
 * for a {@code null} or foreign filename; {@code extract} turns the bytes of a
 * supported file into text, gives the empty string (not {@code null}, no
 * exception) for {@code null} or empty content, is stable across parses of the
 * same bytes, and signals content that cannot be read with the core's
 * {@link UnreadableDocumentException} carrying the filename (the failure mode
 * {@code IngestCorpus} documents). Extend it and implement the hooks.
 */
public abstract class DocumentParserPortContract {

    /** The parser under test. */
    protected abstract DocumentParserPort parser();

    /** A lower-case filename the parser supports, for example {@code notes.txt}. */
    protected abstract String supportedFilename();

    /** A filename the parser does not support. */
    protected abstract String unsupportedFilename();

    /** Bytes of a valid document of the supported type whose text contains {@link #expectedText()}. */
    protected abstract byte[] validContent();

    /** A piece of text that the parse of {@link #validContent()} must contain. */
    protected abstract String expectedText();

    /**
     * Bytes that claim to be of the supported type but cannot be read, or
     * {@code null} when the format has no such content (the unreadable test
     * is then skipped).
     */
    protected byte[] unreadableContent() {
        return null;
    }

    @Test
    void supportsItsExtensionCaseInsensitively() {
        DocumentParserPort parser = parser();
        String name = supportedFilename();

        assertTrue(parser.supports(name), name);
        assertTrue(parser.supports(name.toUpperCase(Locale.ROOT)), name.toUpperCase(Locale.ROOT));
        int dot = name.lastIndexOf('.');
        String mixed = name.substring(0, dot + 1) + name.substring(dot + 1, dot + 2).toUpperCase(Locale.ROOT)
                + name.substring(dot + 2);
        assertTrue(parser.supports(mixed), mixed);
    }

    @Test
    void doesNotSupportAForeignExtensionOrANullFilename() {
        DocumentParserPort parser = parser();

        assertFalse(parser.supports(unsupportedFilename()), unsupportedFilename());
        assertFalse(parser.supports(null), "null filename");
        assertFalse(parser.supports(""), "empty filename");
    }

    @Test
    void extractsTheTextOfAValidDocument() {
        String text = parser().extract(supportedFilename(), validContent());

        assertNotNull(text);
        assertTrue(text.contains(expectedText()), () -> "expected '" + expectedText() + "' in '" + text + "'");
    }

    @Test
    void nullAndEmptyContentGiveTheEmptyString() {
        DocumentParserPort parser = parser();

        assertEquals("", parser.extract(supportedFilename(), null), "null content");
        assertEquals("", parser.extract(supportedFilename(), new byte[0]), "empty content");
    }

    @Test
    void theSameBytesGiveTheSameTextEveryTime() {
        DocumentParserPort parser = parser();

        String first = parser.extract(supportedFilename(), validContent());
        String second = parser.extract(supportedFilename(), validContent());

        assertEquals(first, second);
    }

    @Test
    void unreadableContentThrowsTheCoresUnreadableDocumentException() {
        byte[] unreadable = unreadableContent();
        if (unreadable == null) {
            return;
        }
        UnreadableDocumentException failure = assertThrows(UnreadableDocumentException.class,
                () -> parser().extract(supportedFilename(), unreadable));

        assertEquals(supportedFilename(), failure.filename());
    }
}
