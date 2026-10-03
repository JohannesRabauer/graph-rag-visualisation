package dev.rabauer.graphrag.web;

import com.jayway.jsonpath.JsonPath;
import dev.rabauer.graphrag.adapter.langchain4j.OpenAiLlmPort;
import dev.rabauer.graphrag.adapter.neo4j.InMemoryGraphStoreAdapter;
import dev.rabauer.graphrag.adapter.neo4j.InMemoryVectorStoreAdapter;
import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.usecase.AnswerVectorBaseline;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/corpora/{id}/compare-all}: all four methods answered fresh,
 * per-method timing, footprint and trace, the evidence table, and a failing
 * method reported in its own column.
 */
class CorpusControllerCompareAllTest {

    private static final EmbeddingPort QUERY = text -> new float[]{1f, 0f};

    /** Cites the first Source passage of each context (or the first item when there is none). */
    private static class CitingLlm implements LlmPort {
        @Override
        public GraphExtraction extract(Corpus corpus) {
            return new GraphExtraction(List.of(), List.of());
        }

        @Override
        public boolean synthesizesAnswers() {
            return true;
        }

        @Override
        public SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context) {
            int first = context.stream().filter(ContextItem::isTextUnit).findFirst()
                    .orElse(context.getFirst()).number();
            return new SynthesizedAnswer(false, "Irene Adler left London [" + first + "].");
        }
    }

    private record Fixture(MockMvc mvc, String corpusId) {
    }

    private static Fixture fixture(String corpusId, LlmPort llmPort) {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        registry.put(new Corpus(corpusId, List.of(new UploadedDocument("story.txt", "Irene Adler left London."))));
        registry.markReady(corpusId);

        InMemoryGraphStoreAdapter graph = new InMemoryGraphStoreAdapter();
        graph.persistEntities(corpusId, List.of(new Entity("Irene Adler", "Person", "An opera singer.",
                List.of("tu-a"))));
        graph.persistTextUnits(corpusId, List.of(new TextUnit("tu-a", corpusId, "story.txt", 0,
                "Irene Adler   left\nLondon. She sang at the opera.")));
        graph.persistCommunities(corpusId, List.of(new Community("community-1", "Opera",
                "Irene Adler sang at the opera.")));

        InMemoryVectorStoreAdapter vectors = new InMemoryVectorStoreAdapter();
        vectors.persistChunks(corpusId, List.of(
                new EmbeddedChunk(new Chunk(corpusId + "::chunk-0", corpusId, 0, "Irene Adler left London.",
                        "story.txt"), new float[]{1f, 0f}, new double[]{0.5, 0.5}),
                new EmbeddedChunk(new Chunk(corpusId + "::chunk-1", corpusId, 1, "Unrelated text.", "other.txt"),
                        new float[]{0.2f, 0.8f}, new double[]{-0.5, 0.5})));

        CorpusController controller = new CorpusController(null, registry, List.of(), null, null, llmPort, graph,
                new RetrievalTraceStore(), null, new AnswerVectorBaseline(QUERY, vectors, llmPort), vectors, null);
        return new Fixture(MockMvcBuilders.standaloneSetup(controller).build(), corpusId);
    }

    private static String compareAll(Fixture f, String body, int expectedStatus) throws Exception {
        return f.mvc().perform(post("/api/corpora/{id}/compare-all", f.corpusId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsAllFourRunsWithTimingFootprintTracesAndTheEvidenceTable() throws Exception {
        Fixture f = fixture("compare-all-live", new CitingLlm());

        String body = compareAll(f, "{\"question\": \"Who is Irene Adler?\"}", 200);

        assertThat((String) JsonPath.read(body, "$.question")).isEqualTo("Who is Irene Adler?");
        assertThat((String) JsonPath.read(body, "$.summary")).startsWith("4 of 4 methods answered.");
        assertThat((List<String>) JsonPath.read(body, "$.runs[*].method"))
                .containsExactly("LOCAL", "GLOBAL", "DRIFT", "VECTOR");
        assertThat((List<String>) JsonPath.read(body, "$.runs[*].label"))
                .containsExactly("Local", "Global", "DRIFT", "Vector Search");
        assertThat((List<String>) JsonPath.read(body, "$.runs[*].outcome"))
                .containsOnly("ANSWERED");

        // Local: cited Text Unit, a footprint, timing that adds up and a replayable trace.
        assertThat((String) JsonPath.read(body, "$.runs[0].answer")).isEqualTo("Irene Adler left London [1].");
        assertThat((String) JsonPath.read(body, "$.runs[0].citations[0].textUnitId")).isEqualTo("tu-a");
        assertThat((Integer) JsonPath.read(body, "$.runs[0].footprint.ENTITY")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.runs[0].footprint.TEXT_UNIT")).isEqualTo(1);
        Map<String, Number> timing = JsonPath.read(body, "$.runs[0].timing");
        assertThat(timing).containsKeys("totalMs", "retrievalMs", "embeddingMs", "embeddingCalls", "llmMs",
                "llmCalls");
        assertThat(timing.get("llmCalls").intValue()).isEqualTo(1);
        String traceId = JsonPath.read(body, "$.runs[0].traceId");
        int stepCount = JsonPath.read(body, "$.runs[0].traceStepCount");
        assertThat((List<Object>) JsonPath.read(body, "$.runs[0].steps")).hasSize(stepCount);
        f.mvc().perform(get("/api/traces/{traceId}", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(stepCount));

        // Vector Search: chunk citations, its ranking and the scored-chunk count; graph runs have no ranking.
        assertThat((String) JsonPath.read(body, "$.runs[3].citations[0].chunkId"))
                .isEqualTo("compare-all-live::chunk-0");
        assertThat((Integer) JsonPath.read(body, "$.runs[3].scoredChunkCount")).isEqualTo(2);
        assertThat((List<Object>) JsonPath.read(body, "$.runs[3].ranking")).hasSize(2);
        assertThat((Integer) JsonPath.read(body, "$.runs[3].timing.embeddingCalls")).isEqualTo(1);
        assertThat((List<Object>) JsonPath.read(body, "$.runs[?(@.method == 'LOCAL')].ranking")).isEmpty();

        // Evidence: tu-a first, cited by Local and (through chunk-0, rank 1) by Vector Search.
        assertThat((String) JsonPath.read(body, "$.evidence[0].id")).isEqualTo("tu-a");
        assertThat((String) JsonPath.read(body, "$.evidence[0].kind")).isEqualTo("text-unit");
        assertThat((String) JsonPath.read(body, "$.evidence[0].marks.LOCAL.use")).isEqualTo("CITED");
        assertThat((List<Integer>) JsonPath.read(body, "$.evidence[0].marks.LOCAL.citations")).containsExactly(1);
        assertThat((String) JsonPath.read(body, "$.evidence[0].marks.VECTOR.use")).isEqualTo("CITED");
        assertThat((Integer) JsonPath.read(body, "$.evidence[0].marks.VECTOR.rank")).isEqualTo(1);
        // chunk-1 is in Vector Search's context, uncited, and nobody else reached it.
        assertThat((String) JsonPath.read(body, "$.evidence[1].kind")).isEqualTo("chunk");
        assertThat((String) JsonPath.read(body, "$.evidence[1].marks.VECTOR.use")).isEqualTo("IN_CONTEXT");
        assertThat((String) JsonPath.read(body, "$.evidence[1].marks.LOCAL.use")).isEqualTo("NOT_RETRIEVED");
    }

    @Test
    void aFailingMethodIsReportedInItsColumnAndTheOthersStillAnswer() throws Exception {
        LlmPort failingDrift = new CitingLlm() {
            @Override
            public List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
                throw new OpenAiLlmPort.LlmCallFailedException("drift planning failed", new RuntimeException());
            }
        };
        Fixture f = fixture("compare-all-failing", failingDrift);

        String body = compareAll(f, "{\"question\": \"Who is Irene Adler?\"}", 200);

        assertThat((String) JsonPath.read(body, "$.runs[2].outcome")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(body, "$.runs[2].reason"))
                .isEqualTo(CorpusController.ANSWER_SYNTHESIS_FAILURE_MESSAGE);
        assertThat((String) JsonPath.read(body, "$.runs[0].outcome")).isEqualTo("ANSWERED");
        assertThat((String) JsonPath.read(body, "$.runs[3].outcome")).isEqualTo("ANSWERED");
        assertThat((String) JsonPath.read(body, "$.summary")).contains("DRIFT failed");
    }

    @Test
    void aBlankQuestionIsRejected() throws Exception {
        Fixture f = fixture("compare-all-validation", new CitingLlm());
        assertThat(compareAll(f, "{\"question\": \"   \"}", 400)).contains("Please enter a question first.");
        assertThat(compareAll(f, "", 400)).contains("Please enter a question first.");
    }

    @Test
    void aBuildingFailedOrOfflineCorpusIsBlockedAndAnUnknownOneIsNotFound() throws Exception {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        registry.put(new Corpus("compare-all-building", List.of(new UploadedDocument("a.txt", "A"))));
        registry.put(new Corpus("compare-all-failed", List.of(new UploadedDocument("b.txt", "B"))));
        registry.markFailed("compare-all-failed");
        registry.put(new Corpus("compare-all-offline", List.of(new UploadedDocument("c.txt", "C"))));
        registry.markReady("compare-all-offline");
        registry.markOffline("compare-all-offline");
        InMemoryVectorStoreAdapter vectors = new InMemoryVectorStoreAdapter();
        CorpusController controller = new CorpusController(null, registry, List.of(), null, null, null,
                new InMemoryGraphStoreAdapter(), new RetrievalTraceStore(), null,
                new AnswerVectorBaseline(QUERY, vectors, null), vectors, null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        for (String corpusId : List.of("compare-all-building", "compare-all-failed", "compare-all-offline")) {
            mvc.perform(post("/api/corpora/{id}/compare-all", corpusId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\": \"Who?\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").isNotEmpty());
        }
        mvc.perform(post("/api/corpora/{id}/compare-all", "compare-all-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"Who?\"}"))
                .andExpect(status().isNotFound());
    }
}
