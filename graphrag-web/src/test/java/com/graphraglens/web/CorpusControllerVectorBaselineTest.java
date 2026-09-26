package com.graphraglens.web;

import com.graphraglens.adapter.neo4j.InMemoryGraphStoreAdapter;
import com.graphraglens.adapter.neo4j.InMemoryVectorStoreAdapter;
import com.graphraglens.adapter.langchain4j.LangChain4jEmbeddingPort;
import com.graphraglens.core.domain.Chunk;
import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.EmbeddedChunk;
import com.graphraglens.core.domain.RetrievalTrace;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.port.VectorStorePort;
import com.graphraglens.core.usecase.AnswerVectorBaseline;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit tests for the VECTOR query branch wiring in {@link CorpusController}.
 *
 * <p>Mirrors the pattern of {@link CorpusControllerGlobalSearchTest} and
 * {@link CorpusControllerDriftSearchTest}: constructs the controller directly
 * (no {@code @SpringBootTest}) against fresh, isolated stores.
 */
class CorpusControllerVectorBaselineTest {

    @Test
    void vectorModeReturnsAnswerIdTraceIdAnswerAndModeVectorWhenChunksAreAvailable() {
        CorpusStore corpusStore = new CorpusStore();
        Corpus corpus = new Corpus("corpus-1", List.of(new UploadedDocument("doc.txt", "Some content.")));
        corpusStore.put(corpus);
        corpusStore.markReady(corpus.id());
        RetrievalTraceStore retrievalTraceStore = new RetrievalTraceStore();

        // Pre-populate the vector store with a chunk so the baseline has something to retrieve.
        InMemoryVectorStoreAdapter vectorStore = new InMemoryVectorStoreAdapter();
        LangChain4jEmbeddingPort embeddingPort = new LangChain4jEmbeddingPort();
        String chunkId = corpus.id() + "::chunk-0";
        float[] embedding = embeddingPort.embed("Sherlock Holmes investigated the case");
        vectorStore.persistChunks(corpus.id(), List.of(
                new EmbeddedChunk(
                        new Chunk(chunkId, corpus.id(), 0, "Sherlock Holmes investigated the case"),
                        embedding,
                        new double[]{0.0, 0.0})));

        AnswerVectorBaseline answerVectorBaseline = new AnswerVectorBaseline(embeddingPort, vectorStore, null);
        CorpusController controller = new CorpusController(
                null, corpusStore, List.of(), null, null, null,
                new InMemoryGraphStoreAdapter(), retrievalTraceStore, null, answerVectorBaseline, vectorStore);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "Who investigated?", "mode", "VECTOR"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("answerId", "traceId", "traceStepCount", "answer", "mode", "queryProjection");
        assertThat(body).containsEntry("mode", "VECTOR");
        assertThat(body).doesNotContainKey("noAnswer");
        assertThat((List<?>) body.get("queryProjection")).hasSize(2);

        // The stored trace must be fetchable immediately after the response.
        String traceId = (String) body.get("traceId");
        Optional<RetrievalTrace> storedTrace = retrievalTraceStore.get(traceId);
        assertThat(storedTrace).isPresent();
        // Trace: VECTOR_QUERY_EMBEDDED + ≥1 VECTOR_CHUNK + SYNTHESIS
        assertThat(storedTrace.get().steps()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(storedTrace.get().steps().get(0).kind().name()).isEqualTo("VECTOR_QUERY_EMBEDDED");
        assertThat(storedTrace.get().steps().getLast().kind().name()).isEqualTo("SYNTHESIS");
        assertThat(body.get("traceStepCount")).isEqualTo(storedTrace.get().steps().size());
    }

    @Test
    void vectorModeReturnsNoAnswerShapeWhenVectorIndexIsEmpty() {
        CorpusStore corpusStore = new CorpusStore();
        Corpus corpus = new Corpus("corpus-2", List.of(new UploadedDocument("doc.txt", "Some content.")));
        corpusStore.put(corpus);
        corpusStore.markReady(corpus.id());
        RetrievalTraceStore retrievalTraceStore = new RetrievalTraceStore();

        // Empty vector store — no chunks built yet.
        AnswerVectorBaseline answerVectorBaseline = new AnswerVectorBaseline(
                new LangChain4jEmbeddingPort(), new InMemoryVectorStoreAdapter(), null);
        CorpusController controller = new CorpusController(
                null, corpusStore, List.of(), null, null, null,
                new InMemoryGraphStoreAdapter(), retrievalTraceStore, null, answerVectorBaseline, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "What is this about?", "mode", "VECTOR"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("answerId", "traceId", "traceStepCount", "noAnswer", "reason");
        assertThat(body.get("noAnswer")).isEqualTo(true);
        assertThat(body).doesNotContainKeys("answer", "mode");
        assertThat((String) body.get("reason")).isNotBlank();

        // Even the no-chunks outcome must have a fetchable trace (Story 5.1 AC2).
        String traceId = (String) body.get("traceId");
        assertThat(retrievalTraceStore.get(traceId)).isPresent();
        assertThat(body.get("traceStepCount")).isEqualTo(0);
    }

    @Test
    void vectorSpaceEndpointReturnsSettledChunkPositionsForTheCorpus() {
        InMemoryVectorStoreAdapter vectorStore = new InMemoryVectorStoreAdapter();
        Corpus corpus = new Corpus("corpus-4", List.of(new UploadedDocument("doc.txt", "content")));
        vectorStore.persistChunks(corpus.id(), List.of(
                new EmbeddedChunk(new Chunk(corpus.id() + "::chunk-0", corpus.id(), 0, "alpha"),
                        new float[]{1f, 0f}, new double[]{1.5, -2.0})));

        CorpusController controller = new CorpusController(
                null, new CorpusStore(), List.of(), null, null, null,
                new InMemoryGraphStoreAdapter(), new RetrievalTraceStore(), null, null, vectorStore);

        ResponseEntity<Map<String, Object>> response = controller.vectorSpace(corpus.id());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("corpusId", corpus.id());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) body.get("chunks");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).containsEntry("id", corpus.id() + "::chunk-0");
        assertThat(chunks.get(0)).containsEntry("x", 1.5);
        assertThat(chunks.get(0)).containsEntry("y", -2.0);
    }

    @Test
    void vectorSpaceEndpointReturnsEmptyChunksWhenIndexIsNotBuiltYet() {
        CorpusController controller = new CorpusController(
                null, new CorpusStore(), List.of(), null, null, null,
                new InMemoryGraphStoreAdapter(), new RetrievalTraceStore(), null, null,
                new InMemoryVectorStoreAdapter());

        ResponseEntity<Map<String, Object>> response = controller.vectorSpace("no-such-corpus");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) response.getBody().get("chunks")).isEmpty();
    }

    @Test
    void unknownModeIncludingVectorTypoReturns400WithUpdatedErrorMessage() {
        CorpusStore corpusStore = new CorpusStore();
        Corpus corpus = new Corpus("corpus-3", List.of(new UploadedDocument("doc.txt", "content")));
        corpusStore.put(corpus);
        corpusStore.markReady(corpus.id());

        CorpusController controller = new CorpusController(
                null, corpusStore, List.of(), null, null, null,
                new InMemoryGraphStoreAdapter(), new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "test", "mode", "BOGUS"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error",
                "Search mode must be LOCAL, GLOBAL, DRIFT, or VECTOR.");
    }
}
