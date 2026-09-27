package com.graphraglens.adapter.parsing;

import io.graphrag.core.port.DocumentParserPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Handles PDF uploads by extracting text from the PDF payload before it is kept
 * in the Corpus. A PDF that produces no extractable text is rejected by the web
 * layer as a user-visible error, while the parser itself remains focused on the
 * actual conversion boundary.
 */
public class PdfDocumentParserAdapter implements DocumentParserPort {

    private static final String SUPPORTED_EXTENSION = ".pdf";

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(SUPPORTED_EXTENSION);
    }

    @Override
    public String extract(String filename, byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }

        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract text from PDF: " + filename, e);
        }
    }
}
