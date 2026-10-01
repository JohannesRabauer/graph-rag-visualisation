package dev.rabauer.graphrag.core.domain;

/**
 * Key figures of one side of a GraphRAG-vs-Vector comparison.
 *
 * @param contextItems      how many context items the side retrieved: for
 *                          GraphRAG its {@code ENTITY}, {@code RELATIONSHIP},
 *                          {@code COMMUNITY} and {@code TEXT_UNIT} steps, for
 *                          Vector Search its {@code VECTOR_CHUNK} steps
 * @param distinctDocuments how many distinct documents the side's retrieved
 *                          passages come from
 * @param latencyMs         wall-clock time the side took, in milliseconds
 */
public record ComparisonStats(int contextItems, int distinctDocuments, long latencyMs) {

    public ComparisonStats {
        contextItems = Math.max(0, contextItems);
        distinctDocuments = Math.max(0, distinctDocuments);
        latencyMs = Math.max(0L, latencyMs);
    }
}
