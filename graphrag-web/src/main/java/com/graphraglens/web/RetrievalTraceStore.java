package com.graphraglens.web;

import io.graphrag.core.domain.RetrievalTrace;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of {@link RetrievalTrace}s, keyed by {@code traceId}.
 * Follows the same pattern as {@link CorpusStore}: held in memory only, no
 * disk/Neo4j persistence (AD-5), matching NFR3's local-only,
 * no-restart-durability scope.
 */
@Component
public class RetrievalTraceStore {

    private final ConcurrentHashMap<String, RetrievalTrace> traces = new ConcurrentHashMap<>();

    public void put(String traceId, RetrievalTrace trace) {
        traces.put(traceId, trace);
    }

    public Optional<RetrievalTrace> get(String traceId) {
        return Optional.ofNullable(traces.get(traceId));
    }

    public int size() {
        return traces.size();
    }
}
