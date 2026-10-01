package dev.rabauer.graphrag.adapter.neo4j;

import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.UploadedDocument;

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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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

    /**
     * Reconciliation counts every BUILDING corpus in the database, and other
     * tests in this class leave corpora BUILDING in the shared container, so
     * the reconcile tests start from an empty registry.
     */
    private static void deleteAllCorpusMeta() {
        try (var session = driver.session()) {
            session.run("MATCH (c:CorpusMeta) DETACH DELETE c").consume();
        }
    }

    @Test
    void reconcileInterruptedCorporaFlipsOnlyBuildingCorporaToFailed() {
        deleteAllCorpusMeta();
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(driver);

        Corpus interrupted = newCorpus("corpus-building-" + System.nanoTime());
        registry.put(interrupted); // left BUILDING, as if by a crash mid-ingestion

        Corpus ready = newCorpus("corpus-ready-" + System.nanoTime());
        registry.put(ready);
        registry.markReady(ready.id());

        Corpus failed = newCorpus("corpus-failed-" + System.nanoTime());
        registry.put(failed);
        registry.markFailed(failed.id());

        long reconciledCount = registry.reconcileInterruptedCorpora();

        assertEquals(1, reconciledCount);
        assertEquals(Neo4jCorpusRegistry.CorpusWorkflowStatus.FAILED, registry.status(interrupted.id()));
        assertEquals(Neo4jCorpusRegistry.CorpusWorkflowStatus.READY, registry.status(ready.id()));
        assertEquals(Neo4jCorpusRegistry.CorpusWorkflowStatus.FAILED, registry.status(failed.id()));
    }

    @Test
    void reconcileInterruptedCorporaIsANoOpWhenNoCorporaAreBuilding() {
        deleteAllCorpusMeta();
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(driver);
        Corpus ready = newCorpus("corpus-ready-only-" + System.nanoTime());
        registry.put(ready);
        registry.markReady(ready.id());

        long reconciledCount = registry.reconcileInterruptedCorpora();

        assertEquals(0, reconciledCount);
        assertEquals(Neo4jCorpusRegistry.CorpusWorkflowStatus.READY, registry.status(ready.id()));
    }

    /**
     * The shared {@link #driver} accumulates {@code CorpusMeta} nodes across
     * every test in this class, so ordering/exclusion assertions below filter
     * {@link Neo4jCorpusRegistry#list()}'s full result down to just the ids
     * each test itself created, rather than asserting on the list's overall
     * size or contents.
     */
    private static List<Neo4jCorpusRegistry.CorpusSummary> listFiltered(Neo4jCorpusRegistry registry, String... ids) {
        List<String> wanted = List.of(ids);
        return registry.list().stream().filter(summary -> wanted.contains(summary.corpusId())).toList();
    }

    @Test
    void listOrdersMostRecentlyActivatedFirst() throws InterruptedException {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(driver);
        Corpus a = newCorpus("corpus-list-a-" + System.nanoTime());
        Corpus b = newCorpus("corpus-list-b-" + System.nanoTime());
        Corpus c = newCorpus("corpus-list-c-" + System.nanoTime());
        registry.put(a);
        registry.put(b);
        registry.put(c);

        registry.activate(a.id());
        Thread.sleep(5);
        registry.activate(b.id());
        Thread.sleep(5);
        registry.activate(c.id());

        List<Neo4jCorpusRegistry.CorpusSummary> filtered = listFiltered(registry, a.id(), b.id(), c.id());
        assertEquals(3, filtered.size());
        assertEquals(c.id(), filtered.get(0).corpusId());
        assertEquals(b.id(), filtered.get(1).corpusId());
        assertEquals(a.id(), filtered.get(2).corpusId());
    }

    @Test
    void activatingAnEarlierCorpusMovesItBackToTheFront() throws InterruptedException {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(driver);
        Corpus a = newCorpus("corpus-reactivate-a-" + System.nanoTime());
        Corpus b = newCorpus("corpus-reactivate-b-" + System.nanoTime());
        registry.put(a);
        registry.put(b);
        registry.activate(b.id());

        Thread.sleep(5);
        registry.activate(a.id());

        List<Neo4jCorpusRegistry.CorpusSummary> filtered = listFiltered(registry, a.id(), b.id());
        assertEquals(a.id(), filtered.get(0).corpusId());
        assertEquals(b.id(), filtered.get(1).corpusId());
    }

    @Test
    void listExcludesOfflineCorpora() {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(driver);
        Corpus regular = newCorpus("corpus-online-" + System.nanoTime());
        Corpus offline = newCorpus("corpus-offline-list-" + System.nanoTime());
        registry.put(regular);
        registry.put(offline);
        registry.markOffline(offline.id());

        List<Neo4jCorpusRegistry.CorpusSummary> filtered = listFiltered(registry, regular.id(), offline.id());

        assertEquals(1, filtered.size());
        assertEquals(regular.id(), filtered.get(0).corpusId());
    }

    @Test
    void activateSetsLastActivatedAtToATimestampAfterCreation() throws InterruptedException {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(driver);
        Corpus corpus = newCorpus("corpus-activate-ts-" + System.nanoTime());
        registry.put(corpus);
        String createdLastActivatedAt = listFiltered(registry, corpus.id()).get(0).lastActivatedAt();

        Thread.sleep(5);
        registry.activate(corpus.id());

        String updatedLastActivatedAt = listFiltered(registry, corpus.id()).get(0).lastActivatedAt();
        assertTrue(updatedLastActivatedAt.compareTo(createdLastActivatedAt) > 0);
    }

    @Test
    void activateSilentlyNoOpsForAnUnknownCorpusId() {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(driver);

        assertDoesNotThrow(() -> registry.activate("nonexistent-" + System.nanoTime()));
    }
}
