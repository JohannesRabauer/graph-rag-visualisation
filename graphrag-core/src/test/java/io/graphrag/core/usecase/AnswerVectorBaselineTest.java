package io.graphrag.core.usecase;

import io.graphrag.core.domain.Chunk;
import io.graphrag.core.domain.EmbeddedChunk;
import io.graphrag.core.domain.ProjectionModel;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.port.EmbeddingPort;
import io.graphrag.core.port.VectorStorePort;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerVectorBaselineTest {

    // ---------------------------------------------------------------------------
    // Stub helpers
    // ---------------------------------------------------------------------------

    /** Embedding port that always returns the given fixed vector. */
    private static EmbeddingPort fixedEmbedding(float... vector) {
        return text -> vector.clone();
    }

    /** Embedding port that returns the all-zero vector. */
    private static EmbeddingPort zeroEmbedding(int dimensions) {
        return text -> new float[dimensions];
    }

    /** Vector store that returns the given chunks for any corpusId. */
    private static VectorStorePort storeWith(EmbeddedChunk... chunks) {
        List<EmbeddedChunk> list = List.of(chunks);
        return new VectorStorePort() {
            @Override
            public void persistChunks(String corpusId, Collection<EmbeddedChunk> c) {
            }

            @Override
            public Collection<EmbeddedChunk> chunks(String corpusId) {
                return list;
            }
        };
    }

    /** Empty vector store — no chunks for any corpus. */
    private static VectorStorePort emptyStore() {
        return new VectorStorePort() {
            @Override
            public void persistChunks(String corpusId, Collection<EmbeddedChunk> c) {
            }
        };
    }

    /** Vector store that returns the given chunks and a fitted projection model for any corpusId. */
    private static VectorStorePort storeWithModel(ProjectionModel model, EmbeddedChunk... chunks) {
        List<EmbeddedChunk> list = List.of(chunks);
        return new VectorStorePort() {
            @Override
            public void persistChunks(String corpusId, Collection<EmbeddedChunk> c) {
            }

            @Override
            public Collection<EmbeddedChunk> chunks(String corpusId) {
                return list;
            }

            @Override
            public Optional<ProjectionModel> projectionModel(String corpusId) {
                return Optional.of(model);
            }
        };
    }

    private static EmbeddedChunk ec(String id, float... embedding) {
        Chunk chunk = new Chunk(id, "corpus-1", 0, "text for " + id);
        return new EmbeddedChunk(chunk, embedding, new double[]{0.0, 0.0});
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    void returnsNoChunksYetWhenVectorStoreIsEmpty() {
        VectorBaselineAnswer result = new AnswerVectorBaseline(zeroEmbedding(4), emptyStore(), null)
                .answer("anything", "corpus-1");

        assertTrue(result.noChunks());
        assertNotNull(result.reason());
        assertFalse(result.reason().isBlank());
        assertTrue(result.steps().isEmpty());
    }

    @Test
    void returnsNoChunksYetWhenStoreReturnsEmptyCollection() {
        VectorBaselineAnswer result = new AnswerVectorBaseline(zeroEmbedding(4), storeWith(), null)
                .answer("anything", "corpus-1");

        assertTrue(result.noChunks());
        assertTrue(result.steps().isEmpty());
    }

    @Test
    void traceStartsWithQueryEmbeddedStepThenChunksThenSynthesis() {
        EmbeddedChunk chunk = ec("corpus-1::chunk-0", 1f, 0f, 0f, 0f);
        VectorBaselineAnswer result = new AnswerVectorBaseline(
                fixedEmbedding(1f, 0f, 0f, 0f), storeWith(chunk), null)
                .answer("What is this about?", "corpus-1");

        assertFalse(result.noChunks());
        List<RetrievalStep> steps = result.steps();
        assertEquals(3, steps.size());
        assertEquals(RetrievalStep.Kind.VECTOR_QUERY_EMBEDDED, steps.get(0).kind());
        assertEquals("query", steps.get(0).identifier());
        assertEquals("What is this about?", steps.get(0).label());
        assertEquals(RetrievalStep.Kind.VECTOR_CHUNK, steps.get(1).kind());
        assertEquals("corpus-1::chunk-0", steps.get(1).identifier());
        assertTrue(steps.get(1).label().startsWith("score="));
        assertEquals(RetrievalStep.Kind.SYNTHESIS, steps.get(2).kind());
        assertEquals("", steps.get(2).identifier());
        assertFalse(steps.get(2).label().isBlank());
    }

    @Test
    void chunksAreReturnedInDescendingSimilarityOrder() {
        // chunk-0 is perfectly aligned with query vector (similarity ~1.0)
        // chunk-1 is orthogonal to query vector (similarity 0.0)
        // chunk-2 is partially aligned (similarity > 0 but < chunk-0)
        EmbeddedChunk chunk0 = ec("chunk-0", 1f, 0f);
        EmbeddedChunk chunk1 = ec("chunk-1", 0f, 1f);
        EmbeddedChunk chunk2 = ec("chunk-2", 0.7f, 0.7f);  // ~45 degrees from query

        VectorBaselineAnswer result = new AnswerVectorBaseline(
                fixedEmbedding(1f, 0f), storeWith(chunk0, chunk1, chunk2), null)
                .answer("question", "corpus-1");

        List<RetrievalStep> chunkSteps = result.steps().stream()
                .filter(s -> s.kind() == RetrievalStep.Kind.VECTOR_CHUNK)
                .toList();
        assertEquals(3, chunkSteps.size());
        // First chunk step should be chunk-0 (highest similarity to [1,0])
        assertEquals("chunk-0", chunkSteps.get(0).identifier());
        // Last chunk step should be chunk-1 (orthogonal — similarity 0.0)
        assertEquals("chunk-1", chunkSteps.get(2).identifier());
    }

    @Test
    void limitsResultsToTopKEvenWhenMoreChunksAreAvailable() {
        // Build 8 chunks; TOP_K is 5
        EmbeddedChunk[] chunks = new EmbeddedChunk[8];
        for (int i = 0; i < 8; i++) {
            chunks[i] = ec("chunk-" + i, (float) i, 0f);
        }
        VectorBaselineAnswer result = new AnswerVectorBaseline(
                fixedEmbedding(1f, 0f), storeWith(chunks), null)
                .answer("question", "corpus-1");

        long chunkStepCount = result.steps().stream()
                .filter(s -> s.kind() == RetrievalStep.Kind.VECTOR_CHUNK)
                .count();
        assertEquals(5, chunkStepCount);
    }

    @Test
    void returnsAllChunksWhenFewerThanKAreAvailable() {
        EmbeddedChunk c0 = ec("chunk-0", 1f, 0f);
        EmbeddedChunk c1 = ec("chunk-1", 0f, 1f);
        EmbeddedChunk c2 = ec("chunk-2", 0.5f, 0.5f);

        VectorBaselineAnswer result = new AnswerVectorBaseline(
                fixedEmbedding(1f, 0f), storeWith(c0, c1, c2), null)
                .answer("question", "corpus-1");

        long chunkStepCount = result.steps().stream()
                .filter(s -> s.kind() == RetrievalStep.Kind.VECTOR_CHUNK)
                .count();
        assertEquals(3, chunkStepCount);
    }

    @Test
    void queryZeroVectorProducesZeroSimilaritiesNoNaNOrInfinity() {
        EmbeddedChunk c0 = ec("chunk-0", 1f, 0f);
        EmbeddedChunk c1 = ec("chunk-1", 0f, 1f);

        VectorBaselineAnswer result = new AnswerVectorBaseline(
                zeroEmbedding(2), storeWith(c0, c1), null)
                .answer("question", "corpus-1");

        assertFalse(result.noChunks());
        // All chunk score labels should be "score=0.000" — no NaN or Infinity
        result.steps().stream()
                .filter(s -> s.kind() == RetrievalStep.Kind.VECTOR_CHUNK)
                .forEach(s -> {
                    assertFalse(s.label().contains("NaN"), "score label must not contain NaN: " + s.label());
                    assertFalse(s.label().contains("Infinity"), "score label must not contain Infinity: " + s.label());
                    assertEquals("score=0.000", s.label());
                });
    }

    @Test
    void chunkWithZeroEmbeddingProducesZeroSimilarityNoException() {
        EmbeddedChunk zeroChunk = ec("chunk-zero", 0f, 0f);
        EmbeddedChunk normalChunk = ec("chunk-normal", 1f, 0f);

        VectorBaselineAnswer result = new AnswerVectorBaseline(
                fixedEmbedding(1f, 0f), storeWith(zeroChunk, normalChunk), null)
                .answer("question", "corpus-1");

        assertFalse(result.noChunks());
        // normalChunk should be ranked first, zeroChunk last
        List<RetrievalStep> chunkSteps = result.steps().stream()
                .filter(s -> s.kind() == RetrievalStep.Kind.VECTOR_CHUNK)
                .toList();
        assertEquals(2, chunkSteps.size());
        assertEquals("chunk-normal", chunkSteps.get(0).identifier());
        assertEquals("chunk-zero", chunkSteps.get(1).identifier());
        assertEquals("score=0.000", chunkSteps.get(1).label());
    }

    @Test
    void nullLlmPortIsSafeAndUsesDefaultSynthesis() {
        EmbeddedChunk chunk = ec("chunk-0", 1f, 0f);
        // null llmPort — must not throw
        VectorBaselineAnswer result = new AnswerVectorBaseline(
                fixedEmbedding(1f, 0f), storeWith(chunk), null)
                .answer("What?", "corpus-1");

        assertFalse(result.noChunks());
        assertNotNull(result.answer());
        assertFalse(result.answer().isBlank());
    }

    @Test
    void queryProjectionDefaultsToOriginWhenNoProjectionModelExistsForTheCorpus() {
        EmbeddedChunk chunk = ec("chunk-0", 1f, 0f);
        VectorBaselineAnswer result = new AnswerVectorBaseline(
                fixedEmbedding(1f, 0f), storeWith(chunk), null)
                .answer("What?", "corpus-1");

        assertEquals(0.0, result.queryProjection()[0]);
        assertEquals(0.0, result.queryProjection()[1]);
    }

    @Test
    void queryProjectionIsComputedFromThePersistedProjectionModelWhenOneExists() {
        List<float[]> corpusEmbeddings = List.of(new float[]{1.0f, 0.0f}, new float[]{5.0f, 0.0f});
        ProjectionModel model = TwoDProjection.fit(corpusEmbeddings);
        EmbeddedChunk chunk = ec("chunk-0", 1f, 0f);

        VectorBaselineAnswer result = new AnswerVectorBaseline(
                fixedEmbedding(3.0f, 0f), storeWithModel(model, chunk), null)
                .answer("What?", "corpus-1");

        double[] expected = TwoDProjection.project(model, new float[]{3.0f, 0f});
        assertEquals(expected[0], result.queryProjection()[0], 1.0e-9);
        assertEquals(expected[1], result.queryProjection()[1], 1.0e-9);
    }

    @Test
    void synthesisStepLabelMatchesReturnedAnswer() {
        EmbeddedChunk chunk = ec("chunk-0", 1f, 0f);
        VectorBaselineAnswer result = new AnswerVectorBaseline(
                fixedEmbedding(1f, 0f), storeWith(chunk), null)
                .answer("What?", "corpus-1");

        RetrievalStep synthesisStep = result.steps().stream()
                .filter(s -> s.kind() == RetrievalStep.Kind.SYNTHESIS)
                .findFirst()
                .orElseThrow();
        assertEquals(result.answer(), synthesisStep.label());
    }
}
