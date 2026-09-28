package com.graphraglens.adapter.parsing;

import io.graphrag.core.domain.UnreadableDocumentException;
import io.graphrag.core.port.DocumentParserPort;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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

    /**
     * A {@code .txt} extension only proves the filename, not the content — a
     * binary file renamed to {@code .txt} would otherwise decode via the
     * lenient {@code new String(bytes, UTF_8)} path (which silently swaps
     * invalid byte sequences for U+FFFD) and reach LLM extraction as
     * garbled text. Decoding strictly here rejects that up front with a
     * clear error, the same way {@code PdfDocumentParserAdapter} already
     * rejects an unreadable PDF.
     */
    @Override
    public String extract(String filename, byte[] content) {
        if (content == null) {
            return "";
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw new UnreadableDocumentException(filename,
                    "That file doesn't look like plain text — is it a binary file renamed to .txt?");
        }
    }
}
