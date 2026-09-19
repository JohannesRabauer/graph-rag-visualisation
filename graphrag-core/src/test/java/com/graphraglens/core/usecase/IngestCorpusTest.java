package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.UnsupportedFileTypeException;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.domain.UploadedFile;
import com.graphraglens.core.port.DocumentParserPort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestCorpusTest {

    private final DocumentParserPort txtOnlyParser = new DocumentParserPort() {
        @Override
        public boolean supports(String filename) {
            return filename != null && filename.endsWith(".txt");
        }

        @Override
        public UploadedDocument parse(UploadedFile file) {
            return new UploadedDocument(file.filename(), new String(file.bytes(), StandardCharsets.UTF_8));
        }
    };

    @Test
    void ingestsAllDocumentsWhenEverySupportedByAParser() {
        IngestCorpus ingestCorpus = new IngestCorpus(List.of(txtOnlyParser));
        UploadedFile a = new UploadedFile("a.txt", "hello".getBytes(StandardCharsets.UTF_8));
        UploadedFile b = new UploadedFile("b.txt", "world".getBytes(StandardCharsets.UTF_8));

        Corpus corpus = ingestCorpus.ingest(List.of(a, b));

        assertFalse(corpus.id().isBlank());
        assertEquals(List.of("a.txt", "b.txt"), corpus.documentNames());
        assertEquals(List.of("hello", "world"), corpus.documents().stream().map(UploadedDocument::content).toList());
        assertEquals(2, corpus.documentCount());
    }

    @Test
    void generatesADifferentIdForEachCorpus() {
        IngestCorpus ingestCorpus = new IngestCorpus(List.of(txtOnlyParser));
        UploadedFile doc = new UploadedFile("a.txt", "hello".getBytes(StandardCharsets.UTF_8));

        Corpus first = ingestCorpus.ingest(List.of(doc));
        Corpus second = ingestCorpus.ingest(List.of(doc));

        assertNotEquals(first.id(), second.id());
    }

    @Test
    void throwsUnsupportedFileTypeExceptionForTheFirstUnsupportedFile() {
        IngestCorpus ingestCorpus = new IngestCorpus(List.of(txtOnlyParser));
        UploadedFile supported = new UploadedFile("a.txt", "hello".getBytes(StandardCharsets.UTF_8));
        UploadedFile unsupported = new UploadedFile("b.pdf", "world".getBytes(StandardCharsets.UTF_8));

        UnsupportedFileTypeException exception = assertThrows(UnsupportedFileTypeException.class,
                () -> ingestCorpus.ingest(List.of(supported, unsupported)));

        assertTrue(exception.getMessage().contains("b.pdf"));
        assertEquals("b.pdf", exception.filename());
    }

    @Test
    void supportedIffAnyRegisteredParserSupportsIt() {
        DocumentParserPort neverSupports = new DocumentParserPort() {
            @Override
            public boolean supports(String filename) {
                return false;
            }

            @Override
            public UploadedDocument parse(UploadedFile file) {
                throw new AssertionError("parse should not be called");
            }
        };
        IngestCorpus ingestCorpus = new IngestCorpus(List.of(neverSupports, txtOnlyParser));
        UploadedFile doc = new UploadedFile("a.txt", "hello".getBytes(StandardCharsets.UTF_8));

        Corpus corpus = ingestCorpus.ingest(List.of(doc));

        assertEquals(List.of("a.txt"), corpus.documentNames());
    }
}
