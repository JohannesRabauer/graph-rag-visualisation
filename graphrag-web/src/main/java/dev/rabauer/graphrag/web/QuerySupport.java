package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.RetrievalTrace;
import dev.rabauer.graphrag.core.usecase.VectorBaselineAnswer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the query and compare controllers share: the 409 gate, trace capture
 * and the citation and ranking payload shapes.
 */
final class QuerySupport {

    private final Neo4jCorpusRegistry corpusStore;
    private final RetrievalTraceStore retrievalTraceStore;

    QuerySupport(Neo4jCorpusRegistry corpusStore, RetrievalTraceStore retrievalTraceStore) {
        this.corpusStore = corpusStore;
        this.retrievalTraceStore = retrievalTraceStore;
    }

    /**
     * The 409 a query on {@code corpus} gets while it is the offline demo,
     * still building or failed; null when it may be queried.
     */
    ResponseEntity<Map<String, Object>> queryBlocked(Corpus corpus) {
        if (corpusStore.isOffline(corpus.id())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", CorpusMessages.OFFLINE_QUERY_BLOCKED_MESSAGE));
        }
        Neo4jCorpusRegistry.CorpusWorkflowStatus workflowStatus = corpusStore.status(corpus.id());
        if (workflowStatus == Neo4jCorpusRegistry.CorpusWorkflowStatus.BUILDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", CorpusMessages.GRAPH_BUILDING_MESSAGE));
        }
        if (workflowStatus == Neo4jCorpusRegistry.CorpusWorkflowStatus.FAILED) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", CorpusMessages.GRAPH_FAILED_MESSAGE));
        }
        return null;
    }

    /**
     * Generates a fresh {@code traceId} and stores a {@link RetrievalTrace} for
     * the given steps under it — shared by every query path so a trace is
     * always captured (even zero-step) before the response is built
     * (Story 5.1 AC2).
     */
    String captureTrace(List<RetrievalStep> steps) {
        String traceId = UUID.randomUUID().toString();
        retrievalTraceStore.put(traceId, new RetrievalTrace(traceId, steps));
        return traceId;
    }

    static Map<String, Object> citationPayload(Citation citation) {
        return Map.of(
                "textUnitId", citation.textUnitId(),
                "documentName", citation.documentName(),
                "excerpt", citation.excerpt());
    }

    /** A vector citation: {@link Citation#textUnitId()} holds the cited chunk's id. */
    static Map<String, Object> chunkCitationPayload(Citation citation) {
        return Map.of(
                "chunkId", citation.textUnitId(),
                "documentName", citation.documentName(),
                "excerpt", citation.excerpt());
    }

    /**
     * The vector side's similarity ranking: the highest-scoring chunks in
     * descending score order, {@code used} for the top-k that feed the answer.
     */
    static List<Map<String, Object>> rankingPayload(VectorBaselineAnswer answer) {
        return answer.ranking().stream()
                .map(row -> Map.<String, Object>of(
                        "rank", row.rank(),
                        "chunkId", row.chunkId(),
                        "documentName", row.documentName(),
                        "excerpt", row.excerpt(),
                        "score", row.score(),
                        "used", row.used()))
                .toList();
    }
}
