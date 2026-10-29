package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.adapter.langchain4j.OpenAiLlmPort;
import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.core.domain.ComparisonStats;
import dev.rabauer.graphrag.core.domain.ComparisonVerdict;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.StageTiming;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.core.usecase.AnswerVectorBaseline;
import dev.rabauer.graphrag.core.usecase.CompareAllModes;
import dev.rabauer.graphrag.core.usecase.CompareAnswers;
import dev.rabauer.graphrag.core.usecase.SemanticMatchingException;
import dev.rabauer.graphrag.core.usecase.VectorBaselineAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.rabauer.graphrag.web.QuerySupport.rankingPayload;

/**
 * Side-by-side comparisons: one GraphRAG mode against the Vector Search
 * baseline ({@code compare}), and all four retrieval methods at once
 * ({@code compare-all}).
 */
@RestController
public class CorpusCompareController extends CorpusApiController {

    private static final Logger LOG = LoggerFactory.getLogger(CorpusCompareController.class);

    private final Neo4jCorpusRegistry corpusStore;
    private final LlmPort llmPort;
    private final GraphStorePort graphStorePort;
    private final AnswerVectorBaseline answerVectorBaseline;
    private final VectorStorePort vectorStorePort;
    private final EmbeddingPort embeddingPort;
    private final QuerySupport support;

    public CorpusCompareController(Neo4jCorpusRegistry corpusStore, LlmPort llmPort, GraphStorePort graphStorePort,
                                   RetrievalTraceStore retrievalTraceStore, AnswerVectorBaseline answerVectorBaseline,
                                   VectorStorePort vectorStorePort, EmbeddingPort embeddingPort) {
        this.corpusStore = corpusStore;
        this.llmPort = llmPort;
        this.graphStorePort = graphStorePort;
        this.answerVectorBaseline = answerVectorBaseline;
        this.vectorStorePort = vectorStorePort;
        this.embeddingPort = embeddingPort;
        this.support = new QuerySupport(corpusStore, retrievalTraceStore);
    }

    /**
     * Answers {@code question} fresh with a GraphRAG mode ({@code LOCAL},
     * {@code GLOBAL} or {@code DRIFT}, default {@code LOCAL}) and with the
     * Vector Search baseline, and returns both side by side with each side's
     * key figures, the passages both retrieved and a short verdict (see
     * {@link CompareAnswers}). Validation and the offline/building/failed
     * 409s are those of {@link CorpusQueryController#query}; an LLM failure of either answer is
     * the usual 502, while a failed verdict call falls back to the rule text.
     */
    @PostMapping("/api/corpora/{corpusId}/compare")
    public ResponseEntity<Map<String, Object>> compare(
            @PathVariable("corpusId") String corpusId,
            @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, String> request) {

        String question = Optional.ofNullable(request).map(body -> body.get("question")).orElse("").trim();
        String modeName = Optional.ofNullable(request).map(body -> body.get("mode")).orElse("LOCAL");

        if (question.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Please enter a question first."));
        }
        Optional<CompareAnswers.Mode> mode = CompareAnswers.Mode.parse(modeName);
        if (mode.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Search mode must be LOCAL, GLOBAL, or DRIFT for a comparison."));
        }

        Corpus corpus = corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));
        ResponseEntity<Map<String, Object>> blocked = support.queryBlocked(corpus);
        if (blocked != null) {
            return blocked;
        }

        CompareAnswers.Comparison comparison = new CompareAnswers(graphStorePort, embeddingPort, llmPort,
                vectorStorePort, answerVectorBaseline).compare(question, corpus.id(), mode.get());
        return ResponseEntity.ok(comparisonPayload(comparison));
    }

    /**
     * Answers {@code question} fresh with all four retrieval methods (Local,
     * Global, DRIFT and Vector Search) and returns them side by side: per
     * method the answer, citations, traversal steps and a step count per kind,
     * and where the time went (retrieval, embedding, LLM); across methods an
     * evidence table saying how far each passage got in each method (see
     * {@link CompareAllModes}). A method that fails is reported in its own
     * column ({@code outcome: FAILED}) instead of failing the request. One log
     * line per method records the same figures.
     */
    @PostMapping("/api/corpora/{corpusId}/compare-all")
    public ResponseEntity<Map<String, Object>> compareAll(
            @PathVariable("corpusId") String corpusId,
            @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, String> request) {

        String question = Optional.ofNullable(request).map(body -> body.get("question")).orElse("").trim();
        if (question.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Please enter a question first."));
        }

        Corpus corpus = corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));
        ResponseEntity<Map<String, Object>> blocked = support.queryBlocked(corpus);
        if (blocked != null) {
            return blocked;
        }

        CompareAllModes.Comparison comparison = new CompareAllModes(graphStorePort, embeddingPort, llmPort,
                vectorStorePort, answerVectorBaseline).compare(question, corpus.id());
        comparison.runs().forEach(run -> logRun(corpus.id(), run));
        return ResponseEntity.ok(compareAllPayload(comparison));
    }

    private static void logRun(String corpusId, CompareAllModes.MethodRun run) {
        StageTiming timing = run.timing();
        LOG.info("compare-all corpus={} method={} outcome={} totalMs={} retrievalMs={} embeddingMs={} "
                        + "embeddingCalls={} llmMs={} llmCalls={} steps={} footprint={} citations={}",
                corpusId, run.method(), run.outcome(), timing.totalMs(), timing.retrievalMs(), timing.embeddingMs(),
                timing.embeddingCalls(), timing.llmMs(), timing.llmCalls(), run.steps().size(), run.footprint(),
                run.citations().size());
    }

    private Map<String, Object> compareAllPayload(CompareAllModes.Comparison comparison) {
        List<Map<String, Object>> runs = comparison.runs().stream().map(this::methodRunPayload).toList();
        List<Map<String, Object>> evidence = comparison.evidence().stream()
                .map(CorpusCompareController::evidencePayload).toList();
        return Map.of(
                "question", comparison.question(),
                "summary", comparison.summary(),
                "runs", runs,
                "evidence", evidence);
    }

    private Map<String, Object> methodRunPayload(CompareAllModes.MethodRun run) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("method", run.method().name());
        payload.put("label", run.method().label());
        payload.put("outcome", run.outcome().name());
        if (run.outcome() == CompareAllModes.Outcome.ANSWERED) {
            payload.put("answer", run.answer());
        } else if (run.outcome() == CompareAllModes.Outcome.FAILED) {
            payload.put("reason", failureMessage(run.failure()));
        } else {
            payload.put("reason", run.reason() == null ? "" : run.reason());
        }
        boolean vector = run.method() == CompareAllModes.Method.VECTOR;
        payload.put("citations", run.citations().stream()
                .map(vector ? QuerySupport::chunkCitationPayload : QuerySupport::citationPayload)
                .toList());
        payload.put("traceId", support.captureTrace(run.steps()));
        payload.put("traceStepCount", run.steps().size());
        payload.put("steps", run.steps().stream()
                .map(step -> Map.of("kind", step.kind().name(), "identifier", step.identifier(),
                        "label", step.label()))
                .toList());
        Map<String, Integer> footprint = new java.util.LinkedHashMap<>();
        run.footprint().forEach((kind, count) -> footprint.put(kind.name(), count));
        payload.put("footprint", footprint);
        StageTiming timing = run.timing();
        payload.put("timing", Map.of(
                "totalMs", timing.totalMs(),
                "retrievalMs", timing.retrievalMs(),
                "embeddingMs", timing.embeddingMs(),
                "embeddingCalls", timing.embeddingCalls(),
                "llmMs", timing.llmMs(),
                "llmCalls", timing.llmCalls()));
        if (vector) {
            payload.put("ranking", run.ranking().stream()
                    .map(row -> Map.<String, Object>of(
                            "rank", row.rank(),
                            "chunkId", row.chunkId(),
                            "documentName", row.documentName(),
                            "excerpt", row.excerpt(),
                            "score", row.score(),
                            "used", row.used()))
                    .toList());
            payload.put("scoredChunkCount", run.scoredChunkCount());
        }
        return payload;
    }

    /** What a person reads about a failed run; the exception itself is logged by {@link CompareAllModes}. */
    private static String failureMessage(RuntimeException failure) {
        if (failure instanceof SemanticMatchingException) {
            return CorpusMessages.SEMANTIC_MATCHING_FAILURE_MESSAGE;
        }
        if (failure instanceof OpenAiLlmPort.LlmCallFailedException) {
            return CorpusMessages.ANSWER_SYNTHESIS_FAILURE_MESSAGE;
        }
        return "This method failed unexpectedly; the other methods are unaffected. See the server log.";
    }

    private static Map<String, Object> evidencePayload(CompareAllModes.EvidenceRow row) {
        Map<String, Object> marks = new java.util.LinkedHashMap<>();
        row.marks().forEach((method, mark) -> {
            Map<String, Object> markPayload = new java.util.LinkedHashMap<>();
            markPayload.put("use", mark.use().name());
            markPayload.put("citations", mark.citationNumbers());
            if (mark.rank() > 0) {
                markPayload.put("rank", mark.rank());
            }
            marks.put(method.name(), markPayload);
        });
        return Map.of(
                "id", row.id(),
                "kind", row.kind() == CompareAllModes.PassageKind.CHUNK ? "chunk" : "text-unit",
                "documentName", row.documentName(),
                "excerpt", row.excerpt(),
                "marks", marks);
    }


    private Map<String, Object> comparisonPayload(CompareAnswers.Comparison comparison) {
        CompareAnswers.GraphSide graph = comparison.graph();
        Map<String, Object> graphPayload = new java.util.LinkedHashMap<>();
        graphPayload.put("mode", graph.mode().name());
        graphPayload.put("noAnswer", graph.noAnswer());
        if (graph.noAnswer()) {
            graphPayload.put("reason", graph.reason() == null ? "" : graph.reason());
        } else {
            graphPayload.put("answer", graph.answer() == null ? "" : graph.answer());
        }
        graphPayload.put("citations", graph.citations().stream().map(QuerySupport::citationPayload).toList());
        graphPayload.put("traceId", support.captureTrace(graph.steps()));
        graphPayload.put("traceStepCount", graph.steps().size());
        graphPayload.put("stats", statsPayload(graph.stats()));
        graphPayload.put("retrieved", comparison.graphRetrieved().stream()
                .map(passage -> Map.<String, Object>of(
                        "textUnitId", passage.id(),
                        "documentName", passage.documentName(),
                        "excerpt", passage.excerpt(),
                        "sharedWithVector", passage.shared()))
                .toList());

        VectorBaselineAnswer vector = comparison.vector().answer();
        Map<String, Object> vectorPayload = new java.util.LinkedHashMap<>();
        vectorPayload.put("noAnswer", vector.noAnswer());
        if (vector.noAnswer()) {
            vectorPayload.put("reason", vector.reason() == null ? "" : vector.reason());
        } else {
            vectorPayload.put("answer", vector.answer());
        }
        vectorPayload.put("citations", vector.citations().stream().map(QuerySupport::chunkCitationPayload).toList());
        vectorPayload.put("traceId", support.captureTrace(vector.steps()));
        vectorPayload.put("traceStepCount", vector.steps().size());
        vectorPayload.put("queryProjection", List.of(vector.queryProjection()[0], vector.queryProjection()[1]));
        vectorPayload.put("ranking", rankingPayload(vector));
        vectorPayload.put("scoredChunkCount", vector.scoredChunkCount());
        vectorPayload.put("stats", statsPayload(comparison.vector().stats()));
        vectorPayload.put("retrieved", comparison.vectorRetrieved().stream()
                .map(passage -> Map.<String, Object>of(
                        "chunkId", passage.id(),
                        "documentName", passage.documentName(),
                        "excerpt", passage.excerpt(),
                        "sharedWithGraph", passage.shared()))
                .toList());

        List<Map<String, Object>> vectorOverlap = new java.util.ArrayList<>();
        for (int i = 0; i < vector.citations().size(); i++) {
            vectorOverlap.add(Map.of(
                    "chunkId", vector.citations().get(i).textUnitId(),
                    "sharedWithGraph", comparison.vectorCitationShared().get(i)));
        }
        List<Map<String, Object>> graphOverlap = new java.util.ArrayList<>();
        for (int i = 0; i < graph.citations().size(); i++) {
            graphOverlap.add(Map.of(
                    "textUnitId", graph.citations().get(i).textUnitId(),
                    "sharedWithVector", comparison.graphCitationShared().get(i)));
        }

        ComparisonVerdict verdict = comparison.verdict();
        return Map.of(
                "question", comparison.question() == null ? "" : comparison.question(),
                "graph", graphPayload,
                "vector", vectorPayload,
                "overlap", Map.of(
                        "vector", vectorOverlap,
                        "graph", graphOverlap,
                        "sharedPassages", comparison.facts().sharedPassages(),
                        "vectorPassages", comparison.facts().vectorPassages()),
                "verdict", Map.of(
                        "text", verdict.text(),
                        "source", verdict.source() == ComparisonVerdict.Source.LLM ? "llm" : "rule"));
    }

    private static Map<String, Object> statsPayload(ComparisonStats stats) {
        return Map.of(
                "contextItems", stats.contextItems(),
                "distinctDocuments", stats.distinctDocuments(),
                "latencyMs", stats.latencyMs());
    }
}
