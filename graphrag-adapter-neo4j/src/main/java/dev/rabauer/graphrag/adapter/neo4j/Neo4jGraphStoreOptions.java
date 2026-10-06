package dev.rabauer.graphrag.adapter.neo4j;

import dev.rabauer.graphrag.core.community.CommunityDetector;
import dev.rabauer.graphrag.core.community.GraphCommunities;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * How a {@link Neo4jGraphStoreAdapter} names its schema and detects Communities.
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
 * @param labelPrefix            prefix of every node label (and, lower-cased with
 *                               {@code _}, of every constraint, vector index and
 *                               projection name); {@code ""} by default, e.g.
 *                               {@code GraphRag} for {@code :GraphRagEntity}
 * @param relationshipTypePrefix prefix of every relationship type; {@code ""} by
 *                               default, e.g. {@code GRAPHRAG_} for
 *                               {@code :GRAPHRAG_RELATIONSHIP}
 * @param createConstraints      whether the adapter declares its uniqueness
 *                               constraints on start (off when the schema is
 *                               managed elsewhere, e.g. by migrations)
 */
public record Neo4jGraphStoreOptions(CommunityDetection communityDetection, boolean fallbackToCoreDetector,
                                     CommunityDetector coreDetector, String labelPrefix,
                                     String relationshipTypePrefix, boolean createConstraints) {

    /** Letters, digits and underscores, starting with a letter: safe to splice into Cypher. */
    private static final Pattern IDENTIFIER_PREFIX = Pattern.compile("([A-Za-z][A-Za-z0-9_]*)?");

    public Neo4jGraphStoreOptions {
        communityDetection = communityDetection == null ? CommunityDetection.GDS_LEIDEN : communityDetection;
        coreDetector = coreDetector == null ? GraphCommunities.defaultDetector() : coreDetector;
        labelPrefix = requireIdentifierPrefix(labelPrefix, "labelPrefix");
        relationshipTypePrefix = requireIdentifierPrefix(relationshipTypePrefix, "relationshipTypePrefix");
    }

    /** A {@code (communityDetection, fallbackToCoreDetector, coreDetector)} configuration without prefixes. */
    public Neo4jGraphStoreOptions(CommunityDetection communityDetection, boolean fallbackToCoreDetector,
                                  CommunityDetector coreDetector) {
        this(communityDetection, fallbackToCoreDetector, coreDetector, "", "", true);
    }

    /** GDS Leiden failing hard, no prefixes, constraints declared: the adapter's behaviour so far. */
    public static Neo4jGraphStoreOptions defaults() {
        return new Neo4jGraphStoreOptions(CommunityDetection.GDS_LEIDEN, false, null, "", "", true);
    }

    public Neo4jGraphStoreOptions withCommunityDetection(CommunityDetection value) {
        return new Neo4jGraphStoreOptions(value, fallbackToCoreDetector, coreDetector, labelPrefix,
                relationshipTypePrefix, createConstraints);
    }

    public Neo4jGraphStoreOptions withFallbackToCoreDetector(boolean value) {
        return new Neo4jGraphStoreOptions(communityDetection, value, coreDetector, labelPrefix,
                relationshipTypePrefix, createConstraints);
    }

    public Neo4jGraphStoreOptions withCoreDetector(CommunityDetector value) {
        return new Neo4jGraphStoreOptions(communityDetection, fallbackToCoreDetector,
                Objects.requireNonNull(value, "coreDetector"), labelPrefix, relationshipTypePrefix, createConstraints);
    }

    /** Prefixes labels with {@code labelPrefix} and relationship types with {@code relationshipTypePrefix}. */
    public Neo4jGraphStoreOptions withPrefixes(String labelPrefix, String relationshipTypePrefix) {
        return new Neo4jGraphStoreOptions(communityDetection, fallbackToCoreDetector, coreDetector, labelPrefix,
                relationshipTypePrefix, createConstraints);
    }

    public Neo4jGraphStoreOptions withCreateConstraints(boolean value) {
        return new Neo4jGraphStoreOptions(communityDetection, fallbackToCoreDetector, coreDetector, labelPrefix,
                relationshipTypePrefix, value);
    }

    private static String requireIdentifierPrefix(String prefix, String name) {
        String value = prefix == null ? "" : prefix;
        if (!IDENTIFIER_PREFIX.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be empty or start with a letter and contain only "
                    + "letters, digits and underscores, was '" + value + "'");
        }
        return value;
    }

    /** Where the adapter's Community grouping comes from. */
    public enum CommunityDetection {
        /** GDS Leiden over a corpus-scoped projection (requires the GDS plugin). */
        GDS_LEIDEN,
        /** The core's pure-Java detector over the corpus's Entities and Relationships (plain Neo4j). */
        CORE
    }
}
