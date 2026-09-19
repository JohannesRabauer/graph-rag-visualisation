package com.graphraglens.adapter.parsing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfDocumentParserAdapterTest {

    private final PdfDocumentParserAdapter adapter = new PdfDocumentParserAdapter();

    @Test
    void supportsPdfExtensionCaseInsensitively() {
        assertTrue(adapter.supports("notes.pdf"));
        assertTrue(adapter.supports("NOTES.PDF"));
    }

    @Test
    void rejectsNonPdfExtensions() {
        assertFalse(adapter.supports("notes.txt"));
        assertFalse(adapter.supports("notes.docx"));
        assertFalse(adapter.supports(null));
    }

    @Test
    void extractsTextFromPdfBytes() throws IOException {
        byte[] pdfBytes = createPdfBytes("Hello from PDF");

        String extracted = adapter.extract("notes.pdf", pdfBytes);

        assertTrue(extracted.contains("Hello from PDF"));
    }

    private byte[] createPdfBytes(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(100, 700);
                contentStream.showText(text);
                contentStream.endText();
            }

            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }
}
