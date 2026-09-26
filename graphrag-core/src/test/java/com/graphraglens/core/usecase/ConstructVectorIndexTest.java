package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Chunk;
import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.EmbeddedChunk;
import com.graphraglens.core.domain.ProjectionModel;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.port.EmbeddingPort;
import com.graphraglens.core.port.VectorStorePort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructVectorIndexTest {

    @Test
    void constructsASingleChunkForAShortDocumentAndPersistsItWithAZeroProjection() {
        RecordingEmbeddingPort embeddingPort = new RecordingEmbeddingPort(Map.of(
                "Short content.", new float[]{1.0f, 2.0f}));
        RecordingVectorStore vectorStore = new RecordingVectorStore();

        List<EmbeddedChunk> result = new ConstructVectorIndex(embeddingPort, vectorStore).run(
                new Corpus("corpus-1", List.of(new UploadedDocument("doc.txt", "Short content."))));

        assertEquals(1, result.size());
        EmbeddedChunk embeddedChunk = result.getFirst();
        assertEquals(new Chunk("corpus-1::chunk-0", "corpus-1", 0, "Short content."), embeddedChunk.chunk());
        assertArrayEquals(new float[]{1.0f, 2.0f}, embeddedChunk.embedding());
        assertArrayEquals(new double[]{0.0, 0.0}, embeddedChunk.projection());
        assertEquals(List.of("Short content."), embeddingPort.inputs);
        assertEquals("corpus-1", vectorStore.lastCorpusId);
        assertEquals(1, vectorStore.persisted.size());
    }

    @Test
    void chunksALongDocumentOnWhitespaceBoundariesAndKeepsOrdinalsSequential() {
        String firstChunk = repeated('a', 400);
        String secondChunk = repeated('b', 250);
        String content = firstChunk + " " + secondChunk;
        RecordingEmbeddingPort embeddingPort = new RecordingEmbeddingPort(Map.of(
                firstChunk, new float[]{1.0f, 0.0f},
                secondChunk, new float[]{0.0f, 1.0f}));
        RecordingVectorStore vectorStore = new RecordingVectorStore();

        List<EmbeddedChunk> result = new ConstructVectorIndex(embeddingPort, vectorStore).run(
                new Corpus("corpus-1", List.of(new UploadedDocument("doc.txt", content))));

        assertEquals(2, result.size());
        assertEquals("corpus-1::chunk-0", result.get(0).chunk().id());
        assertEquals(0, result.get(0).chunk().ordinal());
        assertEquals(firstChunk, result.get(0).chunk().text());
        assertEquals("corpus-1::chunk-1", result.get(1).chunk().id());
        assertEquals(1, result.get(1).chunk().ordinal());
        assertEquals(secondChunk, result.get(1).chunk().text());
        assertEquals(List.of(firstChunk, secondChunk), embeddingPort.inputs);
        assertArrayEquals(new float[]{1.0f, 0.0f}, result.get(0).embedding());
        assertArrayEquals(new float[]{0.0f, 1.0f}, result.get(1).embedding());
    }

    @Test
    void assignsEachChunkTheProjectionCoordinateMatchingItsOwnEmbeddingNotAShuffledOne() {
        RecordingEmbeddingPort embeddingPort = new RecordingEmbeddingPort(Map.of(
                "alpha chunk", new float[]{10.0f, 0.0f},
                "beta chunk", new float[]{0.0f, 0.0f},
                "gamma chunk", new float[]{-10.0f, 0.0f}));
        RecordingVectorStore vectorStore = new RecordingVectorStore();

        List<EmbeddedChunk> result = new ConstructVectorIndex(embeddingPort, vectorStore).run(
                new Corpus("corpus-align", List.of(
                        new UploadedDocument("a.txt", "alpha chunk"),
                        new UploadedDocument("b.txt", "beta chunk"),
                        new UploadedDocument("c.txt", "gamma chunk"))));

        assertEquals(3, result.size());
        // alpha (+10) and gamma (-10) sit on opposite ends of the single-variance axis,
        // beta (0) sits exactly between them — this pins each chunk's projection to its
        // own embedding rather than a shuffled/off-by-one assignment across the batch.
        double alphaX = result.get(0).projection()[0];
        double betaX = result.get(1).projection()[0];
        double gammaX = result.get(2).projection()[0];
        assertTrue(Math.abs(betaX) < 1.0e-9);
        assertTrue(alphaX * gammaX < 0.0);
        assertEquals(alphaX, -gammaX, 1.0e-9);
    }

    @Test
    void hardCutsAtChunkSizeWhenNoWhitespaceIsFoundWithinTheLookbackWindow() {
        String giantWord = repeated('x', 700);
        RecordingEmbeddingPort embeddingPort = new RecordingEmbeddingPort(Map.of(
                giantWord.substring(0, 500), new float[]{1.0f, 0.0f},
                giantWord.substring(500), new float[]{0.0f, 1.0f}));
        RecordingVectorStore vectorStore = new RecordingVectorStore();

        List<EmbeddedChunk> result = new ConstructVectorIndex(embeddingPort, vectorStore).run(
                new Corpus("corpus-hardcut", List.of(new UploadedDocument("doc.txt", giantWord))));

        assertEquals(2, result.size());
        assertEquals(500, result.get(0).chunk().text().length());
        assertEquals(200, result.get(1).chunk().text().length());
        assertEquals(giantWord, result.get(0).chunk().text() + result.get(1).chunk().text());
    }

    @Test
    void continuesChunkOrdinalsAcrossMultipleDocumentsAndSkipsBlankDocuments() {
        RecordingEmbeddingPort embeddingPort = new RecordingEmbeddingPort(Map.of(
                "First document.", new float[]{1.0f, 0.0f},
                "Second document.", new float[]{0.0f, 1.0f}));
        RecordingVectorStore vectorStore = new RecordingVectorStore();

        List<EmbeddedChunk> result = new ConstructVectorIndex(embeddingPort, vectorStore).run(
                new Corpus("corpus-7", List.of(
                        new UploadedDocument("blank.txt", "   "),
                        new UploadedDocument("first.txt", "First document."),
                        new UploadedDocument("second.txt", "Second document."))));

        assertEquals(2, result.size());
        assertEquals(0, result.get(0).chunk().ordinal());
        assertEquals(1, result.get(1).chunk().ordinal());
        assertEquals("First document.", result.get(0).chunk().text());
        assertEquals("Second document.", result.get(1).chunk().text());
        assertEquals(List.of("First document.", "Second document."), embeddingPort.inputs);
    }

    @Test
    void returnsAnEmptyListForCorporaWithOnlyBlankDocuments() {
        RecordingEmbeddingPort embeddingPort = new RecordingEmbeddingPort(Map.of());
        RecordingVectorStore vectorStore = new RecordingVectorStore();

        List<EmbeddedChunk> result = new ConstructVectorIndex(embeddingPort, vectorStore).run(
                new Corpus("corpus-blank", List.of(
                        new UploadedDocument("blank-1.txt", ""),
                        new UploadedDocument("blank-2.txt", "   "))));

        assertTrue(result.isEmpty());
        assertTrue(embeddingPort.inputs.isEmpty());
        assertTrue(vectorStore.persisted.isEmpty());
    }

    private static String repeated(char value, int count) {
        return String.valueOf(value).repeat(count);
    }

    private static final class RecordingEmbeddingPort implements EmbeddingPort {
        private final Map<String, float[]> vectorsByText;
        private final List<String> inputs = new ArrayList<>();

        private RecordingEmbeddingPort(Map<String, float[]> vectorsByText) {
            this.vectorsByText = new LinkedHashMap<>(vectorsByText);
        }

        @Override
        public float[] embed(String text) {
            inputs.add(text);
            float[] vector = vectorsByText.get(text);
            if (vector == null) {
                throw new IllegalArgumentException("No vector configured for text: " + text);
            }
            return vector;
        }
    }

    @Test
    void persistsTheFittedProjectionModelAlongsideTheChunksSoAQueryCanBeProjectedLater() {
        RecordingEmbeddingPort embeddingPort = new RecordingEmbeddingPort(Map.of(
                "alpha chunk", new float[]{10.0f, 0.0f},
                "beta chunk", new float[]{-10.0f, 0.0f}));
        RecordingVectorStore vectorStore = new RecordingVectorStore();

        new ConstructVectorIndex(embeddingPort, vectorStore).run(
                new Corpus("corpus-model", List.of(
                        new UploadedDocument("a.txt", "alpha chunk"),
                        new UploadedDocument("b.txt", "beta chunk"))));

        assertEquals("corpus-model", vectorStore.lastModelCorpusId);
        assertTrue(vectorStore.persistedModel.mean().length > 0);
    }

    private static final class RecordingVectorStore implements VectorStorePort {
        private final List<EmbeddedChunk> persisted = new ArrayList<>();
        private String lastCorpusId;
        private ProjectionModel persistedModel;
        private String lastModelCorpusId;

        @Override
        public void persistChunks(String corpusId, Collection<EmbeddedChunk> chunks) {
            lastCorpusId = corpusId;
            persisted.addAll(chunks);
        }

        @Override
        public void persistProjectionModel(String corpusId, ProjectionModel model) {
            lastModelCorpusId = corpusId;
            persistedModel = model;
        }
    }
}
