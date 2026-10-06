package dev.rabauer.graphrag.core.port;

import dev.rabauer.graphrag.core.community.GraphCommunities;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;

import java.util.List;

/**
 * Port for persisting and querying the knowledge graph: the
 * {@link GraphReadPort} (what the query use cases need) and the
 * {@link GraphWritePort} (persisting, detecting) in one. Every method that
 * existed here before the split is inherited unchanged, so existing
 * implementations and callers keep compiling.
 *
 * <p>Implement {@link GraphReadPort} alone to run the queries over an
 * existing graph; implement this port to also ingest, import and detect
 * Communities.
 */
public interface GraphStorePort extends GraphReadPort, GraphWritePort {

    /**
     * Groups the Entities of {@code corpusId} into Communities and returns each
     * group as a list of member Entity identities (the
     * {@link Entity#normalizedIdentity()} / {@link Entity#identityOf(String, String)}
     * format). Every Entity of the corpus appears in exactly one group; an
     * Entity without relationships is its own single-member group.
     *
     * <p>The default implementation runs the core's deterministic,
     * modularity-based detector ({@link GraphCommunities#defaultDetector()}:
     * Louvain with a Leiden-style connectivity refinement, seed 42, resolution
     * 1.0, weighted by {@link Relationship#weight()}) over
     * {@link #entities(String)} and {@link #relationships(String)}, so any
     * store gets modularity-based Communities without a graph-algorithm
     * plugin. Groups and members are in the order of {@link #entities(String)}.
     * Graph stores with a native algorithm (for example GDS Leiden) may
     * override it; callers must not rely on the order an override returns. The
     * former connected-components grouping is available as
     * {@link dev.rabauer.graphrag.core.community.ConnectedComponentsCommunityDetector}.</p>
     *
     * @param corpusId the corpus whose Entities are grouped
     * @return the member-identity groups; empty when the corpus has no Entities
     */
    @Override
    default List<List<String>> detectCommunities(String corpusId) {
        return GraphCommunities.detect(entities(corpusId), relationships(corpusId));
    }
}
