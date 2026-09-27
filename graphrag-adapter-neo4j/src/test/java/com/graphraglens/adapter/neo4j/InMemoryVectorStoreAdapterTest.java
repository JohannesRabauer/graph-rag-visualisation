package com.graphraglens.adapter.neo4j;

import io.graphrag.core.domain.Chunk;
import io.graphrag.core.domain.EmbeddedChunk;
import io.graphrag.core.domain.ProjectionModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryVectorStoreAdapterTest {

    @Test
    void persistsAndReadsBackChunksScopedByCorpusId() {
        InMemoryVectorStoreAdapter adapter = new InMemoryVectorStoreAdapter();

        adapter.persistChunks("corpus-a", List.of(embeddedChunk("corpus-a", 0, "alpha")));
        adapter.persistChunks("corpus-b", List.of(embeddedChunk("corpus-b", 0, "beta")));

        assertEquals(1, adapter.chunks("corpus-a").size());
        assertEquals(1, adapter.chunks("corpus-b").size());
        assertEquals("alpha", adapter.chunks("corpus-a").iterator().next().chunk().text());
        assertEquals("beta", adapter.chunks("corpus-b").iterator().next().chunk().text());
    }

    @Test
    void returnsAnEmptyCollectionForAnUnknownCorpusId() {
        InMemoryVectorStoreAdapter adapter = new InMemoryVectorStoreAdapter();

        assertTrue(adapter.chunks("unknown-corpus").isEmpty());
    }

    @Test
    void reprocessingTheSameChunkIdReplacesRatherThanDuplicatesIt() {
        InMemoryVectorStoreAdapter adapter = new InMemoryVectorStoreAdapter();

        adapter.persistChunks("corpus-a", List.of(embeddedChunk("corpus-a", 0, "first version")));
        adapter.persistChunks("corpus-a", List.of(embeddedChunk("corpus-a", 0, "second version")));

        assertEquals(1, adapter.chunks("corpus-a").size());
        assertEquals("second version", adapter.chunks("corpus-a").iterator().next().chunk().text());
    }

    @Test
    void ignoresNullOrBlankCorpusIdAndNullChunksWithoutThrowing() {
        InMemoryVectorStoreAdapter adapter = new InMemoryVectorStoreAdapter();

        assertDoesNotThrow(() -> adapter.persistChunks(null, List.of(embeddedChunk("corpus-a", 0, "alpha"))));
        assertDoesNotThrow(() -> adapter.persistChunks(" ", List.of(embeddedChunk("corpus-a", 0, "alpha"))));
        assertDoesNotThrow(() -> adapter.persistChunks("corpus-a", null));
    }

    @Test
    void persistsAndReadsBackTheProjectionModelScopedByCorpusId() {
        InMemoryVectorStoreAdapter adapter = new InMemoryVectorStoreAdapter();
        ProjectionModel model = new ProjectionModel(new double[]{1.0, 2.0}, new double[]{0.6, 0.8}, new double[]{-0.8, 0.6});

        adapter.persistProjectionModel("corpus-a", model);

        Optional<ProjectionModel> stored = adapter.projectionModel("corpus-a");
        assertTrue(stored.isPresent());
        assertArrayEquals(model.mean(), stored.get().mean());
        assertArrayEquals(model.pc1(), stored.get().pc1());
        assertArrayEquals(model.pc2(), stored.get().pc2());
        assertTrue(adapter.projectionModel("corpus-b").isEmpty());
    }

    @Test
    void ignoresNullOrBlankCorpusIdAndNullModelForPersistProjectionModel() {
        InMemoryVectorStoreAdapter adapter = new InMemoryVectorStoreAdapter();
        ProjectionModel model = new ProjectionModel(new double[]{1.0}, new double[]{1.0}, new double[]{0.0});

        assertDoesNotThrow(() -> adapter.persistProjectionModel(null, model));
        assertDoesNotThrow(() -> adapter.persistProjectionModel(" ", model));
        assertDoesNotThrow(() -> adapter.persistProjectionModel("corpus-a", null));
        assertTrue(adapter.projectionModel("corpus-a").isEmpty());
    }

    private static EmbeddedChunk embeddedChunk(String corpusId, int ordinal, String text) {
        Chunk chunk = new Chunk(corpusId + "::chunk-" + ordinal, corpusId, ordinal, text);
        return new EmbeddedChunk(chunk, new float[]{1.0f, 0.0f}, new double[]{0.0, 0.0});
    }
}
