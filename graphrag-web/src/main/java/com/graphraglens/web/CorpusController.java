package com.graphraglens.web;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.IngestionProgressEvent;
import com.graphraglens.core.domain.PdfExtractionException;
import com.graphraglens.core.domain.UnsupportedFileTypeException;
import com.graphraglens.core.domain.UploadedFile;
import com.graphraglens.core.usecase.IngestCorpus;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Upload + demo dataset + progress endpoints for Epic 2.
 */
@RestController
public class CorpusController {

    private final IngestCorpus ingestCorpus;
    private final CorpusStore corpusStore;
    private final DemoDatasetService demoDatasetService;
    private final CorpusIngestionOrchestrator ingestionOrchestrator;
    private final IngestionProgressBroker progressBroker;

    public CorpusController(
            IngestCorpus ingestCorpus,
            CorpusStore corpusStore,
            DemoDatasetService demoDatasetService,
            CorpusIngestionOrchestrator ingestionOrchestrator,
            IngestionProgressBroker progressBroker) {
        this.ingestCorpus = ingestCorpus;
        this.corpusStore = corpusStore;
        this.demoDatasetService = demoDatasetService;
        this.ingestionOrchestrator = ingestionOrchestrator;
        this.progressBroker = progressBroker;
    }

    @PostMapping("/api/corpora")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "No files were provided."));
        }

        List<UploadedFile> documents = files.stream()
                .map(this::toUploadedDocument)
                .toList();

        Corpus corpus = ingestCorpus.ingest(documents);
        corpusStore.put(corpus);
        ingestionOrchestrator.start(corpus);

        return ResponseEntity.status(HttpStatus.CREATED).body(successBody(corpus, null));
    }

    @PostMapping("/api/corpora/demo")
    public ResponseEntity<Map<String, Object>> useDemoDataset() {
        Corpus corpus = ingestCorpus.ingest(demoDatasetService.loadDemoCorpus());
        corpusStore.put(corpus);
        ingestionOrchestrator.start(corpus);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(successBody(corpus, DemoDatasetService.DEMO_DATASET_DISPLAY_NAME));
    }

    @GetMapping(path = "/api/corpora/{corpusId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable("corpusId") String corpusId) {
        SseEmitter emitter = progressBroker.subscribe(corpusId);
        progressBroker.publish(corpusId, new IngestionProgressEvent(
                "progress-subscribed",
                Map.of("corpusId", corpusId)));
        return emitter;
    }

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedFileType(UnsupportedFileTypeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(PdfExtractionException.class)
    public ResponseEntity<Map<String, String>> handlePdfExtractionError(PdfExtractionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> handleUncheckedIOException(UncheckedIOException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to read an uploaded file. Please try again."));
    }

    private UploadedFile toUploadedDocument(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "(unnamed file)";
        }
        try {
            return new UploadedFile(filename, file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file: " + filename, e);
        }
    }

    private Map<String, Object> successBody(Corpus corpus, String displayName) {
        if (displayName == null) {
            return Map.of(
                    "corpusId", corpus.id(),
                    "documentNames", corpus.documentNames(),
                    "documentCount", corpus.documentCount());
        }
        return Map.of(
                "corpusId", corpus.id(),
                "documentNames", corpus.documentNames(),
                "documentCount", corpus.documentCount(),
                "displayName", displayName);
    }
}
