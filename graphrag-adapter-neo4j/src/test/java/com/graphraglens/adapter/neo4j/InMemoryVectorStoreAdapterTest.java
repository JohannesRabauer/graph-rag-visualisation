package com.graphraglens.adapter.neo4j;

import com.graphraglens.core.domain.Chunk;
import com.graphraglens.core.domain.EmbeddedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static EmbeddedChunk embeddedChunk(String corpusId, int ordinal, String text) {
        Chunk chunk = new Chunk(corpusId + "::chunk-" + ordinal, corpusId, ordinal, text);
        return new EmbeddedChunk(chunk, new float[]{1.0f, 0.0f}, new double[]{0.0, 0.0});
    }
}
