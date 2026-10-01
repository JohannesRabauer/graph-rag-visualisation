package dev.rabauer.graphrag.core.domain;

import java.util.Locale;

/**
 * The measured facts a GraphRAG-vs-Vector verdict is grounded in: both
 * sides' key figures and how many retrieved passages they share.
 *
 * @param graphMode       the GraphRAG mode that ran ({@code LOCAL},
 *                        {@code GLOBAL} or {@code DRIFT}); never null
 * @param graph           the GraphRAG side's key figures; never null
 * @param vector          the Vector Search side's key figures; never null
 * @param vectorPassages  how many passages Vector Search retrieved
 * @param sharedPassages  how many of those are contained in a passage the
 *                        GraphRAG side also retrieved (same document)
 */
public record ComparisonFacts(String graphMode, ComparisonStats graph, ComparisonStats vector,
                              int vectorPassages, int sharedPassages) {

    public ComparisonFacts {
        graphMode = graphMode == null || graphMode.isBlank() ? "LOCAL" : graphMode.trim().toUpperCase(Locale.ROOT);
        graph = graph == null ? new ComparisonStats(0, 0, 0) : graph;
        vector = vector == null ? new ComparisonStats(0, 0, 0) : vector;
        vectorPassages = Math.max(0, vectorPassages);
        sharedPassages = Math.max(0, Math.min(sharedPassages, vectorPassages));
    }
}
