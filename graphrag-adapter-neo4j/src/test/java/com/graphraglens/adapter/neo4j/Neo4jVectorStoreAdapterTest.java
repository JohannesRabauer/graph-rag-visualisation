package com.graphraglens.adapter.neo4j;

import io.graphrag.core.domain.Chunk;
import io.graphrag.core.domain.EmbeddedChunk;
import io.graphrag.core.domain.ProjectionModel;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testcontainers-backed tests proving {@link Neo4jVectorStoreAdapter} behaves
 * correctly against a real Neo4j instance, not just that it compiles.
 */
@Testcontainers
class Neo4jVectorStoreAdapterTest {

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

    @Test
    void persistsChunksAndReadsThemBackForTheirCorpusOnly() {
        Neo4jVectorStoreAdapter adapter = new Neo4jVectorStoreAdapter(driver);
        String corpusId = "corpus-" + System.nanoTime();
        String otherCorpusId = "corpus-other-" + System.nanoTime();

        adapter.persistChunks(corpusId, List.of(embeddedChunk(corpusId, 0, "alpha")));

        Collection<EmbeddedChunk> read = adapter.chunks(corpusId);
        assertEquals(1, read.size());
        EmbeddedChunk readChunk = read.iterator().next();
        assertEquals("alpha", readChunk.chunk().text());
        assertArrayEquals(new float[]{1.0f, 0.5f}, readChunk.embedding());
        assertArrayEquals(new double[]{0.1, 0.2}, readChunk.projection());
        assertTrue(adapter.chunks(otherCorpusId).isEmpty());
    }

    @Test
    void reprocessingTheSameChunkIdReplacesRatherThanDuplicatesIt() {
        Neo4jVectorStoreAdapter adapter = new Neo4jVectorStoreAdapter(driver);
        String corpusId = "corpus-" + System.nanoTime();

        adapter.persistChunks(corpusId, List.of(embeddedChunk(corpusId, 0, "first version")));
        adapter.persistChunks(corpusId, List.of(embeddedChunk(corpusId, 0, "second version")));

        Collection<EmbeddedChunk> read = adapter.chunks(corpusId);
        assertEquals(1, read.size());
        assertEquals("second version", read.iterator().next().chunk().text());
    }

    @Test
    void skipsAChunkWithANullFieldButPersistsTheValidOnesAlongsideIt() {
        Neo4jVectorStoreAdapter adapter = new Neo4jVectorStoreAdapter(driver);
        String corpusId = "corpus-" + System.nanoTime();
        EmbeddedChunk valid = embeddedChunk(corpusId, 0, "alpha");
        EmbeddedChunk nullText = new EmbeddedChunk(
                new Chunk(corpusId + "::chunk-null-text", corpusId, 1, null),
                new float[]{1.0f, 0.5f}, new double[]{0.1, 0.2});
        EmbeddedChunk nullEmbedding = new EmbeddedChunk(
                new Chunk(corpusId + "::chunk-null-embedding", corpusId, 2, "beta"),
                null, new double[]{0.1, 0.2});
        EmbeddedChunk nullProjection = new EmbeddedChunk(
                new Chunk(corpusId + "::chunk-null-projection", corpusId, 3, "gamma"),
                new float[]{1.0f, 0.5f}, null);

        assertDoesNotThrow(() -> adapter.persistChunks(
                corpusId, List.of(valid, nullText, nullEmbedding, nullProjection)));

        Collection<EmbeddedChunk> read = adapter.chunks(corpusId);
        assertEquals(1, read.size());
        assertEquals("alpha", read.iterator().next().chunk().text());
    }

    @Test
    void chunksForAnUnknownCorpusReturnsEmptyCollectionWithoutException() {
        Neo4jVectorStoreAdapter adapter = new Neo4jVectorStoreAdapter(driver);

        assertTrue(adapter.chunks("nonexistent-corpus").isEmpty());
    }

    @Test
    void ignoresNullOrBlankCorpusIdAndNullChunksWithoutThrowing() {
        Neo4jVectorStoreAdapter adapter = new Neo4jVectorStoreAdapter(driver);
        String corpusId = "corpus-" + System.nanoTime();

        assertDoesNotThrow(() -> adapter.persistChunks(null, List.of(embeddedChunk(corpusId, 0, "alpha"))));
        assertDoesNotThrow(() -> adapter.persistChunks(" ", List.of(embeddedChunk(corpusId, 0, "alpha"))));
        assertDoesNotThrow(() -> adapter.persistChunks(corpusId, null));
        assertTrue(adapter.chunks(corpusId).isEmpty());
    }

    @Test
    void persistsAndReadsBackTheProjectionModelScopedByCorpusId() {
        Neo4jVectorStoreAdapter adapter = new Neo4jVectorStoreAdapter(driver);
        String corpusId = "corpus-" + System.nanoTime();
        ProjectionModel model =
                new ProjectionModel(new double[]{1.0, 2.0}, new double[]{0.6, 0.8}, new double[]{-0.8, 0.6});

        adapter.persistProjectionModel(corpusId, model);

        Optional<ProjectionModel> stored = adapter.projectionModel(corpusId);
        assertTrue(stored.isPresent());
        assertArrayEquals(model.mean(), stored.get().mean());
        assertArrayEquals(model.pc1(), stored.get().pc1());
        assertArrayEquals(model.pc2(), stored.get().pc2());
    }

    @Test
    void projectionModelForAnUnknownCorpusIsEmpty() {
        Neo4jVectorStoreAdapter adapter = new Neo4jVectorStoreAdapter(driver);

        assertTrue(adapter.projectionModel("nonexistent-corpus").isEmpty());
    }

    @Test
    void ignoresNullOrBlankCorpusIdAndNullModelForPersistProjectionModel() {
        Neo4jVectorStoreAdapter adapter = new Neo4jVectorStoreAdapter(driver);
        String corpusId = "corpus-" + System.nanoTime();
        ProjectionModel model = new ProjectionModel(new double[]{1.0}, new double[]{1.0}, new double[]{0.0});

        assertDoesNotThrow(() -> adapter.persistProjectionModel(null, model));
        assertDoesNotThrow(() -> adapter.persistProjectionModel(" ", model));
        assertDoesNotThrow(() -> adapter.persistProjectionModel(corpusId, null));
        assertTrue(adapter.projectionModel(corpusId).isEmpty());
    }

    @Test
    void freshAdapterInstanceReadsBackChunksAndProjectionModelAfterSimulatedRestart() {
        String corpusId = "corpus-restart-" + System.nanoTime();
        Neo4jVectorStoreAdapter first = new Neo4jVectorStoreAdapter(driver);
        ProjectionModel model =
                new ProjectionModel(new double[]{1.0, 2.0}, new double[]{0.6, 0.8}, new double[]{-0.8, 0.6});

        first.persistChunks(corpusId,
                List.of(embeddedChunk(corpusId, 0, "alpha"), embeddedChunk(corpusId, 1, "beta")));
        first.persistProjectionModel(corpusId, model);

        // A fresh instance pointed at the same Neo4j simulates an app restart.
        Neo4jVectorStoreAdapter restarted = new Neo4jVectorStoreAdapter(driver);

        assertEquals(2, restarted.chunks(corpusId).size());
        Optional<ProjectionModel> restartedModel = restarted.projectionModel(corpusId);
        assertTrue(restartedModel.isPresent());
        assertArrayEquals(model.mean(), restartedModel.get().mean());
        assertArrayEquals(model.pc1(), restartedModel.get().pc1());
        assertArrayEquals(model.pc2(), restartedModel.get().pc2());
    }

    private static EmbeddedChunk embeddedChunk(String corpusId, int ordinal, String text) {
        Chunk chunk = new Chunk(corpusId + "::chunk-" + ordinal, corpusId, ordinal, text);
        return new EmbeddedChunk(chunk, new float[]{1.0f, 0.5f}, new double[]{0.1, 0.2});
    }
}
