package dev.rabauer.graphrag.adapter.neo4j;

import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.testkit.CodeGraphRetrievalContract;
import dev.rabauer.graphrag.testkit.GraphStorePortContract;
import dev.rabauer.graphrag.testkit.VectorStorePortContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import dev.rabauer.graphrag.testkit.ContractGraph;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Neo4j adapters meet the graphrag-core-testkit contracts against a real,
 * plain Neo4j (no GDS plugin): the graph store uses the core community
 * detector there.
 */
@Testcontainers
class Neo4jAdaptersContractTest {

    @Container
    private static final Neo4jContainer<?> NEO4J =
            new Neo4jContainer<>("neo4j:2026.08.1-community").withoutAuthentication();

    private static Driver driver;

    @BeforeAll
    static void startDriver() {
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.none());
    }

    @AfterAll
    static void stopDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    private static Neo4jGraphStoreAdapter graphStore() {
        return new Neo4jGraphStoreAdapter(driver, Neo4jGraphStoreOptions.defaults()
                .withCommunityDetection(Neo4jGraphStoreOptions.CommunityDetection.CORE));
    }

    @Nested
    class GraphStore extends GraphStorePortContract {
        @Override
        protected GraphStorePort newStore() {
            return graphStore();
        }
    }

    @Nested
    class VectorStore extends VectorStorePortContract {
        @Override
        protected VectorStorePort newStore() {
            return new Neo4jVectorStoreAdapter(driver);
        }
    }

    @Nested
    class CodeGraph extends CodeGraphRetrievalContract {
        @Override
        protected GraphStorePort newStore() {
            return graphStore();
        }
    }

    private static Neo4jGraphStoreAdapter prefixedGraphStore() {
        // Sessions from a supplier the caller owns, as with a framework-managed driver.
        return new Neo4jGraphStoreAdapter(() -> driver.session(), Neo4jGraphStoreOptions.defaults()
                .withCommunityDetection(Neo4jGraphStoreOptions.CommunityDetection.CORE)
                .withPrefixes("GraphRag", "GRAPHRAG_"));
    }

    /** The whole contract again with prefixed labels and types, through an injected session supplier. */
    @Nested
    class PrefixedGraphStore extends GraphStorePortContract {
        @Override
        protected GraphStorePort newStore() {
            return prefixedGraphStore();
        }
    }

    @Test
    void aPrefixedStoreSharesTheDatabaseWithAForeignGraphWithoutTouchingIt() {
        String corpusId = "shared-db-" + System.nanoTime();
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(
                    "CREATE (a:Java:Type {fqn: 'com.acme.Alpha', corpusId: $corpusId})"
                            + "-[:INVOKES]->(b:Java:Type {fqn: 'com.acme.Beta', corpusId: $corpusId}) "
                            + "CREATE (:Entity {corpusId: $corpusId, normalizedIdentity: 'foreign::thing', "
                            + "name: 'foreign', type: 'thing'})",
                    Map.of("corpusId", corpusId)).consume());
        }
        Neo4jGraphStoreAdapter store = prefixedGraphStore();
        ContractGraph graph = ContractGraph.create();

        store.persistTextUnits(corpusId, graph.textUnits());
        store.persistEntities(corpusId, graph.entities());
        store.persistRelationships(corpusId, graph.relationships());
        store.persistEntityEmbeddings(corpusId, Map.of(ContractGraph.identity(ContractGraph.ALPHA, "Class"),
                new float[] {1f, 0f, 0f}));
        store.detectCommunities(corpusId);
        store.deleteEntities(corpusId, List.of("foreign::thing"));
        store.deleteCommunities(corpusId);

        assertEquals(4, store.entities(corpusId).size());
        assertEquals(4L, count("MATCH (n:GraphRagEntity {corpusId: $corpusId}) RETURN count(n) AS count", corpusId));
        assertEquals(4L, count("MATCH ()-[r:GRAPHRAG_RELATIONSHIP {corpusId: $corpusId}]->() RETURN count(r) AS count",
                corpusId));
        assertEquals(1L, count("MATCH (n:Entity {corpusId: $corpusId}) RETURN count(n) AS count", corpusId));
        assertEquals(2L, count("MATCH (n:Java:Type {corpusId: $corpusId}) RETURN count(n) AS count", corpusId));
        assertEquals(1L, count("MATCH (:Java:Type {corpusId: $corpusId})-[r:INVOKES]->() RETURN count(r) AS count",
                corpusId));
        assertEquals(0L, count("MATCH (n:Entity {corpusId: $corpusId})-[r]-() RETURN count(r) AS count", corpusId));
        try (Session session = driver.session()) {
            List<String> indexes = session.executeRead(tx -> tx.run("SHOW VECTOR INDEXES YIELD name RETURN name")
                    .list(record -> record.get("name").asString()));
            assertTrue(indexes.contains("graphrag_entity_embedding"), indexes::toString);
        }
    }

    private static long count(String cypher, String corpusId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run(cypher, Map.of("corpusId", corpusId)).single()
                    .get("count").asLong());
        }
    }
}
