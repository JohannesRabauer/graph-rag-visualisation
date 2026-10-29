package dev.rabauer.graphrag.adapter.parsing;

import dev.rabauer.graphrag.core.domain.UnreadableDocumentException;
import dev.rabauer.graphrag.core.port.DocumentParserPort;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Handles {@code .md} and {@code .markdown} uploads: the Markdown is parsed
 * with commonmark-java (BSD-2-Clause) and flattened to the text a reader would
 * see. Markup characters ({@code #}, {@code *}, link and image syntax, HTML)
 * disappear; link text and image alt text stay, code (spans and fenced or
 * indented blocks) stays as its text, headings and paragraphs are separated by
 * blank lines and every list item is its own line.
 *
 * <p>The extracted text is a plain string: headings are not kept as separate
 * locators, because {@link DocumentParserPort#extract} returns only text (the
 * PDF adapter keeps no page structure either) and {@code SourceLocator} is a
 * line range, which would not match the stripped text.
 */
public class MarkdownDocumentParserAdapter implements DocumentParserPort {

    private static final Parser PARSER = Parser.builder().build();

    @Override
    public boolean supports(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    @Override
    public String extract(String filename, byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }
        String markdown = decode(filename, content);
        StringBuilder out = new StringBuilder();
        for (Node block = PARSER.parse(markdown).getFirstChild(); block != null; block = block.getNext()) {
            appendBlock(block, out);
        }
        return out.toString().strip();
    }

    private static String decode(String filename, byte[] content) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            String text = decoded.toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new UnreadableDocumentException(filename,
                    "That file doesn't look like Markdown text — is it a binary file renamed to .md?");
        }
    }

    /** Appends a block-level node followed by a blank line. */
    private static void appendBlock(Node node, StringBuilder out) {
        if (node instanceof FencedCodeBlock code) {
            appendParagraph(code.getLiteral(), out);
        } else if (node instanceof IndentedCodeBlock code) {
            appendParagraph(code.getLiteral(), out);
        } else if (node instanceof Heading || node instanceof Paragraph) {
            StringBuilder inline = new StringBuilder();
            appendInline(node, inline);
            appendParagraph(inline.toString(), out);
        } else if (node instanceof BulletList || node instanceof OrderedList) {
            for (Node item = node.getFirstChild(); item != null; item = item.getNext()) {
                appendListItem(item, out);
            }
            out.append('\n');
        } else {
            // Block quotes and other containers: keep whatever text they hold.
            // Thematic breaks and raw HTML blocks have no children and vanish.
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                appendBlock(child, out);
            }
        }
    }

    private static void appendListItem(Node item, StringBuilder out) {
        if (!(item instanceof ListItem)) {
            return;
        }
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof BulletList || child instanceof OrderedList) {
                for (Node nested = child.getFirstChild(); nested != null; nested = nested.getNext()) {
                    appendListItem(nested, out);
                }
            } else if (child instanceof Paragraph || child instanceof Heading) {
                StringBuilder inline = new StringBuilder();
                appendInline(child, inline);
                out.append(inline.toString().strip()).append('\n');
            } else if (child instanceof FencedCodeBlock code) {
                out.append(stripTrailing(code.getLiteral())).append('\n');
            } else if (child instanceof IndentedCodeBlock code) {
                out.append(stripTrailing(code.getLiteral())).append('\n');
            }
        }
    }

    private static void appendParagraph(String text, StringBuilder out) {
        String trimmed = stripTrailing(text).strip();
        if (!trimmed.isEmpty()) {
            out.append(trimmed).append("\n\n");
        }
    }

    private static String stripTrailing(String text) {
        return text == null ? "" : text.stripTrailing();
    }

    /** Flattens the inline children of a node to their visible text. */
    private static void appendInline(Node parent, StringBuilder out) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Text text) {
                out.append(text.getLiteral());
            } else if (node instanceof Code code) {
                out.append(code.getLiteral());
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                out.append('\n');
            } else {
                // Emphasis, strong, link, image: keep the visible children and
                // drop the markup and the URL. Inline HTML has no children.
                appendInline(node, out);
            }
        }
    }
}
