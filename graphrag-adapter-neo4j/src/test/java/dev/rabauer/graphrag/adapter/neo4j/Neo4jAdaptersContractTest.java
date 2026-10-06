package dev.rabauer.graphrag.adapter.neo4j;

import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.testkit.CodeGraphRetrievalContract;
import dev.rabauer.graphrag.testkit.GraphStorePortContract;
import dev.rabauer.graphrag.testkit.VectorStorePortContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
}
