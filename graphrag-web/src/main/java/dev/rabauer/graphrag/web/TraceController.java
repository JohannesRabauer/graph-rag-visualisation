package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.RetrievalTrace;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Reads a stored retrieval trace (captured by the query and compare controllers). */
@RestController
public class TraceController extends CorpusApiController {

    private final RetrievalTraceStore retrievalTraceStore;

    public TraceController(RetrievalTraceStore retrievalTraceStore) {
        this.retrievalTraceStore = retrievalTraceStore;
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
}
