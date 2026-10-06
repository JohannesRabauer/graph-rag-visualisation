package dev.rabauer.graphrag.adapter.neo4j;

import dev.rabauer.graphrag.core.community.CommunityDetector;
import dev.rabauer.graphrag.core.community.GraphCommunities;

import java.util.Objects;

/**
 * How a {@link Neo4jGraphStoreAdapter} detects Communities.
 *
 * @param communityDetection     {@link CommunityDetection#GDS_LEIDEN} (needs the
 *                               Graph Data Science plugin) or
 *                               {@link CommunityDetection#CORE} (the core's
 *                               detector, plain Neo4j)
 * @param fallbackToCoreDetector with GDS Leiden: whether a GDS failure (plugin
 *                               missing, Leiden failing) falls back to the core
 *                               detector with a logged warning instead of
 *                               failing; off by default, so a broken GDS setup
 *                               stays visible
 * @param coreDetector           the detector used by {@code CORE} and the
 *                               fallback; defaults to
 *                               {@link GraphCommunities#defaultDetector()}
 */
public record Neo4jGraphStoreOptions(CommunityDetection communityDetection, boolean fallbackToCoreDetector,
                                     CommunityDetector coreDetector) {

    public Neo4jGraphStoreOptions {
        communityDetection = communityDetection == null ? CommunityDetection.GDS_LEIDEN : communityDetection;
        coreDetector = coreDetector == null ? GraphCommunities.defaultDetector() : coreDetector;
    }

    /** GDS Leiden, failing hard when GDS fails: the adapter's behaviour so far. */
    public static Neo4jGraphStoreOptions defaults() {
        return new Neo4jGraphStoreOptions(CommunityDetection.GDS_LEIDEN, false, null);
    }

    public Neo4jGraphStoreOptions withCommunityDetection(CommunityDetection value) {
        return new Neo4jGraphStoreOptions(value, fallbackToCoreDetector, coreDetector);
    }

    public Neo4jGraphStoreOptions withFallbackToCoreDetector(boolean value) {
        return new Neo4jGraphStoreOptions(communityDetection, value, coreDetector);
    }

    public Neo4jGraphStoreOptions withCoreDetector(CommunityDetector value) {
        return new Neo4jGraphStoreOptions(communityDetection, fallbackToCoreDetector,
                Objects.requireNonNull(value, "coreDetector"));
    }

    /** Where the adapter's Community grouping comes from. */
    public enum CommunityDetection {
        /** GDS Leiden over a corpus-scoped projection (requires the GDS plugin). */
        GDS_LEIDEN,
        /** The core's pure-Java detector over the corpus's Entities and Relationships (plain Neo4j). */
        CORE
    }
}
