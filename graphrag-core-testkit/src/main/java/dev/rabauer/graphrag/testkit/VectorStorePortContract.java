package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.ProjectionModel;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of a {@link VectorStorePort}: chunks with their embeddings
 * round-trip per corpus, re-persisting a chunk id replaces it, and the
 * projection model round-trips when supported. Extend it and return a store
 * from {@link #newStore()}.
 */
public abstract class VectorStorePortContract {

    /** The store under test. */
    protected VectorStorePort store;
    protected String corpusId;
    protected String otherCorpusId;

    /** A store to run one test against; corpus ids are unique per test. */
    protected abstract VectorStorePort newStore();

    /** Whether the store keeps the projection model; the default is {@code true}. */
    protected boolean supportsProjectionModel() {
        return true;
    }

    @BeforeEach
    void createStore() {
        store = newStore();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        corpusId = "vectors-a-" + suffix;
        otherCorpusId = "vectors-b-" + suffix;
    }

    private EmbeddedChunk chunk(String corpus, int ordinal, String text, float... embedding) {
        return new EmbeddedChunk(new Chunk(corpus + "::chunk-" + ordinal, corpus, ordinal, text, "doc.txt"),
                embedding, new double[] {ordinal, -ordinal});
    }

    @Test
    void chunksRoundTripWithTheirEmbeddingsPerCorpus() {
        store.persistChunks(corpusId, List.of(chunk(corpusId, 0, "alpha", 1f, 0f), chunk(corpusId, 1, "beta", 0f, 1f)));
        store.persistChunks(otherCorpusId, List.of(chunk(otherCorpusId, 0, "gamma", 0.5f, 0.5f)));

        Map<String, EmbeddedChunk> read = byId(store.chunks(corpusId));

        assertEquals(2, read.size());
        EmbeddedChunk beta = read.get(corpusId + "::chunk-1");
        assertEquals("beta", beta.chunk().text());
        assertEquals(1, beta.chunk().ordinal());
        assertEquals(corpusId, beta.chunk().corpusId());
        assertArrayEquals(new float[] {0f, 1f}, beta.embedding(), 1e-6f);
        assertEquals(1, store.chunks(otherCorpusId).size());
    }

    @Test
    void persistingAChunkIdAgainReplacesIt() {
        store.persistChunks(corpusId, List.of(chunk(corpusId, 0, "alpha", 1f, 0f)));
        store.persistChunks(corpusId, List.of(chunk(corpusId, 0, "alpha v2", 0f, 1f)));

        Collection<EmbeddedChunk> read = store.chunks(corpusId);

        assertEquals(1, read.size());
        assertEquals("alpha v2", read.iterator().next().chunk().text());
    }

    @Test
    void anUnknownCorpusHasNoChunksAndNoModel() {
        Collection<EmbeddedChunk> chunks = store.chunks("unknown-" + corpusId);

        assertNotNull(chunks);
        assertTrue(chunks.isEmpty());
        assertTrue(store.projectionModel("unknown-" + corpusId).isEmpty());
    }

    @Test
    void deletingADocumentsChunksKeepsTheOthers() {
        if (!supportsDeletion()) {
            return;
        }
        store.persistChunks(corpusId, List.of(chunk(corpusId, 0, "alpha", 1f, 0f),
                new EmbeddedChunk(new Chunk(corpusId + "::chunk-1", corpusId, 1, "beta", "other.txt"),
                        new float[] {0f, 1f}, new double[] {1, -1})));

        store.deleteChunksOf(corpusId, "doc.txt");

        assertEquals(List.of(corpusId + "::chunk-1"),
                store.chunks(corpusId).stream().map(chunk -> chunk.chunk().id()).toList());
    }

    /** Whether the store deletes chunks by document; the default is {@code true}. */
    protected boolean supportsDeletion() {
        return true;
    }

    @Test
    void theProjectionModelRoundTripsWhenSupported() {
        ProjectionModel model = new ProjectionModel(new double[] {0.1, 0.2}, new double[] {1, 0}, new double[] {0, 1});

        store.persistProjectionModel(corpusId, model);

        if (supportsProjectionModel()) {
            ProjectionModel read = store.projectionModel(corpusId).orElseThrow();
            assertArrayEquals(model.mean(), read.mean(), 1e-9);
            assertArrayEquals(model.pc1(), read.pc1(), 1e-9);
            assertArrayEquals(model.pc2(), read.pc2(), 1e-9);
            assertTrue(store.projectionModel(otherCorpusId).isEmpty());
        }
    }

    private static Map<String, EmbeddedChunk> byId(Collection<EmbeddedChunk> chunks) {
        return chunks.stream().collect(Collectors.toMap(chunk -> chunk.chunk().id(), Function.identity()));
    }
}
