package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.usecase.AnswerDriftSearch;
import dev.rabauer.graphrag.core.usecase.AnswerGlobalSearch;
import dev.rabauer.graphrag.core.usecase.AnswerLocalSearch;
import dev.rabauer.graphrag.core.usecase.AnswerVectorBaseline;
import dev.rabauer.graphrag.core.usecase.DriftSearchAnswer;
import dev.rabauer.graphrag.core.usecase.GlobalSearchAnswer;
import dev.rabauer.graphrag.core.usecase.LocalSearchAnswer;
import dev.rabauer.graphrag.core.usecase.VectorBaselineAnswer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.rabauer.graphrag.web.QuerySupport.rankingPayload;

/**
 * Asks one question of a corpus in one retrieval mode (LOCAL, GLOBAL, DRIFT or
 * the VECTOR baseline) and returns the cited answer with its trace id.
 */
@RestController
public class CorpusQueryController extends CorpusApiController {

    private final Neo4jCorpusRegistry corpusStore;
    private final LlmPort llmPort;
    private final GraphStorePort graphStorePort;
    private final AnswerVectorBaseline answerVectorBaseline;
    private final EmbeddingPort embeddingPort;
    private final QuerySupport support;

    public CorpusQueryController(Neo4jCorpusRegistry corpusStore, LlmPort llmPort, GraphStorePort graphStorePort,
                                 RetrievalTraceStore retrievalTraceStore, AnswerVectorBaseline answerVectorBaseline,
                                 EmbeddingPort embeddingPort) {
        this.corpusStore = corpusStore;
        this.llmPort = llmPort;
        this.graphStorePort = graphStorePort;
        this.answerVectorBaseline = answerVectorBaseline;
        this.embeddingPort = embeddingPort;
        this.support = new QuerySupport(corpusStore, retrievalTraceStore);
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
        ResponseEntity<Map<String, Object>> blocked = support.queryBlocked(corpus);
        if (blocked != null) {
            return blocked;
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
        String traceId = support.captureTrace(result.steps());

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
                "citations", result.citations().stream().map(QuerySupport::citationPayload).toList()));
    }

    /**
     * Story 15.3: like {@link #localSearchResponse}, a synthesizing
     * {@link #llmPort} generates a cited answer; a successful answer always
     * carries {@code citations} ({@code []} for the templated answer).
     */
    private ResponseEntity<Map<String, Object>> globalSearchResponse(String question, String corpusId) {
        GlobalSearchAnswer result = new AnswerGlobalSearch(graphStorePort, embeddingPort, llmPort)
                .answer(question, corpusId);
        String traceId = support.captureTrace(result.steps());

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
                "citations", result.citations().stream().map(QuerySupport::citationPayload).toList()));
    }

    /** Story 15.3: as {@link #globalSearchResponse}, with one synthesis over all branch contexts. */
    private ResponseEntity<Map<String, Object>> driftSearchResponse(String question, String corpusId) {
        DriftSearchAnswer result = new AnswerDriftSearch(graphStorePort, llmPort, embeddingPort).answer(question, corpusId);
        String traceId = support.captureTrace(result.steps());

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
                "citations", result.citations().stream().map(QuerySupport::citationPayload).toList()));
    }

    private ResponseEntity<Map<String, Object>> vectorBaselineResponse(String question, String corpusId) {
        VectorBaselineAnswer result = answerVectorBaseline.answer(question, corpusId);
        String traceId = support.captureTrace(result.steps());

        if (result.noChunks()) {
            return ResponseEntity.ok(Map.of(
                    "answerId", UUID.randomUUID().toString(),
                    "traceId", traceId,
                    "traceStepCount", result.steps().size(),
                    "noAnswer", true,
                    "reason", result.reason()));
        }
        if (result.noAnswer()) {
            // Not in context: chunks were retrieved, so the replay can still place the query.
            return ResponseEntity.ok(Map.of(
                    "answerId", UUID.randomUUID().toString(),
                    "traceId", traceId,
                    "traceStepCount", result.steps().size(),
                    "noAnswer", true,
                    "reason", result.reason(),
                    "mode", "VECTOR",
                    "queryProjection", List.of(result.queryProjection()[0], result.queryProjection()[1]),
                    "ranking", rankingPayload(result),
                    "scoredChunkCount", result.scoredChunkCount()));
        }

        return ResponseEntity.ok(Map.of(
                "answerId", UUID.randomUUID().toString(),
                "traceId", traceId,
                "traceStepCount", result.steps().size(),
                "answer", result.answer(),
                "mode", "VECTOR",
                "queryProjection", List.of(result.queryProjection()[0], result.queryProjection()[1]),
                "citations", result.citations().stream().map(QuerySupport::chunkCitationPayload).toList(),
                "ranking", rankingPayload(result),
                "scoredChunkCount", result.scoredChunkCount()));
    }
}
