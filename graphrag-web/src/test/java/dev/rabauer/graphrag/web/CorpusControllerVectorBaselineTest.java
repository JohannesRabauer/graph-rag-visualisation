package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.adapter.neo4j.InMemoryGraphStoreAdapter;
import dev.rabauer.graphrag.adapter.neo4j.InMemoryVectorStoreAdapter;
import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.adapter.langchain4j.LangChain4jEmbeddingPort;
import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.RetrievalTrace;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.core.usecase.AnswerVectorBaseline;
import dev.rabauer.graphrag.core.usecase.VectorBaselineAnswer;
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
        Neo4jCorpusRegistry corpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        Corpus corpus = new Corpus("corpus-1", List.of(new UploadedDocument("doc.txt", "Some content.")));
        corpusRegistry.put(corpus);
        corpusRegistry.markReady(corpus.id());
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
                null, corpusRegistry, List.of(), null, null, null,
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
        assertThat(body).containsEntry("scoredChunkCount", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) body.get("ranking");
        assertThat(ranking).hasSize(1);
        assertThat(ranking.get(0)).containsEntry("rank", 1)
                .containsEntry("chunkId", chunkId)
                .containsEntry("documentName", "")
                .containsEntry("excerpt", "Sherlock Holmes investigated the case")
                .containsEntry("used", true)
                .containsKey("score");

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
        Neo4jCorpusRegistry corpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        Corpus corpus = new Corpus("corpus-2", List.of(new UploadedDocument("doc.txt", "Some content.")));
        corpusRegistry.put(corpus);
        corpusRegistry.markReady(corpus.id());
        RetrievalTraceStore retrievalTraceStore = new RetrievalTraceStore();

        // Empty vector store — no chunks built yet.
        AnswerVectorBaseline answerVectorBaseline = new AnswerVectorBaseline(
                new LangChain4jEmbeddingPort(), new InMemoryVectorStoreAdapter(), null);
        CorpusController controller = new CorpusController(
                null, corpusRegistry, List.of(), null, null, null,
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

    /** A synthesizing LLM stub answering every context with {@code answer}. */
    private static dev.rabauer.graphrag.core.port.LlmPort synthesizing(
            dev.rabauer.graphrag.core.domain.SynthesizedAnswer answer) {
        return new dev.rabauer.graphrag.core.port.LlmPort() {
            @Override
            public dev.rabauer.graphrag.core.domain.GraphExtraction extract(Corpus corpus) {
                return new dev.rabauer.graphrag.core.domain.GraphExtraction(List.of(), List.of());
            }

            @Override
            public boolean synthesizesAnswers() {
                return true;
            }

            @Override
            public dev.rabauer.graphrag.core.domain.SynthesizedAnswer synthesizeAnswer(
                    String question, List<dev.rabauer.graphrag.core.domain.ContextItem> context) {
                return answer;
            }
        };
    }

    private static CorpusController synthesizingController(String corpusId,
                                                           dev.rabauer.graphrag.core.port.LlmPort llmPort) {
        Neo4jCorpusRegistry corpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        corpusRegistry.put(new Corpus(corpusId, List.of(new UploadedDocument("doc.txt", "Some content."))));
        corpusRegistry.markReady(corpusId);
        InMemoryVectorStoreAdapter vectorStore = new InMemoryVectorStoreAdapter();
        vectorStore.persistChunks(corpusId, List.of(new EmbeddedChunk(
                new Chunk(corpusId + "::chunk-0", corpusId, 0, "Sherlock Holmes investigated the case", "doc.txt"),
                new float[]{1f, 0f}, new double[]{0.0, 0.0})));
        AnswerVectorBaseline answerVectorBaseline =
                new AnswerVectorBaseline(text -> new float[]{1f, 0f}, vectorStore, llmPort);
        return new CorpusController(null, corpusRegistry, List.of(), null, null, llmPort,
                new InMemoryGraphStoreAdapter(), new RetrievalTraceStore(), null, answerVectorBaseline, vectorStore);
    }

    @Test
    void vectorModeNotInContextReturnsTheNoAnswerShapeWithTheQueryProjection() {
        CorpusController controller = synthesizingController("corpus-vector-nic",
                synthesizing(new dev.rabauer.graphrag.core.domain.SynthesizedAnswer(true, "")));

        ResponseEntity<Map<String, Object>> response = controller.query(
                "corpus-vector-nic", Map.of("question", "Who investigated?", "mode", "VECTOR"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("noAnswer", true);
        assertThat(body).containsEntry("reason", VectorBaselineAnswer.NOT_IN_CONTEXT_REASON);
        assertThat(body).containsEntry("mode", "VECTOR");
        assertThat((List<?>) body.get("queryProjection")).hasSize(2);
        assertThat(body).doesNotContainKey("answer");
        assertThat((List<?>) body.get("ranking")).hasSize(1);
        assertThat(body).containsEntry("scoredChunkCount", 1);
        // VECTOR_QUERY_EMBEDDED + VECTOR_CHUNK, no SYNTHESIS.
        assertThat(body.get("traceStepCount")).isEqualTo(2);
    }

    @Test
    void vectorModeCitedAnswerReturnsChunkCitations() {
        CorpusController controller = synthesizingController("corpus-vector-cited",
                synthesizing(new dev.rabauer.graphrag.core.domain.SynthesizedAnswer(false, "Holmes did [1].")));

        ResponseEntity<Map<String, Object>> response = controller.query(
                "corpus-vector-cited", Map.of("question", "Who investigated?", "mode", "VECTOR"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("answer", "Holmes did [1].");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> citations = (List<Map<String, Object>>) body.get("citations");
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0)).containsEntry("chunkId", "corpus-vector-cited::chunk-0");
        assertThat(citations.get(0)).containsEntry("documentName", "doc.txt");
        assertThat(citations.get(0)).containsEntry("excerpt", "Sherlock Holmes investigated the case");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) body.get("ranking");
        assertThat(ranking).hasSize(1);
        assertThat(ranking.get(0)).containsEntry("documentName", "doc.txt").containsEntry("used", true);
    }

    @Test
    void vectorSpaceEndpointReturnsSettledChunkPositionsForTheCorpus() {
        InMemoryVectorStoreAdapter vectorStore = new InMemoryVectorStoreAdapter();
        Corpus corpus = new Corpus("corpus-4", List.of(new UploadedDocument("doc.txt", "content")));
        vectorStore.persistChunks(corpus.id(), List.of(
                new EmbeddedChunk(new Chunk(corpus.id() + "::chunk-0", corpus.id(), 0, "alpha"),
                        new float[]{1f, 0f}, new double[]{1.5, -2.0})));

        CorpusController controller = new CorpusController(
                null, new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver()), List.of(), null, null, null,
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
                null, new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver()), List.of(), null, null, null,
                new InMemoryGraphStoreAdapter(), new RetrievalTraceStore(), null, null,
                new InMemoryVectorStoreAdapter());

        ResponseEntity<Map<String, Object>> response = controller.vectorSpace("no-such-corpus");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) response.getBody().get("chunks")).isEmpty();
    }

    @Test
    void unknownModeIncludingVectorTypoReturns400WithUpdatedErrorMessage() {
        Neo4jCorpusRegistry corpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        Corpus corpus = new Corpus("corpus-3", List.of(new UploadedDocument("doc.txt", "content")));
        corpusRegistry.put(corpus);
        corpusRegistry.markReady(corpus.id());

        CorpusController controller = new CorpusController(
                null, corpusRegistry, List.of(), null, null, null,
                new InMemoryGraphStoreAdapter(), new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "test", "mode", "BOGUS"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error",
                "Search mode must be LOCAL, GLOBAL, DRIFT, or VECTOR.");
    }
}
