package com.graphraglens.web;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A JVM-wide singleton Testcontainers-managed Neo4j instance shared by every
 * {@code @SpringBootTest}-based test in this module ({@link CorpusControllerTest}
 * and every {@code UiTestSupport}-based Playwright UI test), so each of the
 * ~15+ test classes doesn't start (and tear down) its own container.
 *
 * <p>Now that {@code ParserConfig}'s {@code graphStorePort()}/
 * {@code vectorStorePort()} beans are real Neo4j-backed adapters (Story
 * 12.3), every Spring context in this module needs a reachable Neo4j just to
 * start. Deliberately <b>not</b> annotated {@code @Container}/
 * {@code @Testcontainers} — that annotation pair lifecycle-manages a
 * container per test class (started/stopped around each class), which for
 * this many Spring-context test classes would make the suite unacceptably
 * slow. Instead this is the standard Testcontainers "singleton container"
 * pattern: a {@code static final} field started once, in a static
 * initializer block, and left running for the JVM's lifetime — Testcontainers'
 * own Ryuk resource reaper cleans it up once the JVM exits.
 */
public final class SharedNeo4jTestContainer {

    private static final String USERNAME = "neo4j";
    private static final String PASSWORD = "test-password";

    static final Neo4jContainer<?> INSTANCE =
            new Neo4jContainer<>(DockerImageName.parse("neo4j:2026.08.1-community"))
                    .withAdminPassword(PASSWORD)
                    .withReuse(true);

    private static volatile Driver driver;

    static {
        try {
            INSTANCE.start();
        } catch (RuntimeException e) {
            // Without this, a failure here (e.g. Docker not running/reachable)
            // surfaces to every other @SpringBootTest class in the JVM as an
            // opaque ExceptionInInitializerError/NoClassDefFoundError, with no
            // indication that a Neo4j test container is even involved.
            throw new IllegalStateException(
                    "Failed to start the shared Neo4j test container. Every @SpringBootTest-based "
                            + "test in this module needs a reachable Docker daemon for this to succeed "
                            + "-- check that Docker is running and reachable.", e);
        }
    }

    private SharedNeo4jTestContainer() {
    }

    /**
     * Registers {@code NEO4J_URI}/{@code NEO4J_USERNAME}/{@code NEO4J_PASSWORD}
     * against this shared container, for use from a test class's own
     * {@code @DynamicPropertySource}-annotated static method. Runs before
     * context refresh, so the real {@code Driver} bean and
     * {@code Neo4jConnectivityCheck} both see a reachable Neo4j well before
     * {@code ApplicationReadyEvent} fires.
     */
    public static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("NEO4J_URI", INSTANCE::getBoltUrl);
        registry.add("NEO4J_USERNAME", () -> USERNAME);
        registry.add("NEO4J_PASSWORD", () -> PASSWORD);
    }

    /**
     * A JVM-wide singleton {@link Driver} against this shared container, for
     * plain, non-Spring test classes (constructing a {@code
     * Neo4jCorpusRegistry} directly) that don't go through
     * {@link #registerDynamicProperties(DynamicPropertyRegistry)}'s
     * {@code @DynamicPropertySource}/Spring-context wiring.
     */
    public static Driver driver() {
        Driver result = driver;
        if (result == null) {
            synchronized (SharedNeo4jTestContainer.class) {
                result = driver;
                if (result == null) {
                    result = GraphDatabase.driver(INSTANCE.getBoltUrl(), AuthTokens.basic(USERNAME, PASSWORD));
                    driver = result;
                }
            }
        }
        return result;
    }
}
