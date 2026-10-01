package dev.rabauer.graphrag.web;

import com.jayway.jsonpath.JsonPath;
import dev.rabauer.graphrag.adapter.langchain4j.OpenAiLlmPort;
import dev.rabauer.graphrag.adapter.neo4j.InMemoryGraphStoreAdapter;
import dev.rabauer.graphrag.adapter.neo4j.InMemoryVectorStoreAdapter;
import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.ComparisonFacts;
import dev.rabauer.graphrag.core.domain.ComparisonVerdict;
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

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/corpora/{id}/compare} and {@code GET /api/corpora/{id}/chunks/{chunkId}}:
 * both sides answered fresh, key figures, passage overlap and the verdict.
 */
class CorpusControllerCompareTest {

    private static final EmbeddingPort QUERY = text -> new float[]{1f, 0f};

    /** Cites the first Source passage of each context; a configurable verdict. */
    private static LlmPort llm(boolean synthesizes, Function<List<ContextItem>, SynthesizedAnswer> answer,
                               Function<ComparisonFacts, ComparisonVerdict> verdict) {
        return new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus corpus) {
                return new GraphExtraction(List.of(), List.of());
            }

            @Override
            public boolean synthesizesAnswers() {
                return synthesizes;
            }

            @Override
            public SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context) {
                return answer.apply(context);
            }

            @Override
            public ComparisonVerdict compareAnswers(String question, String graphAnswer, String vectorAnswer,
                                                    ComparisonFacts facts) {
                return verdict == null ? LlmPort.super.compareAnswers(question, graphAnswer, vectorAnswer, facts)
                        : verdict.apply(facts);
            }
        };
    }

    private static SynthesizedAnswer citeFirstPassage(List<ContextItem> context) {
        int first = context.stream().filter(ContextItem::isTextUnit).findFirst().orElseThrow().number();
        return new SynthesizedAnswer(false, "Irene Adler left London [" + first + "].");
    }

    private record Fixture(MockMvc mvc, String corpusId, RetrievalTraceStore traces) {
    }

    private static Fixture fixture(String corpusId, LlmPort llmPort, boolean withChunks) {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        registry.put(new Corpus(corpusId, List.of(new UploadedDocument("story.txt", "Irene Adler left London."))));
        registry.markReady(corpusId);

        InMemoryGraphStoreAdapter graph = new InMemoryGraphStoreAdapter();
        graph.persistEntities(corpusId, List.of(new Entity("Irene Adler", "Person", "An opera singer.",
                List.of("tu-a"))));
        graph.persistTextUnits(corpusId, List.of(new TextUnit("tu-a", corpusId, "story.txt", 0,
                "Irene Adler   left\nLondon. She sang at the opera.")));

        InMemoryVectorStoreAdapter vectors = new InMemoryVectorStoreAdapter();
        if (withChunks) {
            vectors.persistChunks(corpusId, List.of(
                    new EmbeddedChunk(new Chunk(corpusId + "::chunk-0", corpusId, 0, "Irene Adler left London.",
                            "story.txt"), new float[]{1f, 0f}, new double[]{0.5, 0.5}),
                    new EmbeddedChunk(new Chunk(corpusId + "::chunk-1", corpusId, 1, "Unrelated text."),
                            new float[]{0.2f, 0.8f}, new double[]{-0.5, 0.5})));
        }

        RetrievalTraceStore traces = new RetrievalTraceStore();
        CorpusController controller = new CorpusController(null, registry, List.of(), null, null, llmPort, graph,
                traces, null, new AnswerVectorBaseline(QUERY, vectors, llmPort), vectors, null);
        return new Fixture(MockMvcBuilders.standaloneSetup(controller).build(), corpusId, traces);
    }

    private static String compareBody(String question, String mode) {
        return mode == null ? "{\"question\": \"" + question + "\"}"
                : "{\"question\": \"" + question + "\", \"mode\": \"" + mode + "\"}";
    }

    @Test
    void aLiveComparisonReturnsBothCitedAnswersStatsOverlapAndAnLlmVerdict() throws Exception {
        Fixture f = fixture("compare-live", llm(true, CorpusControllerCompareTest::citeFirstPassage,
                facts -> new ComparisonVerdict("GraphRAG read the whole passage.", ComparisonVerdict.Source.LLM)),
                true);

        String body = f.mvc().perform(post("/api/corpora/{id}/compare", f.corpusId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareBody("Who is Irene Adler?", "local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("Who is Irene Adler?"))
                .andExpect(jsonPath("$.graph.mode").value("LOCAL"))
                .andExpect(jsonPath("$.graph.noAnswer").value(false))
                .andExpect(jsonPath("$.graph.answer").value("Irene Adler left London [1]."))
                .andExpect(jsonPath("$.graph.citations[0].textUnitId").value("tu-a"))
                .andExpect(jsonPath("$.graph.stats.contextItems").value(2))
                .andExpect(jsonPath("$.graph.stats.distinctDocuments").value(1))
                .andExpect(jsonPath("$.graph.stats.latencyMs").isNumber())
                .andExpect(jsonPath("$.vector.noAnswer").value(false))
                .andExpect(jsonPath("$.vector.answer").value("Irene Adler left London [1]."))
                .andExpect(jsonPath("$.vector.citations[0].chunkId").value("compare-live::chunk-0"))
                .andExpect(jsonPath("$.vector.citations[0].documentName").value("story.txt"))
                .andExpect(jsonPath("$.vector.citations[0].excerpt").value("Irene Adler left London."))
                .andExpect(jsonPath("$.vector.queryProjection.length()").value(2))
                .andExpect(jsonPath("$.vector.scoredChunkCount").value(2))
                .andExpect(jsonPath("$.vector.ranking.length()").value(2))
                .andExpect(jsonPath("$.vector.ranking[0].rank").value(1))
                .andExpect(jsonPath("$.vector.ranking[0].chunkId").value("compare-live::chunk-0"))
                .andExpect(jsonPath("$.vector.ranking[0].documentName").value("story.txt"))
                .andExpect(jsonPath("$.vector.ranking[0].excerpt").value("Irene Adler left London."))
                .andExpect(jsonPath("$.vector.ranking[0].score").value(org.hamcrest.Matchers.closeTo(1.0, 1e-6)))
                .andExpect(jsonPath("$.vector.ranking[0].used").value(true))
                .andExpect(jsonPath("$.vector.ranking[1].rank").value(2))
                .andExpect(jsonPath("$.vector.ranking[1].chunkId").value("compare-live::chunk-1"))
                .andExpect(jsonPath("$.vector.ranking[1].documentName").value(""))
                .andExpect(jsonPath("$.vector.ranking[1].used").value(true))
                .andExpect(jsonPath("$.vector.stats.contextItems").value(2))
                // chunk-1 has no document name (an older corpus): unknown, not counted.
                .andExpect(jsonPath("$.vector.stats.distinctDocuments").value(1))
                .andExpect(jsonPath("$.overlap.vector[0].chunkId").value("compare-live::chunk-0"))
                .andExpect(jsonPath("$.overlap.vector[0].sharedWithGraph").value(true))
                .andExpect(jsonPath("$.overlap.graph[0].textUnitId").value("tu-a"))
                .andExpect(jsonPath("$.overlap.graph[0].sharedWithVector").value(true))
                .andExpect(jsonPath("$.overlap.sharedPassages").value(1))
                .andExpect(jsonPath("$.overlap.vectorPassages").value(2))
                .andExpect(jsonPath("$.verdict.text").value("GraphRAG read the whole passage."))
                .andExpect(jsonPath("$.verdict.source").value("llm"))
                .andExpect(jsonPath("$.graph.retrieved.length()").value(1))
                .andExpect(jsonPath("$.graph.retrieved[0].textUnitId").value("tu-a"))
                .andExpect(jsonPath("$.graph.retrieved[0].documentName").value("story.txt"))
                .andExpect(jsonPath("$.graph.retrieved[0].excerpt").value("Irene Adler left London. She sang at the opera."))
                .andExpect(jsonPath("$.graph.retrieved[0].sharedWithVector").value(true))
                .andExpect(jsonPath("$.vector.retrieved.length()").value(2))
                .andExpect(jsonPath("$.vector.retrieved[0].chunkId").value("compare-live::chunk-0"))
                .andExpect(jsonPath("$.vector.retrieved[0].documentName").value("story.txt"))
                .andExpect(jsonPath("$.vector.retrieved[0].sharedWithGraph").value(true))
                .andExpect(jsonPath("$.vector.retrieved[1].chunkId").value("compare-live::chunk-1"))
                .andExpect(jsonPath("$.vector.retrieved[1].sharedWithGraph").value(false))
                .andReturn().getResponse().getContentAsString();

        // Both traces are stored and replayable; every vector citation is one of its VECTOR_CHUNK steps.
        String vectorTraceId = JsonPath.read(body, "$.vector.traceId");
        String graphTraceId = JsonPath.read(body, "$.graph.traceId");
        assertThat(f.traces().get(graphTraceId)).isPresent();
        String vectorTrace = f.mvc().perform(get("/api/traces/{id}", vectorTraceId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> chunkSteps = JsonPath.read(vectorTrace, "$.steps[?(@.kind == 'VECTOR_CHUNK')].identifier");
        List<String> cited = JsonPath.read(body, "$.vector.citations[*].chunkId");
        assertThat(chunkSteps).containsAll(cited);
        assertThat((Integer) JsonPath.read(body, "$.vector.traceStepCount"))
                .isEqualTo(f.traces().get(vectorTraceId).orElseThrow().steps().size());
    }

    @Test
    void anOfflineComparisonUsesTheTemplatedAndJoinedAnswersAndTheRuleVerdict() throws Exception {
        Fixture f = fixture("compare-offline", llm(false, context -> null, null), true);

        f.mvc().perform(post("/api/corpora/{id}/compare", f.corpusId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareBody("Who is Irene Adler?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graph.mode").value("LOCAL"))
                .andExpect(jsonPath("$.graph.citations.length()").value(0))
                .andExpect(jsonPath("$.vector.answer").value(org.hamcrest.Matchers.startsWith(
                        "Based on the retrieved text passages: ")))
                .andExpect(jsonPath("$.vector.citations.length()").value(0))
                .andExpect(jsonPath("$.vector.retrieved.length()").value(2))
                .andExpect(jsonPath("$.graph.retrieved.length()").value(0))
                .andExpect(jsonPath("$.overlap.vector.length()").value(0))
                .andExpect(jsonPath("$.verdict.source").value("rule"))
                .andExpect(jsonPath("$.verdict.text").isNotEmpty());
    }

    @Test
    void aVectorNotInContextIsAVectorNoAnswerWhileTheGraphSideStillAnswers() throws Exception {
        Fixture f = fixture("compare-nic", llm(true, context -> context.stream()
                        .anyMatch(item -> item.kind() == dev.rabauer.graphrag.core.domain.RetrievalStep.Kind.ENTITY)
                        ? citeFirstPassage(context) : new SynthesizedAnswer(true, ""), null),
                true);

        f.mvc().perform(post("/api/corpora/{id}/compare", f.corpusId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareBody("Who is Irene Adler?", "LOCAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graph.noAnswer").value(false))
                .andExpect(jsonPath("$.vector.noAnswer").value(true))
                .andExpect(jsonPath("$.vector.reason").isNotEmpty())
                .andExpect(jsonPath("$.vector.answer").doesNotExist())
                .andExpect(jsonPath("$.vector.citations.length()").value(0))
                // The ranking is still shown: the chunks were scored, they just did not answer.
                .andExpect(jsonPath("$.vector.ranking.length()").value(2))
                .andExpect(jsonPath("$.vector.scoredChunkCount").value(2));
    }

    @Test
    void anEmptyVectorIndexIsTheExistingNoChunksReason() throws Exception {
        Fixture f = fixture("compare-nochunks", llm(false, context -> null, null), false);

        f.mvc().perform(post("/api/corpora/{id}/compare", f.corpusId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareBody("Who is Irene Adler?", "LOCAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vector.noAnswer").value(true))
                .andExpect(jsonPath("$.vector.reason").value(org.hamcrest.Matchers.containsString(
                        "vector index for this corpus is not ready yet")))
                .andExpect(jsonPath("$.vector.traceStepCount").value(0))
                .andExpect(jsonPath("$.vector.ranking.length()").value(0))
                .andExpect(jsonPath("$.vector.scoredChunkCount").value(0));
    }

    @Test
    void aFailingVerdictFallsBackToTheRuleText() throws Exception {
        Fixture f = fixture("compare-verdict-fail", llm(true, CorpusControllerCompareTest::citeFirstPassage,
                facts -> {
                    throw new OpenAiLlmPort.LlmCallFailedException("verdict outage", null);
                }), true);

        f.mvc().perform(post("/api/corpora/{id}/compare", f.corpusId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareBody("Who is Irene Adler?", "LOCAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict.source").value("rule"));
    }

    @Test
    void aFailingAnswerCallIsTheUsual502() throws Exception {
        Fixture f = fixture("compare-answer-fail", llm(true, context -> {
            throw new OpenAiLlmPort.LlmCallFailedException("answer outage", null);
        }, null), true);

        f.mvc().perform(post("/api/corpora/{id}/compare", f.corpusId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareBody("Who is Irene Adler?", "LOCAL")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(CorpusController.ANSWER_SYNTHESIS_FAILURE_MESSAGE));
    }

    @Test
    void rejectsAVectorOrUnknownModeAndABlankQuestion() throws Exception {
        Fixture f = fixture("compare-invalid", llm(false, context -> null, null), true);

        for (String mode : List.of("VECTOR", "nonsense")) {
            f.mvc().perform(post("/api/corpora/{id}/compare", f.corpusId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(compareBody("Who?", mode)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith(
                            "Search mode must be LOCAL, GLOBAL, or DRIFT")));
        }
        f.mvc().perform(post("/api/corpora/{id}/compare", f.corpusId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareBody(" ", "LOCAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Please enter a question first."));
    }

    @Test
    void offlineBuildingAndFailedCorporaGetTheQueryConflicts() throws Exception {
        Neo4jCorpusRegistry registry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        registry.put(new Corpus("compare-building", List.of(new UploadedDocument("a.txt", "A"))));
        registry.put(new Corpus("compare-failed", List.of(new UploadedDocument("b.txt", "B"))));
        registry.markFailed("compare-failed");
        registry.put(new Corpus("compare-offline-demo", List.of(new UploadedDocument("c.txt", "C"))));
        registry.markReady("compare-offline-demo");
        registry.markOffline("compare-offline-demo");
        InMemoryVectorStoreAdapter vectors = new InMemoryVectorStoreAdapter();
        CorpusController controller = new CorpusController(null, registry, List.of(), null, null, null,
                new InMemoryGraphStoreAdapter(), new RetrievalTraceStore(), null,
                new AnswerVectorBaseline(QUERY, vectors, null), vectors, null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        for (String corpusId : List.of("compare-building", "compare-failed", "compare-offline-demo")) {
            mvc.perform(post("/api/corpora/{id}/compare", corpusId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(compareBody("Who?", "LOCAL")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").isNotEmpty());
        }
        mvc.perform(post("/api/corpora/{id}/compare", "compare-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareBody("Who?", "LOCAL")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theChunkEndpointReturnsAChunksTextAndDocument() throws Exception {
        Fixture f = fixture("compare-chunk", llm(false, context -> null, null), true);

        f.mvc().perform(get("/api/corpora/{id}/chunks/{chunkId}", f.corpusId(), "compare-chunk::chunk-0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("compare-chunk::chunk-0"))
                .andExpect(jsonPath("$.documentName").value("story.txt"))
                .andExpect(jsonPath("$.ordinal").value(0))
                .andExpect(jsonPath("$.text").value("Irene Adler left London."));
        // An older chunk without a document reads as "".
        f.mvc().perform(get("/api/corpora/{id}/chunks/{chunkId}", f.corpusId(), "compare-chunk::chunk-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentName").value(""));
        f.mvc().perform(get("/api/corpora/{id}/chunks/{chunkId}", f.corpusId(), "nope"))
                .andExpect(status().isNotFound());
    }
}
