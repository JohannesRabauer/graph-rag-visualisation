package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.UnsupportedFileTypeException;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.domain.UploadedFile;
import com.graphraglens.core.port.DocumentParserPort;

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

    public Corpus ingest(List<UploadedFile> files) {
        List<UploadedDocument> documents = files.stream()
                .map(this::parseDocument)
                .toList();
        return new Corpus(UUID.randomUUID().toString(), documents);
    }

    private UploadedDocument parseDocument(UploadedFile file) {
        return parsers.stream()
                .filter(parser -> parser.supports(file.filename()))
                .findFirst()
                .orElseThrow(() -> new UnsupportedFileTypeException(file.filename()))
                .parse(file);
    }
}
