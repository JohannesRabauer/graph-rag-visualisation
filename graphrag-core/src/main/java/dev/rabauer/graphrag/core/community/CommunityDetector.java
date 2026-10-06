package dev.rabauer.graphrag.core.community;

import java.util.List;

/** Groups node ids into communities. */
public interface CommunityDetector {

    /**
     * Partitions {@code nodeIds} into communities.
     *
     * <p>Contract: every distinct non-null id of {@code nodeIds} appears in exactly
     * one returned group; no other ids appear. Groups are ordered by the index (in
     * {@code nodeIds}) of their first member; members keep {@code nodeIds} order.
     * Edge endpoints that are not in {@code nodeIds} may be used during optimisation
     * but are never returned. Must be deterministic: same input (same order) yields
     * the same output.
     *
     * @param nodeIds the ids to partition
     * @param edges   the undirected, weighted edges between them
     * @return the communities, never {@code null}
     */
    List<List<String>> detect(List<String> nodeIds, List<WeightedEdge> edges);
}
