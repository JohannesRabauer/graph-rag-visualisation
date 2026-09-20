package com.graphraglens.core.domain;

import java.util.List;

/**
 * A captured record of what a single query's retrieval touched, in the
 * order it touched it.
 *
 * <p>Held in memory only by {@code graphrag-web}'s {@code RetrievalTraceStore}
 * — never written to Neo4j (AD-5). {@code steps} is deliberately a
 * {@link List}, never a {@link java.util.Set}: touch order is part of the
 * trace's meaning and must never be reordered or deduplicated away.
 */
public record RetrievalTrace(String traceId, List<RetrievalStep> steps) {

    public RetrievalTrace {
        traceId = traceId == null ? "" : traceId.trim();
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
