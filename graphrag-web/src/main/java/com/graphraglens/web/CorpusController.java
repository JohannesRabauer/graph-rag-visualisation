package com.graphraglens.web;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.domain.RetrievalStep;
import com.graphraglens.core.domain.RetrievalTrace;
import com.graphraglens.core.domain.UnreadableDocumentException;
import com.graphraglens.core.domain.UnsupportedFileTypeException;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.port.DocumentParserPort;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;
import com.graphraglens.core.usecase.AnswerGlobalSearch;
import com.graphraglens.core.usecase.BuildKnowledgeGraph;
import com.graphraglens.core.usecase.DetectCommunities;
import com.graphraglens.core.usecase.GlobalSearchAnswer;
import com.graphraglens.core.usecase.IngestCorpus;
import com.graphraglens.core.usecase.KeywordMatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Upload entry point for a Corpus. It accepts plain text and PDF uploads,
 * validates support with the registered parser adapters, and extracts the text
 * payload each parser is responsible for before storing the resulting Corpus.
 */
@RestController
public class CorpusController {

    private static final String EXTRACTION_FAILURE_MESSAGE =
            "The LLM call failed during knowledge graph extraction. Nothing was retried — try again when ready.";

    private final IngestCorpus ingestCorpus;
    private final CorpusStore corpusStore;
    private final List<DocumentParserPort> documentParsers;
    private final DemoDatasetService demoDatasetService;
    private final CorpusProgressService corpusProgressService;
    private final LlmPort llmPort;
    private final GraphStorePort graphStorePort;
    private final RetrievalTraceStore retrievalTraceStore;

    public CorpusController(IngestCorpus ingestCorpus, CorpusStore corpusStore,
                           List<DocumentParserPort> documentParsers, DemoDatasetService demoDatasetService,
                           CorpusProgressService corpusProgressService, LlmPort llmPort, GraphStorePort graphStorePort,
                           RetrievalTraceStore retrievalTraceStore) {
        this.ingestCorpus = ingestCorpus;
        this.corpusStore = corpusStore;
        this.documentParsers = documentParsers;
        this.demoDatasetService = demoDatasetService;
        this.corpusProgressService = corpusProgressService;
        this.llmPort = llmPort;
        this.graphStorePort = graphStorePort;
        this.retrievalTraceStore = retrievalTraceStore;
    }

    @GetMapping(value = "/api/corpora/{corpusId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable("corpusId") String corpusId) {
        return corpusProgressService.register(corpusId);
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
        corpusProgressService.emit(corpus.id(), "ingestion-started",
                Map.of("message", "Knowledge graph construction started for " + corpus.name()));
        startKnowledgeGraphConstruction(corpus);

        return ResponseEntity.status(HttpStatus.CREATED).body(corpusPayload(corpus));
    }

    @PostMapping("/api/corpora/demo")
    public ResponseEntity<Map<String, Object>> useDemoDataset() {
        Corpus corpus = demoDatasetService.createSherlockCorpus();
        corpusStore.put(corpus);
        corpusProgressService.emit(corpus.id(), "ingestion-started",
                Map.of("message", "Knowledge graph construction started for " + corpus.name()));
        startKnowledgeGraphConstruction(corpus);
        return ResponseEntity.status(HttpStatus.CREATED).body(corpusPayload(corpus));
    }

    @PostMapping("/api/corpora/{corpusId}/query")
    public ResponseEntity<Map<String, Object>> query(
            @PathVariable("corpusId") String corpusId,
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> request) {

        String question = Optional.ofNullable(request).map(body -> body.get("question")).orElse("").trim();
        String mode = Optional.ofNullable(request).map(body -> body.get("mode")).orElse("LOCAL").trim();

        if (question.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Please enter a question first."));
        }

        if (!"LOCAL".equalsIgnoreCase(mode) && !"GLOBAL".equalsIgnoreCase(mode)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Search mode must be LOCAL or GLOBAL."));
        }

        Corpus corpus = corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));

        if ("GLOBAL".equalsIgnoreCase(mode)) {
            return globalSearchResponse(question);
        }

        LocalSearchResult result = buildAnswer(corpus, question);
        String traceId = captureTrace(result.steps());
        return ResponseEntity.ok(Map.of(
                "answerId", UUID.randomUUID().toString(),
                "traceId", traceId,
                "traceStepCount", result.steps().size(),
                "answer", result.answer(),
                "mode", mode.toUpperCase(Locale.ROOT)));
    }

    private ResponseEntity<Map<String, Object>> globalSearchResponse(String question) {
        GlobalSearchAnswer result = new AnswerGlobalSearch(graphStorePort).answer(question);
        String traceId = captureTrace(result.steps());

        if (result.noAnswer()) {
            // AD-13's distinct no-answer shape — a normal outcome (Communities
            // not detected/summarized yet), never the generic {"error": ...} shape.
            return ResponseEntity.ok(Map.of(
                    "answerId", UUID.randomUUID().toString(),
                    "traceId", traceId,
                    "traceStepCount", result.steps().size(),
                    "noAnswer", true,
                    "reason", result.reason()));
        }

        return ResponseEntity.ok(Map.of(
                "answerId", UUID.randomUUID().toString(),
                "traceId", traceId,
                "traceStepCount", result.steps().size(),
                "answer", result.answer(),
                "mode", "GLOBAL"));
    }

    /**
     * Generates a fresh {@code traceId} and stores a {@link RetrievalTrace} for
     * the given steps under it — shared by both the LOCAL and GLOBAL query
     * paths so a trace is always captured (even zero-step) before the query
     * response is built (Story 5.1 AC2).
     */
    private String captureTrace(List<RetrievalStep> steps) {
        String traceId = UUID.randomUUID().toString();
        retrievalTraceStore.put(traceId, new RetrievalTrace(traceId, steps));
        return traceId;
    }

    @GetMapping("/api/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> trace(@PathVariable("traceId") String traceId) {
        RetrievalTrace trace = retrievalTraceStore.get(traceId)
                .orElseThrow(() -> new IllegalArgumentException("No retrieval trace was found for id " + traceId));

        return ResponseEntity.ok(Map.of(
                "traceId", trace.traceId(),
                "steps", trace.steps().stream().map(this::stepPayload).toList()));
    }

    private Map<String, Object> stepPayload(RetrievalStep step) {
        return Map.of(
                "kind", step.kind().name(),
                "identifier", step.identifier(),
                "label", step.label());
    }

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedFileType(UnsupportedFileTypeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(UnreadableDocumentException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableDocument(UnreadableDocumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> handleUncheckedIOException(UncheckedIOException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to read an uploaded file. Please try again."));
    }

    private void startKnowledgeGraphConstruction(Corpus corpus) {
        CompletableFuture.runAsync(() -> {
            try {
                new BuildKnowledgeGraph(llmPort, graphStorePort).run(corpus,
                        entity -> corpusProgressService.emit(corpus.id(), "entity-extracted",
                                entityEventPayload(entity)),
                        relationship -> corpusProgressService.emit(corpus.id(), "relationship-extracted",
                                relationshipEventPayload(relationship)));
                new DetectCommunities(graphStorePort, llmPort).run(corpus,
                        (community, memberEntityIdentities) -> corpusProgressService.emit(corpus.id(), "community-detected",
                                communityEventPayload(community, memberEntityIdentities)));
                corpusProgressService.emit(corpus.id(), "ingestion-complete",
                        Map.of("message", "Knowledge graph construction and community detection completed for " + corpus.name()));
            } catch (Exception ex) {
                corpusProgressService.emit(corpus.id(), "error",
                        Map.of("error", EXTRACTION_FAILURE_MESSAGE));
            }
        });
    }

    private Map<String, Object> entityEventPayload(Entity entity) {
        return Map.of(
                "identity", entity.normalizedIdentity(),
                "name", entity.name(),
                "type", entity.type());
    }

    private Map<String, Object> relationshipEventPayload(Relationship relationship) {
        return Map.of(
                "sourceIdentity", Entity.identityOf(relationship.source(), relationship.sourceType()),
                "source", relationship.source(),
                "targetIdentity", Entity.identityOf(relationship.target(), relationship.targetType()),
                "target", relationship.target(),
                "type", relationship.type());
    }

    private Map<String, Object> communityEventPayload(Community community, List<String> memberEntityIdentities) {
        return Map.of(
                "communityId", community.id(),
                "summary", community.summary(),
                "memberEntityIdentities", memberEntityIdentities);
    }

    private LocalSearchResult buildAnswer(Corpus corpus, String question) {
        Set<String> tokens = KeywordMatcher.tokenize(question);

        for (var document : corpus.documents()) {
            if (document == null || document.content() == null) {
                continue;
            }
            String content = document.content();
            String candidate = findBestSentence(content, tokens);
            if (candidate != null) {
                String answer = "Based on the local neighborhood in this corpus, " + candidate;
                return new LocalSearchResult(answer, entityStepsNamedIn(candidate));
            }
        }

        return new LocalSearchResult(
                "The loaded corpus does not contain a direct local match for that question. Try asking about a person, place, or event mentioned in the documents.",
                List.of());
    }

    private String findBestSentence(String content, Set<String> tokens) {
        String[] sentences = content.split("(?<=[.!?])\\s+");
        String best = null;
        int bestScore = -1;
        for (String sentence : sentences) {
            int score = KeywordMatcher.score(sentence, tokens);
            if (score > bestScore) {
                bestScore = score;
                best = sentence;
            }
        }
        return bestScore > 0 ? best : null;
    }

    /**
     * Cross-references the matched sentence's text against the Entities that
     * already exist in the graph (a best-effort match, since Local Search's
     * underlying retrieval is plain-text sentence-matching, not a graph
     * traversal — see this story's Design Notes). Steps are ordered by where
     * each entity's name first appears in the sentence, not by
     * {@code graphStorePort.entities()}'s iteration order.
     */
    private List<RetrievalStep> entityStepsNamedIn(String sentence) {
        record NamedAt(int index, Entity entity) {
        }

        Collection<Entity> entities = graphStorePort.entities();
        if (entities == null) {
            return List.of();
        }

        List<NamedAt> matches = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity == null || entity.name() == null || entity.name().isBlank()) {
                continue;
            }
            java.util.regex.Matcher wordBoundaryMatch = java.util.regex.Pattern
                    .compile("\\b" + java.util.regex.Pattern.quote(entity.name().toLowerCase(Locale.ROOT)) + "\\b")
                    .matcher(sentence.toLowerCase(Locale.ROOT));
            if (wordBoundaryMatch.find()) {
                matches.add(new NamedAt(wordBoundaryMatch.start(), entity));
            }
        }

        matches.sort(Comparator.comparingInt(NamedAt::index));
        return matches.stream()
                .map(match -> new RetrievalStep(
                        RetrievalStep.Kind.ENTITY, match.entity().normalizedIdentity(), match.entity().name()))
                .toList();
    }

    private record LocalSearchResult(String answer, List<RetrievalStep> steps) {
    }

    private Map<String, Object> corpusPayload(Corpus corpus) {
        return Map.of(
                "corpusId", corpus.id(),
                "name", corpus.name(),
                "documentNames", corpus.documentNames(),
                "documentCount", corpus.documentCount());
    }

    private UploadedDocument toUploadedDocument(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "(unnamed file)";
        }

        try {
            byte[] bytes = file.getBytes();
            String content = extractText(filename, bytes);
            return new UploadedDocument(filename, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file: " + filename, e);
        }
    }

    private String extractText(String filename, byte[] bytes) {
        DocumentParserPort parser = documentParsers.stream()
                .filter(candidate -> candidate.supports(filename))
                .findFirst()
                .orElseThrow(() -> new UnsupportedFileTypeException(filename));

        String extractedText = parser.extract(filename, bytes);
        if (filename.toLowerCase(Locale.ROOT).endsWith(".pdf") && (extractedText == null || extractedText.isBlank())) {
            throw new UnreadableDocumentException(filename,
                    "No text could be read from that PDF — is it a scanned image?");
        }
        return extractedText == null ? "" : extractedText;
    }
}
