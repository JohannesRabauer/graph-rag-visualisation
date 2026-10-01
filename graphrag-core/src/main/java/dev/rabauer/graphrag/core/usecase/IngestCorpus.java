package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.UnsupportedFileTypeException;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import dev.rabauer.graphrag.core.port.DocumentParserPort;

import java.util.List;
import java.util.UUID;

/**
 * Validates and ingests an uploaded Corpus.
 *
 * <p>Every document's filename is checked against every registered {@link
 * DocumentParserPort} (via {@link DocumentParserPort#supports(String)}); the
 * first unsupported filename encountered aborts ingestion with an {@link
 * UnsupportedFileTypeException}. This is the one place upload validation
 * logic lives — no raw extension checks happen in {@code graphrag-web}
 * (AD-9).
 */
public class IngestCorpus {

    private final List<DocumentParserPort> parsers;

    public IngestCorpus(List<DocumentParserPort> parsers) {
        this.parsers = parsers;
    }

    public Corpus ingest(List<UploadedDocument> documents) {
        for (UploadedDocument document : documents) {
            boolean supported = parsers.stream().anyMatch(parser -> parser.supports(document.filename()));
            if (!supported) {
                throw new UnsupportedFileTypeException(document.filename());
            }
        }
        return new Corpus(UUID.randomUUID().toString(), documents);
    }
}
