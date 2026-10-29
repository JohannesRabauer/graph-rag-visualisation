package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.core.domain.RetrievalTrace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory registry of {@link RetrievalTrace}s, keyed by {@code traceId}.
 * Held in memory only, no disk/Neo4j persistence (AD-5), matching NFR3's
 * local-only, no-restart-durability scope — unlike the corpus registry
 * itself, which moved to durable Neo4j-backed persistence in {@code
 * Neo4jCorpusRegistry} (Story 12.4).
 *
 * <p>The store is bounded: it keeps at most {@code graphrag.traces.max}
 * traces (default {@value #DEFAULT_MAX_TRACES}) and evicts the least recently
 * used one when a new trace would exceed that. Both {@link #put} and a
 * successful {@link #get} count as a use. An evicted trace is simply unknown
 * to {@link #get}, so {@code GET /api/traces/{id}} answers 404 for it.
 */
@Component
public class RetrievalTraceStore {

    public static final int DEFAULT_MAX_TRACES = 500;

    private final int maxTraces;
    private final Map<String, RetrievalTrace> traces;

    public RetrievalTraceStore() {
        this(DEFAULT_MAX_TRACES);
    }

    @Autowired
    public RetrievalTraceStore(@Value("${graphrag.traces.max:" + DEFAULT_MAX_TRACES + "}") int maxTraces) {
        if (maxTraces < 1) {
            throw new IllegalArgumentException("graphrag.traces.max must be at least 1, was " + maxTraces);
        }
        this.maxTraces = maxTraces;
        // access-ordered: iteration starts at the least recently used entry
        this.traces = new LinkedHashMap<>(16, 0.75f, true);
    }

    public synchronized void put(String traceId, RetrievalTrace trace) {
        traces.put(traceId, trace);
        while (traces.size() > maxTraces) {
            var eldest = traces.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
    }

    public synchronized Optional<RetrievalTrace> get(String traceId) {
        return Optional.ofNullable(traces.get(traceId));
    }

    public synchronized int size() {
        return traces.size();
    }

    public int maxTraces() {
        return maxTraces;
    }
}
