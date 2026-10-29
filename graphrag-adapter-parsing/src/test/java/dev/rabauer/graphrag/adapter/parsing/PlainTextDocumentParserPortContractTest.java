package dev.rabauer.graphrag.adapter.parsing;

import dev.rabauer.graphrag.core.port.DocumentParserPort;
import dev.rabauer.graphrag.testkit.DocumentParserPortContract;

import java.nio.charset.StandardCharsets;

class PlainTextDocumentParserPortContractTest extends DocumentParserPortContract {

    @Override
    protected DocumentParserPort parser() {
        return new PlainTextDocumentParserAdapter();
    }

    @Override
    protected String supportedFilename() {
        return "notes.txt";
    }

    @Override
    protected String unsupportedFilename() {
        return "notes.pdf";
    }

    @Override
    protected byte[] validContent() {
        return "Sherlock Holmes lived at 221B Baker Street.".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected String expectedText() {
        return "221B Baker Street";
    }

    @Override
    protected byte[] unreadableContent() {
        return new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, (byte) 0xFF, (byte) 0xFE, 0x00, 0x01 };
    }
}
