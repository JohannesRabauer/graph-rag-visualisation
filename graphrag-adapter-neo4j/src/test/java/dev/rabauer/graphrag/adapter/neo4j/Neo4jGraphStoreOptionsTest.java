package dev.rabauer.graphrag.adapter.neo4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Neo4jGraphStoreOptionsTest {

    @Test
    void theDefaultsKeepTheAdaptersBehaviour() {
        Neo4jGraphStoreOptions defaults = Neo4jGraphStoreOptions.defaults();

        assertEquals(Neo4jGraphStoreOptions.CommunityDetection.GDS_LEIDEN, defaults.communityDetection());
        assertFalse(defaults.fallbackToCoreDetector());
        assertEquals("", defaults.labelPrefix());
        assertEquals("", defaults.relationshipTypePrefix());
        assertTrue(defaults.createConstraints());
    }

    @Test
    void prefixesMustBeSafeCypherIdentifiers() {
        Neo4jGraphStoreOptions defaults = Neo4jGraphStoreOptions.defaults();

        assertEquals("GraphRag", defaults.withPrefixes("GraphRag", "GRAPHRAG_").labelPrefix());
        assertEquals("", defaults.withPrefixes(null, null).labelPrefix());
        assertThrows(IllegalArgumentException.class, () -> defaults.withPrefixes("Graph Rag", ""));
        assertThrows(IllegalArgumentException.class, () -> defaults.withPrefixes("x`) DETACH DELETE n //", ""));
        assertThrows(IllegalArgumentException.class, () -> defaults.withPrefixes("", "1_"));
    }
}
