package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.adapter.langchain4j.LangChain4jEmbeddingPort;
import dev.rabauer.graphrag.adapter.langchain4j.LangChain4jLlmPort;
import dev.rabauer.graphrag.adapter.langchain4j.OpenAiLlmPort;
import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.RetrievalTrace;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.domain.UnreadableDocumentException;
import dev.rabauer.graphrag.core.domain.UnsupportedFileTypeException;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import dev.rabauer.graphrag.core.port.DocumentParserPort;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.core.usecase.AnswerVectorBaseline;
import dev.rabauer.graphrag.core.usecase.AnswerGlobalSearch;
import dev.rabauer.graphrag.core.usecase.AnswerLocalSearch;
import dev.rabauer.graphrag.core.usecase.AnswerDriftSearch;
import dev.rabauer.graphrag.core.usecase.BuildKnowledgeGraph;
import dev.rabauer.graphrag.core.usecase.ConstructVectorIndex;
import dev.rabauer.graphrag.core.usecase.DetectCommunities;
import dev.rabauer.graphrag.core.usecase.EmbedGraphElements;
import dev.rabauer.graphrag.core.usecase.VectorBaselineAnswer;
import dev.rabauer.graphrag.core.usecase.DriftSearchAnswer;
import dev.rabauer.graphrag.core.usecase.GlobalSearchAnswer;
import dev.rabauer.graphrag.core.usecase.IngestCorpus;
import dev.rabauer.graphrag.core.usecase.LocalSearchAnswer;
import dev.rabauer.graphrag.core.usecase.SemanticMatchingException;
import dev.rabauer.graphrag.core.usecase.TextUnitProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Upload entry point for a Corpus. It accepts plain text and PDF uploads,
 * validates support with the registered parser adapters, and extracts the text
 * payload each parser is responsible for before storing the resulting Corpus.
 */
@RestController
public class CorpusController {

    private static final Logger LOG = LoggerFactory.getLogger(CorpusController.class);
    static final String EXTRACTION_FAILURE_MESSAGE =
            "The LLM call failed during knowledge graph extraction. Nothing was retried — try again when ready.";
    static final String COMMUNITY_DETECTION_FAILURE_MESSAGE =
            "Community detection failed after the knowledge graph was extracted (is the Neo4j Graph Data Science "
                    + "plugin available?). Nothing was retried — try again when ready.";
    static final String EMBEDDING_FAILURE_MESSAGE =
            "The embedding call failed while preparing Entities and Communities for semantic search. "
                    + "Nothing was retried — try again when ready.";
    static final String SEMANTIC_MATCHING_FAILURE_MESSAGE =
            "Semantic matching failed: the embedding call for your question did not succeed, so no starting "
                    + "points could be found. Nothing was retried — try again when ready.";
    static final String ANSWER_SYNTHESIS_FAILURE_MESSAGE =
            "The LLM call failed while writing the answer to your question. Nothing was retried — "
                    + "try again when ready.";
    private static final String GRAPH_BUILDING_MESSAGE =
            "The graph is still building for this corpus. Wait for “Knowledge Graph — Ready”, then ask your question.";
    private static final String GRAPH_FAILED_MESSAGE =
            "Graph construction failed for this corpus. Upload again or use the demo dataset to restart.";
    private static final String OFFLINE_QUERY_BLOCKED_MESSAGE =
            "This is the offline demo corpus — its questions are pre-recorded, not live. Load a live corpus "
                    + "(upload your own, or the live Demo Dataset) to ask your own question.";

    private final IngestCorpus ingestCorpus;
    private final Neo4jCorpusRegistry corpusStore;
    private final List<DocumentParserPort> documentParsers;
    private final DemoDatasetService demoDatasetService;
    private final CorpusProgressService corpusProgressService;
    private final LlmPort llmPort;
    private final GraphStorePort graphStorePort;
    private final RetrievalTraceStore retrievalTraceStore;
    private final ConstructVectorIndex constructVectorIndex;
    private final AnswerVectorBaseline answerVectorBaseline;
    private final VectorStorePort vectorStorePort;
    private final EmbeddingPort embeddingPort;

    public CorpusController(IngestCorpus ingestCorpus, Neo4jCorpusRegistry corpusStore,
                           List<DocumentParserPort> documentParsers, DemoDatasetService demoDatasetService,
                           CorpusProgressService corpusProgressService, LlmPort llmPort, GraphStorePort graphStorePort,
                           RetrievalTraceStore retrievalTraceStore, ConstructVectorIndex constructVectorIndex,
                           AnswerVectorBaseline answerVectorBaseline, VectorStorePort vectorStorePort) {
        this(ingestCorpus, corpusStore, documentParsers, demoDatasetService, corpusProgressService, llmPort,
                graphStorePort, retrievalTraceStore, constructVectorIndex, answerVectorBaseline, vectorStorePort, null);
    }

    /**
     * @param embeddingPort when semantic (Story 15.1), Entities and Communities
     *                      are embedded after community detection and the
     *                      LOCAL/GLOBAL/DRIFT searches seed by meaning; null or
     *                      the offline stub keeps pure keyword matching
     */
    @Autowired
    public CorpusController(IngestCorpus ingestCorpus, Neo4jCorpusRegistry corpusStore,
                           List<DocumentParserPort> documentParsers, DemoDatasetService demoDatasetService,
                           CorpusProgressService corpusProgressService, LlmPort llmPort, GraphStorePort graphStorePort,
                           RetrievalTraceStore retrievalTraceStore, ConstructVectorIndex constructVectorIndex,
                           AnswerVectorBaseline answerVectorBaseline, VectorStorePort vectorStorePort,
                           EmbeddingPort embeddingPort) {
        this.embeddingPort = embeddingPort;
        this.ingestCorpus = ingestCorpus;
        this.corpusStore = corpusStore;
        this.documentParsers = documentParsers;
        this.demoDatasetService = demoDatasetService;
        this.corpusProgressService = corpusProgressService;
        this.llmPort = llmPort;
        this.graphStorePort = graphStorePort;
        this.retrievalTraceStore = retrievalTraceStore;
        this.constructVectorIndex = constructVectorIndex;
        this.answerVectorBaseline = answerVectorBaseline;
        this.vectorStorePort = vectorStorePort;
    }

    /**
     * Story 8.5: the corpus's chunk embeddings at their settled, ingestion-time
     * 2D positions (Story 8.1) — fetched once by the Vector Space tab and
     * cached client-side, since this layout never changes after ingestion.
     */
    @GetMapping("/api/corpora/{corpusId}/vector-space")
    public ResponseEntity<Map<String, Object>> vectorSpace(@PathVariable("corpusId") String corpusId) {
        Collection<EmbeddedChunk> chunks = vectorStorePort.chunks(corpusId);
        List<Map<String, Object>> chunkPayload = chunks.stream()
                .map(ec -> Map.<String, Object>of(
                        "id", ec.chunk().id(),
                        "x", ec.projection().length > 0 ? ec.projection()[0] : 0.0,
                        "y", ec.projection().length > 1 ? ec.projection()[1] : 0.0))
                .toList();
        return ResponseEntity.ok(Map.of("corpusId", corpusId, "chunks", chunkPayload));
    }

    @GetMapping(value = "/api/corpora/{corpusId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable("corpusId") String corpusId) {
        return corpusProgressService.register(corpusId);
    }

    /**
     * Story 12.6: every retained corpus, ordered most-recently-activated
     * first -- the durable history-list data source for Story 12.7's
     * switcher UI. Offline/demo corpora are excluded by
     * {@link Neo4jCorpusRegistry#list()} itself.
     */
    @GetMapping("/api/corpora")
    public ResponseEntity<Map<String, Object>> corpora() {
        List<Map<String, Object>> corpora = corpusStore.list().stream()
                .map(this::corpusSummaryPayload)
                .toList();
        return ResponseEntity.ok(Map.of("corpora", corpora));
    }

    /**
     * Story 12.6: the only endpoint that ever updates {@code
     * lastActivatedAt} -- never inferred from query/vector-space/progress
     * traffic. 404s via the existing {@code IllegalArgumentException} ->
     * {@link #handleIllegalArgumentException(IllegalArgumentException)}
     * pattern already used by {@link #query}.
     */
    @PostMapping("/api/corpora/{corpusId}/activate")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable("corpusId") String corpusId) {
        corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));
        corpusStore.activate(corpusId);
        return ResponseEntity.ok(Map.of("id", corpusId, "activated", true));
    }

    /**
     * Story 12.7: a bulk read of a corpus's entire graph, shaped exactly
     * like the {@code entity-extracted}/{@code relationship-extracted}/
     * {@code community-detected} SSE payloads already emitted during live
     * ingestion (same field names) -- so the corpus switcher's {@code
     * READY} branch can loop the same {@code GraphCanvas.addEntity}/
     * {@code addRelationship}/{@code addCommunity} functions the live SSE
     * handlers already call, instead of a new bulk-render API (Design
     * Notes). Read-only over the already-real corpus-scoped {@link
     * GraphStorePort} methods -- never touches {@code lastActivatedAt}.
     */
    @GetMapping("/api/corpora/{corpusId}/graph")
    public ResponseEntity<Map<String, Object>> graph(@PathVariable("corpusId") String corpusId) {
        Corpus corpus = corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));

        Neo4jCorpusRegistry.CorpusWorkflowStatus workflowStatus = corpusStore.status(corpus.id());
        if (workflowStatus == Neo4jCorpusRegistry.CorpusWorkflowStatus.BUILDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", GRAPH_BUILDING_MESSAGE));
        }
        if (workflowStatus == Neo4jCorpusRegistry.CorpusWorkflowStatus.FAILED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", GRAPH_FAILED_MESSAGE));
        }

        Collection<Entity> entities = graphStorePort.entities(corpusId);
        Collection<Relationship> relationships = graphStorePort.relationships(corpusId);
        Collection<Community> communities = graphStorePort.communities(corpusId);
        Collection<CommunityMembership> memberships = graphStorePort.communityMemberships(corpusId);
        Map<String, SourceLabel> sourceLabelsById = graphStorePort.textUnits(corpusId).stream()
                .collect(Collectors.toMap(TextUnit::id,
                        unit -> new SourceLabel(unit.documentName(), unit.ordinal()),
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new));

        Map<String, List<String>> memberEntityIdentitiesByCommunity = memberships.stream()
                .collect(Collectors.groupingBy(
                        CommunityMembership::communityId,
                        Collectors.mapping(CommunityMembership::entityIdentity, Collectors.toList())));

        List<Map<String, Object>> entityPayload = entities.stream()
                .map(entity -> entityEventPayload(entity, sourceLabelsById::get))
                .toList();
        List<Map<String, Object>> relationshipPayload = relationships.stream()
                .map(this::relationshipEventPayload)
                .toList();
        List<Map<String, Object>> communityPayload = communities.stream()
                .map(community -> communityEventPayload(community,
                        memberEntityIdentitiesByCommunity.getOrDefault(community.id(), List.of())))
                .toList();

        return ResponseEntity.ok(Map.of(
                "corpusId", corpusId,
                "entities", entityPayload,
                "relationships", relationshipPayload,
                "communities", communityPayload));
    }

    @GetMapping("/api/corpora/{corpusId}/text-units/{textUnitId}")
    public ResponseEntity<Map<String, Object>> textUnit(@PathVariable("corpusId") String corpusId,
                                                        @PathVariable("textUnitId") String textUnitId) {
        corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));
        TextUnit textUnit = graphStorePort.textUnit(corpusId, textUnitId)
                .orElseThrow(() -> new IllegalArgumentException("No text passage was found for id " + textUnitId));
        return ResponseEntity.ok(Map.of(
                "id", textUnit.id(),
                "documentName", textUnit.documentName() == null ? "" : textUnit.documentName(),
                "ordinal", textUnit.ordinal(),
                "text", textUnit.text() == null ? "" : textUnit.text()));
    }

    private Map<String, Object> corpusSummaryPayload(Neo4jCorpusRegistry.CorpusSummary summary) {
        return Map.of(
                "id", summary.corpusId(),
                "name", summary.name(),
                "status", summary.status().name(),
                "createdAt", summary.createdAt(),
                "lastActivatedAt", summary.lastActivatedAt());
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
        startVectorIndexConstruction(corpus);

        return ResponseEntity.status(HttpStatus.CREATED).body(corpusPayload(corpus));
    }

    @PostMapping("/api/corpora/demo")
    public ResponseEntity<Map<String, Object>> useDemoDataset() {
        Corpus corpus = demoDatasetService.createSherlockCorpus();
        corpusStore.put(corpus);
        corpusProgressService.emit(corpus.id(), "ingestion-started",
                Map.of("message", "Knowledge graph construction started for " + corpus.name()));
        startKnowledgeGraphConstruction(corpus);
        startVectorIndexConstruction(corpus);
        return ResponseEntity.status(HttpStatus.CREATED).body(corpusPayload(corpus));
    }

    /**
     * Story 9.1: a demo-safe offline mode. Builds the same Sherlock Holmes
     * corpus, but always through the deterministic offline LLM/embedding
     * stubs ({@link LangChain4jLlmPort}/{@link LangChain4jEmbeddingPort}'s
     * own no-arg constructors) — never the app's normally-configured
     * {@link #llmPort}/embedding ports, regardless of whether
     * {@code OPENAI_API_KEY} is set. Querying this corpus is blocked
     * entirely ({@link #OFFLINE_QUERY_BLOCKED_MESSAGE}) so a live call can
     * never leak in through the query path either — a presenter can still
     * watch the graph build, explore communities, and inspect Entities,
     * just never ask it a fresh question.
     */
    @PostMapping("/api/corpora/demo-offline")
    public ResponseEntity<Map<String, Object>> useOfflineDemoDataset() {
        Corpus corpus = demoDatasetService.createOfflineSherlockCorpus();
        corpusStore.put(corpus);
        corpusStore.markOffline(corpus.id());
        corpusProgressService.emit(corpus.id(), "ingestion-started",
                Map.of("message", "Knowledge graph construction started for " + corpus.name()));
        startKnowledgeGraphConstruction(corpus, new LangChain4jLlmPort(), null);
        startVectorIndexConstruction(corpus, new ConstructVectorIndex(new LangChain4jEmbeddingPort(), vectorStorePort));
        return ResponseEntity.status(HttpStatus.CREATED).body(corpusPayload(corpus, true));
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

        if (!"LOCAL".equalsIgnoreCase(mode)
                && !"GLOBAL".equalsIgnoreCase(mode)
                && !"DRIFT".equalsIgnoreCase(mode)
                && !"VECTOR".equalsIgnoreCase(mode)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Search mode must be LOCAL, GLOBAL, DRIFT, or VECTOR."));
        }

        Corpus corpus = corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));
        if (corpusStore.isOffline(corpus.id())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", OFFLINE_QUERY_BLOCKED_MESSAGE));
        }
        Neo4jCorpusRegistry.CorpusWorkflowStatus workflowStatus = corpusStore.status(corpus.id());
        if (workflowStatus == Neo4jCorpusRegistry.CorpusWorkflowStatus.BUILDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", GRAPH_BUILDING_MESSAGE));
        }
        if (workflowStatus == Neo4jCorpusRegistry.CorpusWorkflowStatus.FAILED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", GRAPH_FAILED_MESSAGE));
        }

        if ("GLOBAL".equalsIgnoreCase(mode)) {
            return globalSearchResponse(question, corpus.id());
        }
        if ("DRIFT".equalsIgnoreCase(mode)) {
            return driftSearchResponse(question, corpus.id());
        }
        if ("VECTOR".equalsIgnoreCase(mode)) {
            return vectorBaselineResponse(question, corpus.id());
        }

        return localSearchResponse(question, corpus.id());
    }

    /**
     * Story 15.2: with an answer-synthesizing {@link #llmPort} the answer is
     * generated and cited. A successful answer always carries
     * {@code citations} ({@code []} for the templated answer); "not in the
     * context" is AD-13's no-answer shape, which has no {@code citations}. An LLM failure surfaces through
     * {@link #handleLlmCallFailure}.
     */
    private ResponseEntity<Map<String, Object>> localSearchResponse(String question, String corpusId) {
        LocalSearchAnswer result = new AnswerLocalSearch(graphStorePort, embeddingPort, llmPort)
                .answer(question, corpusId);
        String traceId = captureTrace(result.steps());

        if (result.noAnswer()) {
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
                "mode", "LOCAL",
                "citations", result.citations().stream().map(CorpusController::citationPayload).toList()));
    }

    private static Map<String, Object> citationPayload(Citation citation) {
        return Map.of(
                "textUnitId", citation.textUnitId(),
                "documentName", citation.documentName(),
                "excerpt", citation.excerpt());
    }

    /**
     * Story 15.3: like {@link #localSearchResponse}, a synthesizing
     * {@link #llmPort} generates a cited answer; a successful answer always
     * carries {@code citations} ({@code []} for the templated answer).
     */
    private ResponseEntity<Map<String, Object>> globalSearchResponse(String question, String corpusId) {
        GlobalSearchAnswer result = new AnswerGlobalSearch(graphStorePort, embeddingPort, llmPort)
                .answer(question, corpusId);
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
                "mode", "GLOBAL",
                "citations", result.citations().stream().map(CorpusController::citationPayload).toList()));
    }

    /** Story 15.3: as {@link #globalSearchResponse}, with one synthesis over all branch contexts. */
    private ResponseEntity<Map<String, Object>> driftSearchResponse(String question, String corpusId) {
        DriftSearchAnswer result = new AnswerDriftSearch(graphStorePort, llmPort, embeddingPort).answer(question, corpusId);
        String traceId = captureTrace(result.steps());

        if (result.noAnswer()) {
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
                "mode", "DRIFT",
                "citations", result.citations().stream().map(CorpusController::citationPayload).toList()));
    }

    private ResponseEntity<Map<String, Object>> vectorBaselineResponse(String question, String corpusId) {
        VectorBaselineAnswer result = answerVectorBaseline.answer(question, corpusId);
        String traceId = captureTrace(result.steps());

        if (result.noChunks()) {
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
                "mode", "VECTOR",
                "queryProjection", List.of(result.queryProjection()[0], result.queryProjection()[1])));
    }

    /**
     * Generates a fresh {@code traceId} and stores a {@link RetrievalTrace} for
     * the given steps under it — shared by the LOCAL, GLOBAL, and DRIFT query
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

    /**
     * Story 15.1: embedding the question (or the vector lookup) failed at query
     * time. No keyword fallback is attempted, so the outage stays visible.
     */
    @ExceptionHandler(SemanticMatchingException.class)
    public ResponseEntity<Map<String, String>> handleSemanticMatchingFailure(SemanticMatchingException ex) {
        LOG.warn("Semantic matching failed at query time: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", SEMANTIC_MATCHING_FAILURE_MESSAGE));
    }

    /**
     * Story 15.2: the answer-synthesis LLM call failed at query time. There is
     * no templated fallback, so the failure stays visible.
     */
    @ExceptionHandler(OpenAiLlmPort.LlmCallFailedException.class)
    public ResponseEntity<Map<String, String>> handleLlmCallFailure(OpenAiLlmPort.LlmCallFailedException ex) {
        LOG.warn("LLM call failed at query time: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", ANSWER_SYNTHESIS_FAILURE_MESSAGE));
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
        startKnowledgeGraphConstruction(corpus, llmPort, embeddingPort);
    }

    /**
     * @param embeddingPortToUse null for the offline demo, so it never embeds
     */
    private void startKnowledgeGraphConstruction(Corpus corpus, LlmPort llmPortToUse, EmbeddingPort embeddingPortToUse) {
        CompletableFuture.runAsync(() -> {
            boolean detectingCommunities = false;
            boolean embedding = false;
            try {
                Map<String, SourceLabel> sourceLabelsById = new java.util.concurrent.ConcurrentHashMap<>();
                new BuildKnowledgeGraph(llmPortToUse, graphStorePort).run(corpus,
                        progress -> {
                            sourceLabelsById.put(progress.textUnitId(),
                                    new SourceLabel(progress.documentName(), progress.ordinal()));
                            corpusProgressService.emit(corpus.id(), "text-unit-extracted",
                                    textUnitEventPayload(progress));
                        },
                        entity -> corpusProgressService.emit(corpus.id(), "entity-extracted",
                                entityEventPayload(entity, sourceLabelsById::get)),
                        relationship -> corpusProgressService.emit(corpus.id(), "relationship-extracted",
                                relationshipEventPayload(relationship)),
                        (previousIdentity, entity) -> corpusProgressService.emit(corpus.id(), "entity-retyped",
                                entityRetypedEventPayload(previousIdentity, entity, sourceLabelsById::get)));
                detectingCommunities = true;
                new DetectCommunities(graphStorePort, llmPortToUse).run(corpus,
                        (community, memberEntityIdentities) -> corpusProgressService.emit(corpus.id(), "community-detected",
                                communityEventPayload(community, memberEntityIdentities)));
                detectingCommunities = false;
                embedding = true;
                // Story 15.1: a no-op unless the embedding port is a real semantic model.
                new EmbedGraphElements(graphStorePort, embeddingPortToUse).run(corpus);
                embedding = false;
                corpusStore.markReady(corpus.id());
                corpusProgressService.emit(corpus.id(), "ingestion-complete",
                        Map.of("message", "Knowledge graph construction and community detection completed for " + corpus.name()));
            } catch (Exception ex) {
                // The exception message names the failing document and passage (Story 13.1),
                // or the community-detection stage (Story 14.1), or the embedding stage (Story 15.1)
                // -- no fallback grouping and no retry is attempted.
                String stage = embedding ? "Embedding"
                        : detectingCommunities && !isLlmFailure(ex) ? "Community detection"
                        : "Knowledge graph construction";
                LOG.warn("{} failed for corpus {}: {}", stage, corpus.id(), ex.getMessage(), ex);
                corpusStore.markFailed(corpus.id());
                corpusProgressService.emit(corpus.id(), "error",
                        Map.of("error", embedding ? EMBEDDING_FAILURE_MESSAGE
                                : detectingCommunities && !isLlmFailure(ex)
                                ? COMMUNITY_DETECTION_FAILURE_MESSAGE
                                : EXTRACTION_FAILURE_MESSAGE));
            }
        });
    }

    /**
     * Whether {@code failure} (or any cause) is an LLM call failure, e.g. a
     * failed community summarization, which keeps the LLM wording even when
     * it is thrown during the community-detection stage.
     */
    private static boolean isLlmFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof OpenAiLlmPort.LlmCallFailedException) {
                return true;
            }
        }
        return false;
    }

    private void startVectorIndexConstruction(Corpus corpus) {
        startVectorIndexConstruction(corpus, constructVectorIndex);
    }

    private void startVectorIndexConstruction(Corpus corpus, ConstructVectorIndex constructVectorIndexToUse) {
        CompletableFuture.runAsync(() -> {
            try {
                constructVectorIndexToUse.run(corpus);
            } catch (Exception ex) {
                LOG.warn("Vector index construction failed for corpus {} — this does not affect knowledge graph construction.",
                        corpus.id(), ex);
            }
        });
    }

    private Map<String, Object> textUnitEventPayload(TextUnitProgress progress) {
        return Map.of(
                "index", progress.index(),
                "total", progress.total(),
                "documentName", progress.documentName() == null ? "" : progress.documentName());
    }

    private Map<String, Object> entityEventPayload(Entity entity, Function<String, SourceLabel> sourceLabelLookup) {
        return Map.of(
                "identity", entity.normalizedIdentity(),
                "name", entity.name(),
                "type", entity.type(),
                "description", entity.description(),
                "sources", sourcePayloads(entity, sourceLabelLookup));
    }

    private Map<String, Object> entityRetypedEventPayload(String previousIdentity, Entity entity,
                                                          Function<String, SourceLabel> sourceLabelLookup) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(entityEventPayload(entity, sourceLabelLookup));
        payload.put("previousIdentity", previousIdentity);
        return payload;
    }

    private List<Map<String, Object>> sourcePayloads(Entity entity, Function<String, SourceLabel> sourceLabelLookup) {
        if (entity.sourceTextUnitIds().isEmpty()) {
            return List.of();
        }
        return entity.sourceTextUnitIds().stream()
                .map(textUnitId -> {
                    SourceLabel sourceLabel = sourceLabelLookup.apply(textUnitId);
                    if (sourceLabel == null) {
                        return null;
                    }
                    return Map.<String, Object>of(
                            "textUnitId", textUnitId,
                            "documentName", sourceLabel.documentName() == null ? "" : sourceLabel.documentName(),
                            "ordinal", sourceLabel.ordinal());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private record SourceLabel(String documentName, int ordinal) {
    }

    private Map<String, Object> relationshipEventPayload(Relationship relationship) {
        return Map.of(
                "sourceIdentity", Entity.identityOf(relationship.source(), relationship.sourceType()),
                "source", relationship.source(),
                "targetIdentity", Entity.identityOf(relationship.target(), relationship.targetType()),
                "target", relationship.target(),
                "type", relationship.type(),
                "description", relationship.description());
    }

    private Map<String, Object> communityEventPayload(Community community, List<String> memberEntityIdentities) {
        return Map.of(
                "communityId", community.id(),
                "title", community.title(),
                "summary", community.summary(),
                "memberEntityIdentities", memberEntityIdentities);
    }

    private Map<String, Object> corpusPayload(Corpus corpus) {
        return corpusPayload(corpus, false);
    }

    private Map<String, Object> corpusPayload(Corpus corpus, boolean offline) {
        return Map.of(
                "corpusId", corpus.id(),
                "name", corpus.name(),
                "documentNames", corpus.documentNames(),
                "documentCount", corpus.documentCount(),
                "offline", offline);
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
