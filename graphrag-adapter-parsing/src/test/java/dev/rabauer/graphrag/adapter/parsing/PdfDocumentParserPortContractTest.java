package dev.rabauer.graphrag.adapter.parsing;

import dev.rabauer.graphrag.core.port.DocumentParserPort;
import dev.rabauer.graphrag.testkit.DocumentParserPortContract;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

class PdfDocumentParserPortContractTest extends DocumentParserPortContract {

    @Override
    protected DocumentParserPort parser() {
        return new PdfDocumentParserAdapter();
    }

    @Override
    protected String supportedFilename() {
        return "notes.pdf";
    }

    @Override
    protected String unsupportedFilename() {
        return "notes.txt";
    }

    @Override
    protected byte[] validContent() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(100, 700);
                content.showText("Hello from PDF");
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected String expectedText() {
        return "Hello from PDF";
    }

    @Override
    protected byte[] unreadableContent() {
        return "this is not a pdf".getBytes(StandardCharsets.UTF_8);
    }
}
