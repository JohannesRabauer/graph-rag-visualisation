package com.graphraglens.adapter.parsing;

import com.graphraglens.core.domain.PdfExtractionException;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.domain.UploadedFile;
import com.graphraglens.core.port.DocumentParserPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * PDF parser adapter for Story 2.2 upload validation.
 */
public class PdfDocumentParserAdapter implements DocumentParserPort {

    private static final String SUPPORTED_EXTENSION = ".pdf";

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(SUPPORTED_EXTENSION);
    }

    @Override
    public UploadedDocument parse(UploadedFile file) {
        try (PDDocument document = Loader.loadPDF(file.bytes())) {
            String extracted = new PDFTextStripper().getText(document);
            if (extracted == null || extracted.isBlank()) {
                throw new PdfExtractionException(file.filename());
            }
            return new UploadedDocument(file.filename(), extracted);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse uploaded PDF: " + file.filename(), e);
        }
    }
}
