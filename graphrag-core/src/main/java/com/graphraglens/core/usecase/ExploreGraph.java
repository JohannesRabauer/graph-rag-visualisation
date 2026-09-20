package com.graphraglens.core.usecase;

import com.graphraglens.core.port.GraphStorePort;

import java.util.Collection;
import java.util.List;

/**
 * Reads the full Knowledge Graph — every persisted Entity, Relationship,
 * Community, and Community membership — for Story 6.1's Explore page.
 *
 * <p>This use case only reads {@link GraphStorePort#entities()}/
 * {@link GraphStorePort#relationships()}/{@link GraphStorePort#communities()}/
 * {@link GraphStorePort#communityMemberships()} (no new port method, no
 * Cypher). It never waits for, locks against, or errors on in-flight
 * ingestion (AD-14 philosophy already established by Local/Global Search and
 * Story 4.3's live canvas): it simply reflects whatever the store currently
 * holds, which may be empty or partial.
 *
 * <p>{@link GraphStorePort} is a process-global, unscoped store: it is not
 * filtered by any particular corpus. "The full Knowledge Graph" this use
 * case reads is therefore the same single shared graph every other read
 * path already sees, not scoped to any one corpus — an accepted,
 * pre-existing limitation (same as Stories 4.3/4.4/5.1/5.2), not a new gap
 * introduced here.
 */
public class ExploreGraph {

    private final GraphStorePort graphStorePort;

    public ExploreGraph(GraphStorePort graphStorePort) {
        this.graphStorePort = graphStorePort;
    }

    public ExploreGraphResult explore() {
        return new ExploreGraphResult(
                List.copyOf(orEmpty(graphStorePort.entities())),
                List.copyOf(orEmpty(graphStorePort.relationships())),
                List.copyOf(orEmpty(graphStorePort.communities())),
                List.copyOf(orEmpty(graphStorePort.communityMemberships())));
    }

    private static <T> Collection<T> orEmpty(Collection<T> collection) {
        return collection == null ? List.of() : collection;
    }
}
