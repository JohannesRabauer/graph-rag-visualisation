package com.graphraglens.adapter.parsing;

import com.graphraglens.core.domain.PdfExtractionException;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.domain.UploadedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfDocumentParserAdapterTest {

    private final PdfDocumentParserAdapter adapter = new PdfDocumentParserAdapter();

    @Test
    void supportsPdfExtensionCaseInsensitively() {
        assertTrue(adapter.supports("doc.pdf"));
        assertTrue(adapter.supports("DOC.PDF"));
        assertFalse(adapter.supports("doc.txt"));
        assertFalse(adapter.supports(null));
    }

    @Test
    void parseExtractsTextFromPdf() throws IOException {
        UploadedFile file = new UploadedFile("doc.pdf", createPdf("Hello PDF"));

        UploadedDocument parsed = adapter.parse(file);

        assertEquals("doc.pdf", parsed.filename());
        assertTrue(parsed.content().contains("Hello PDF"));
    }

    @Test
    void parseThrowsWhenPdfHasNoExtractableText() throws IOException {
        UploadedFile file = new UploadedFile("empty.pdf", createPdf(""));

        PdfExtractionException ex = assertThrows(PdfExtractionException.class, () -> adapter.parse(file));

        assertEquals("empty.pdf", ex.filename());
    }

    @Test
    void parseThrowsUncheckedIOExceptionForInvalidPdfBytes() {
        UploadedFile file = new UploadedFile("broken.pdf", "not-a-pdf".getBytes());

        assertThrows(UncheckedIOException.class, () -> adapter.parse(file));
    }

    private byte[] createPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);

            if (!text.isEmpty()) {
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(100, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }

            document.save(out);
            return out.toByteArray();
        }
    }
}
