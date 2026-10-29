package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.adapter.langchain4j.LangChain4jEmbeddingPort;
import dev.rabauer.graphrag.adapter.langchain4j.LangChain4jLlmPort;
import dev.rabauer.graphrag.adapter.langchain4j.OpenAiLlmPort;
import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.UnreadableDocumentException;
import dev.rabauer.graphrag.core.domain.UnsupportedFileTypeException;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import dev.rabauer.graphrag.core.port.DocumentParserPort;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.core.usecase.BuildKnowledgeGraph;
import dev.rabauer.graphrag.core.usecase.ConstructVectorIndex;
import dev.rabauer.graphrag.core.usecase.DetectCommunities;
import dev.rabauer.graphrag.core.usecase.EmbedGraphElements;
import dev.rabauer.graphrag.core.usecase.IngestCorpus;
import dev.rabauer.graphrag.web.CorpusEventPayloads.SourceLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static dev.rabauer.graphrag.web.CorpusEventPayloads.communityEventPayload;
import static dev.rabauer.graphrag.web.CorpusEventPayloads.entityEventPayload;
import static dev.rabauer.graphrag.web.CorpusEventPayloads.entityRetypedEventPayload;
import static dev.rabauer.graphrag.web.CorpusEventPayloads.relationshipEventPayload;
import static dev.rabauer.graphrag.web.CorpusEventPayloads.textUnitEventPayload;

/**
 * Ingestion: the upload entry point for a Corpus (plain text, Markdown and PDF,
 * validated with the registered parser adapters), the two demo datasets, the
 * background knowledge-graph and vector-index construction, and the SSE
 * progress stream they report to.
 */
@RestController
public class CorpusIngestController extends CorpusApiController {

    private static final Logger LOG = LoggerFactory.getLogger(CorpusIngestController.class);

    private final IngestCorpus ingestCorpus;
    private final Neo4jCorpusRegistry corpusStore;
    private final List<DocumentParserPort> documentParsers;
    private final DemoDatasetService demoDatasetService;
    private final CorpusProgressService corpusProgressService;
    private final LlmPort llmPort;
    private final GraphStorePort graphStorePort;
    private final ConstructVectorIndex constructVectorIndex;
    private final VectorStorePort vectorStorePort;
    private final EmbeddingPort embeddingPort;

    /**
     * @param embeddingPort when semantic (Story 15.1), Entities and Communities
     *                      are embedded after community detection; null or
     *                      the offline stub keeps pure keyword matching
     */
    public CorpusIngestController(IngestCorpus ingestCorpus, Neo4jCorpusRegistry corpusStore,
                                  List<DocumentParserPort> documentParsers, DemoDatasetService demoDatasetService,
                                  CorpusProgressService corpusProgressService, LlmPort llmPort,
                                  GraphStorePort graphStorePort, ConstructVectorIndex constructVectorIndex,
                                  VectorStorePort vectorStorePort, EmbeddingPort embeddingPort) {
        this.ingestCorpus = ingestCorpus;
        this.corpusStore = corpusStore;
        this.documentParsers = documentParsers;
        this.demoDatasetService = demoDatasetService;
        this.corpusProgressService = corpusProgressService;
        this.llmPort = llmPort;
        this.graphStorePort = graphStorePort;
        this.constructVectorIndex = constructVectorIndex;
        this.vectorStorePort = vectorStorePort;
        this.embeddingPort = embeddingPort;
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
     * entirely ({@link CorpusMessages#OFFLINE_QUERY_BLOCKED_MESSAGE}) so a live
     * call can never leak in through the query path either — a presenter can
     * still watch the graph build, explore communities, and inspect Entities,
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
                        Map.of("error", embedding ? CorpusMessages.EMBEDDING_FAILURE_MESSAGE
                                : detectingCommunities && !isLlmFailure(ex)
                                ? CorpusMessages.COMMUNITY_DETECTION_FAILURE_MESSAGE
                                : CorpusMessages.EXTRACTION_FAILURE_MESSAGE));
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
