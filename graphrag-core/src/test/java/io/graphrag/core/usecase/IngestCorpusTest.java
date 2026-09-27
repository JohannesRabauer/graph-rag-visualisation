package io.graphrag.core.usecase;

import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.UnsupportedFileTypeException;
import io.graphrag.core.domain.UploadedDocument;
import io.graphrag.core.port.DocumentParserPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestCorpusTest {

    private final DocumentParserPort txtOnlyParser = filename -> filename != null && filename.endsWith(".txt");

    @Test
    void ingestsAllDocumentsWhenEverySupportedByAParser() {
        IngestCorpus ingestCorpus = new IngestCorpus(List.of(txtOnlyParser));
        UploadedDocument a = new UploadedDocument("a.txt", "hello");
        UploadedDocument b = new UploadedDocument("b.txt", "world");

        Corpus corpus = ingestCorpus.ingest(List.of(a, b));

        assertFalse(corpus.id().isBlank());
        assertEquals(List.of(a, b), corpus.documents());
        assertEquals(List.of("a.txt", "b.txt"), corpus.documentNames());
        assertEquals(2, corpus.documentCount());
    }

    @Test
    void generatesADifferentIdForEachCorpus() {
        IngestCorpus ingestCorpus = new IngestCorpus(List.of(txtOnlyParser));
        UploadedDocument doc = new UploadedDocument("a.txt", "hello");

        Corpus first = ingestCorpus.ingest(List.of(doc));
        Corpus second = ingestCorpus.ingest(List.of(doc));

        assertNotEquals(first.id(), second.id());
    }

    @Test
    void throwsUnsupportedFileTypeExceptionForTheFirstUnsupportedFile() {
        IngestCorpus ingestCorpus = new IngestCorpus(List.of(txtOnlyParser));
        UploadedDocument supported = new UploadedDocument("a.txt", "hello");
        UploadedDocument unsupported = new UploadedDocument("b.pdf", "world");

        UnsupportedFileTypeException exception = assertThrows(UnsupportedFileTypeException.class,
                () -> ingestCorpus.ingest(List.of(supported, unsupported)));

        assertTrue(exception.getMessage().contains("b.pdf"));
        assertEquals("b.pdf", exception.filename());
    }

    @Test
    void supportedIffAnyRegisteredParserSupportsIt() {
        DocumentParserPort neverSupports = filename -> false;
        IngestCorpus ingestCorpus = new IngestCorpus(List.of(neverSupports, txtOnlyParser));
        UploadedDocument doc = new UploadedDocument("a.txt", "hello");

        Corpus corpus = ingestCorpus.ingest(List.of(doc));

        assertEquals(List.of("a.txt"), corpus.documentNames());
    }
}
