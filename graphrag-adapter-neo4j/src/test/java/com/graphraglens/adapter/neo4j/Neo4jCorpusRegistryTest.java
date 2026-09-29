package com.graphraglens.adapter.neo4j;

import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.UploadedDocument;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testcontainers-backed tests proving {@link Neo4jCorpusRegistry} behaves
 * correctly against a real Neo4j instance, not just that it compiles.
 * Mirrors {@link Neo4jGraphStoreAdapterTest}'s Testcontainers setup.
 */
@Testcontainers
class Neo4jCorpusRegistryTest {

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

    private static Corpus newCorpus(String id) {
        return new Corpus(id, List.of(new UploadedDocument("a.txt", "content"), new UploadedDocument("b.txt", "content")));
    }

    @Test
    void putThenGetReturnsTheCorpusWithBuildingStatus() {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(driver);
        Corpus corpus = newCorpus("corpus-" + System.nanoTime());

        registry.put(corpus);

        Optional<Corpus> stored = registry.get(corpus.id());
        assertTrue(stored.isPresent());
        assertEquals(corpus.id(), stored.get().id());
        assertEquals(corpus.name(), stored.get().name());
        assertEquals(corpus.documentNames(), stored.get().documentNames());
        assertEquals(Neo4jCorpusRegistry.CorpusWorkflowStatus.BUILDING, registry.status(corpus.id()));
    }

    @Test
    void markReadyAndMarkFailedTransitionStatus() {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(driver);
        Corpus corpus = newCorpus("corpus-" + System.nanoTime());
        registry.put(corpus);

        registry.markReady(corpus.id());
        assertEquals(Neo4jCorpusRegistry.CorpusWorkflowStatus.READY, registry.status(corpus.id()));

        registry.markFailed(corpus.id());
        assertEquals(Neo4jCorpusRegistry.CorpusWorkflowStatus.FAILED, registry.status(corpus.id()));
    }

    @Test
    void unknownCorpusReturnsEmptyAndDefaultsToBuildingStatus() {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(driver);

        assertFalse(registry.get("nonexistent-" + System.nanoTime()).isPresent());
        assertEquals(Neo4jCorpusRegistry.CorpusWorkflowStatus.BUILDING,
                registry.status("nonexistent-" + System.nanoTime()));
    }

    @Test
    void aFreshRegistryInstanceReadsBackTheSameDataAfterASimulatedRestart() {
        Corpus corpus = newCorpus("corpus-restart-" + System.nanoTime());
        Neo4jCorpusRegistry first = new Neo4jCorpusRegistry(driver);
        first.put(corpus);
        first.markReady(corpus.id());

        Neo4jCorpusRegistry restarted = new Neo4jCorpusRegistry(driver);

        Optional<Corpus> stored = restarted.get(corpus.id());
        assertTrue(stored.isPresent());
        assertEquals(corpus.name(), stored.get().name());
        assertEquals(Neo4jCorpusRegistry.CorpusWorkflowStatus.READY, restarted.status(corpus.id()));
    }

    @Test
    void puttingTheSameCorpusIdTwiceNeverDuplicatesTheCorpusMetaNode() {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(driver);
        Corpus corpus = newCorpus("corpus-" + System.nanoTime());

        registry.put(corpus);
        registry.put(corpus);

        Optional<Corpus> stored = registry.get(corpus.id());
        assertTrue(stored.isPresent());
        assertEquals(corpus.id(), stored.get().id());
    }

    @Test
    void offlineFlagNeverPersistsAcrossAFreshRegistryInstance() {
        String corpusId = "corpus-offline-" + System.nanoTime();
        Neo4jCorpusRegistry first = new Neo4jCorpusRegistry(driver);
        first.markOffline(corpusId);
        assertTrue(first.isOffline(corpusId));

        Neo4jCorpusRegistry restarted = new Neo4jCorpusRegistry(driver);

        assertFalse(restarted.isOffline(corpusId));
    }
}
