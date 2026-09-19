package com.graphraglens.web;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.UnsupportedFileTypeException;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.usecase.IngestCorpus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Upload entry point for a plain text Corpus (Story 2.1). Validates each
 * uploaded file via {@link IngestCorpus} (which in turn dispatches to every
 * registered {@code DocumentParserPort}) and registers the resulting {@link
 * Corpus} in the {@link CorpusStore}. No extraction, Neo4j write, or SSE
 * progress stream happens here yet — that is Story 2.4/2.5.
 */
@RestController
public class CorpusController {

    private final IngestCorpus ingestCorpus;
    private final CorpusStore corpusStore;

    public CorpusController(IngestCorpus ingestCorpus, CorpusStore corpusStore) {
        this.ingestCorpus = ingestCorpus;
        this.corpusStore = corpusStore;
    }

    @PostMapping("/api/corpora")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "No files were provided."));
        }

        List<UploadedDocument> documents = files.stream()
                .map(this::toUploadedDocument)
                .toList();

        Corpus corpus = ingestCorpus.ingest(documents);
        corpusStore.put(corpus);

        Map<String, Object> body = Map.of(
                "corpusId", corpus.id(),
                "documentNames", corpus.documentNames(),
                "documentCount", corpus.documentCount());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedFileType(UnsupportedFileTypeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> handleUncheckedIOException(UncheckedIOException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to read an uploaded file. Please try again."));
    }

    private UploadedDocument toUploadedDocument(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "(unnamed file)";
        }
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            return new UploadedDocument(filename, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file: " + filename, e);
        }
    }
}
