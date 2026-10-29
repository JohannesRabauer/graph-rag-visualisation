package dev.rabauer.graphrag.adapter.parsing;

import dev.rabauer.graphrag.core.domain.UnreadableDocumentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownDocumentParserAdapterTest {

    private final MarkdownDocumentParserAdapter adapter = new MarkdownDocumentParserAdapter();

    private String extract(String markdown) {
        return adapter.extract("doc.md", markdown.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void supportsMdAndMarkdownCaseInsensitively() {
        assertTrue(adapter.supports("notes.md"));
        assertTrue(adapter.supports("NOTES.MD"));
        assertTrue(adapter.supports("Notes.Markdown"));
        assertFalse(adapter.supports("notes.txt"));
        assertFalse(adapter.supports("notes.pdf"));
        assertFalse(adapter.supports("md"));
        assertFalse(adapter.supports(null));
    }

    @Test
    void headingsLoseTheirHashes() {
        assertEquals("Title\n\nSection\n\nBody text.", extract("# Title\n\n## Section\n\nBody text.\n"));
    }

    @Test
    void emphasisAndStrongMarkersAreRemoved() {
        assertEquals("Some bold and italic and both words.",
                extract("Some **bold** and _italic_ and ***both*** words."));
    }

    @Test
    void linksKeepTheirTextAndDropTheUrl() {
        String text = extract("See [the docs](https://example.com/docs) and ![a diagram](img.png \"t\").");
        assertEquals("See the docs and a diagram.", text);
        assertFalse(text.contains("example.com"));
    }

    @Test
    void codeSpansAndFencedBlocksKeepTheirText() {
        String text = extract("Call `run()` now.\n\n```java\nint x = 1;\nreturn x;\n```\n");
        assertEquals("Call run() now.\n\nint x = 1;\nreturn x;", text);
    }

    @Test
    void listsBecomeOneLinePerItem() {
        assertEquals("one\ntwo\nnested\n\nthree", extract("- one\n- two\n  - nested\n\n1. three\n"));
    }

    @Test
    void htmlCommentsAndThematicBreaksAreDropped() {
        assertEquals("a\n\nb", extract("a\n\n<!-- hidden -->\n\n---\n\nb\n"));
    }

    @Test
    void emptyAndNullContentGiveAnEmptyString() {
        assertEquals("", adapter.extract("empty.md", new byte[0]));
        assertEquals("", adapter.extract("empty.md", null));
        assertEquals("", extract("\n\n  \n"));
    }

    @Test
    void aLeadingByteOrderMarkIsIgnored() {
        assertEquals("Title", extract("﻿# Title"));
    }

    @Test
    void invalidUtf8IsRejectedAsUnreadable() {
        byte[] binary = {(byte) 0xC3, (byte) 0x28, (byte) 0xFF};
        assertThrows(UnreadableDocumentException.class, () -> adapter.extract("bad.md", binary));
    }
}
