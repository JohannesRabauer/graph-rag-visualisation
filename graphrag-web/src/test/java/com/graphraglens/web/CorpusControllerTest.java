package com.graphraglens.web;

import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.port.GraphStorePort;
import io.graphrag.core.port.LlmPort;
import io.graphrag.core.usecase.ConstructVectorIndex;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the corpus-upload acceptance criteria for plain-text and PDF uploads.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorpusControllerTest {

    // Was 5_000L before Story 12.3: graphStorePort()/vectorStorePort() are now
    // real Neo4j-backed adapters (a Cypher round-trip per persisted
    // entity/relationship/community, over the shared Testcontainers Neo4j)
    // rather than in-memory maps, so the pipeline genuinely takes longer end
    // to end. Widened rather than tightened elsewhere, to keep this an
    // honest wait for real I/O instead of masking a regression.
    private static final long PIPELINE_TIMEOUT_MS = 15_000L;

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        SharedNeo4jTestContainer.registerDynamicProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CorpusStore corpusStore;

    @Autowired
    private GraphStorePort graphStorePort;

    @MockitoSpyBean
    private CorpusProgressService corpusProgressService;

    @MockitoSpyBean
    private ConstructVectorIndex constructVectorIndex;

    @Test
    void progressEndpointStreamsHeartbeatEventsWithNamedEventEnvelope() throws Exception {
        mockMvc.perform(get("/api/corpora/progress-test/progress"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:heartbeat")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"type\":\"heartbeat\"")));
    }

    @Test
    void uploadingATxtFileReturns201WithCorpusIdAndDocumentNamesAndRegistersTheCorpus() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "test.txt", "text/plain", "hello world".getBytes(StandardCharsets.UTF_8));

        String responseBody = mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corpusId").exists())
                .andExpect(jsonPath("$.documentNames[0]").value("test.txt"))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(responseBody, "$.corpusId");
        Optional<Corpus> stored = corpusStore.get(corpusId);

        assertThat(stored).isPresent();
        assertThat(stored.get().documentNames()).containsExactly("test.txt");
        assertThat(stored.get().documents().get(0).content()).isEqualTo("hello world");
    }

    @Test
    void uploadingAPdfFileReturns201WithExtractedTextAndRegistersTheCorpus() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "report.pdf", "application/pdf", createPdfBytes("A PDF report for testing."));

        String responseBody = mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentNames[0]").value("report.pdf"))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(responseBody, "$.corpusId");
        Optional<Corpus> stored = corpusStore.get(corpusId);

        assertThat(stored).isPresent();
        assertThat(stored.get().documents().get(0).content()).contains("A PDF report for testing.");
    }

    @Test
    void uploadingMultipleTxtFilesRegistersAllOfThemOnOneCorpus() throws Exception {
        MockMultipartFile a = new MockMultipartFile("files", "a.txt", "text/plain", "a".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile b = new MockMultipartFile("files", "b.txt", "text/plain", "b".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/corpora").file(a).file(b))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentNames[0]").value("a.txt"))
                .andExpect(jsonPath("$.documentNames[1]").value("b.txt"))
                .andExpect(jsonPath("$.documentCount").value(2));
    }

    @Test
    void usingTheBuiltInDemoDatasetReturns201AndRegistersTheCorpus() throws Exception {
        String responseBody = mockMvc.perform(multipart("/api/corpora/demo"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sherlock Holmes — Demo Dataset"))
                .andExpect(jsonPath("$.documentCount").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(responseBody, "$.corpusId");
        Optional<Corpus> stored = corpusStore.get(corpusId);

        assertThat(stored).isPresent();
        assertThat(stored.get().name()).isEqualTo("Sherlock Holmes — Demo Dataset");
        assertThat(stored.get().documentNames()).containsExactly(
                "A Scandal in Bohemia.txt",
                "The Adventure of the Speckled Band.txt",
                "The Final Problem.txt");
        assertThat(stored.get().documents()).allSatisfy(document ->
                assertThat(document.content()).isNotBlank());
        assertThat(stored.get().documents().get(0).content()).contains("Irene Adler");
    }

    @Test
    void usingTheOfflineDemoDatasetReturns201WithDistinctNameOfflineFlagAndBuildsToReady() throws Exception {
        String responseBody = mockMvc.perform(post("/api/corpora/demo-offline"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sherlock Holmes — Offline Demo (no API calls)"))
                .andExpect(jsonPath("$.documentCount").value(3))
                .andExpect(jsonPath("$.offline").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(responseBody, "$.corpusId");
        assertThat(corpusStore.isOffline(corpusId)).isTrue();

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());
        assertThat(corpusStore.status(corpusId)).isEqualTo(CorpusStore.CorpusWorkflowStatus.READY);
    }

    @Test
    void theLiveDemoDatasetIsNeverMarkedOffline() throws Exception {
        String responseBody = mockMvc.perform(multipart("/api/corpora/demo"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.offline").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(responseBody, "$.corpusId");
        assertThat(corpusStore.isOffline(corpusId)).isFalse();
    }

    @Test
    void queryingTheOfflineDemoCorpusIsAlwaysBlockedEvenOnceReady() throws Exception {
        String responseBody = mockMvc.perform(post("/api/corpora/demo-offline"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String corpusId = JsonPath.read(responseBody, "$.corpusId");

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());
        assertThat(corpusStore.status(corpusId)).isEqualTo(CorpusStore.CorpusWorkflowStatus.READY);

        mockMvc.perform(post("/api/corpora/" + corpusId + "/query")
                        .contentType("application/json")
                        .content("{\"question\":\"Who is Irene Adler?\",\"mode\":\"LOCAL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("pre-recorded")));
    }

    @Test
    void aVectorIndexConstructionFailureDoesNotAffectKnowledgeGraphConstructionOrCorpusProgressEvents() throws Exception {
        doThrow(new RuntimeException("embedding boom")).when(constructVectorIndex).run(any());

        String responseBody = mockMvc.perform(multipart("/api/corpora/demo"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String corpusId = JsonPath.read(responseBody, "$.corpusId");

        // Knowledge-graph construction must still run to completion...
        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());
        assertThat(corpusStore.status(corpusId)).isEqualTo(CorpusStore.CorpusWorkflowStatus.READY);

        // ...and the vector-index failure must never be surfaced as a corpus/progress error.
        verify(corpusProgressService, never()).emit(eq(corpusId), eq("error"), any());
    }

    @Test
    void uploadingACorpusWithManyDisjointEntitiesGrowsCommunitiesAndMembershipsBeyondSharedTestClassState() throws Exception {
        // GraphStorePort is a shared, unreset @SpringBootTest singleton (no
        // per-Corpus isolation — see this story's Never boundary), so a bare
        // non-empty check on the unscoped communities()/communityMemberships()
        // reads could pass from another test's leftover data. Since Story
        // 12.3, GraphStorePort is also Neo4jGraphStoreAdapter, which (by
        // design, AD-20) never overrides those unscoped reads -- they always
        // return GraphStorePort's own empty default, regardless of what has
        // been persisted -- so this test instead reads through the
        // corpus-scoped overloads, keyed by this test's own freshly-generated
        // corpusId. A brand-new corpusId is guaranteed to start with zero
        // Communities/memberships regardless of what earlier tests in this
        // class already populated or what order tests run in, so before/after
        // counts against that one corpusId are enough to prove *this* test's
        // own pipeline run produced new data -- no need for the
        // many-mutually-unrelated-entities trick to force growth against a
        // shared aggregate view any more.
        MockMultipartFile file = new MockMultipartFile(
                "files", "many-entities.txt", "text/plain",
                manyDisjointEntitiesCorpusText().getBytes(StandardCharsets.UTF_8));

        String responseBody = mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String corpusId = JsonPath.read(responseBody, "$.corpusId");

        assertThat(graphStorePort.communities(corpusId)).isEmpty();
        assertThat(graphStorePort.communityMemberships(corpusId)).isEmpty();

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());

        assertThat(graphStorePort.communities(corpusId)).isNotEmpty();
        assertThat(graphStorePort.communityMemberships(corpusId)).isNotEmpty();
    }

    /**
     * 20 sentences, each naming exactly one two-word proper noun and no
     * other capitalized words, so {@code LangChain4jLlmPort} extracts 20
     * distinct Entities with zero Relationships between them — 20 singleton
     * connected components, i.e. 20 Communities, comfortably more than the
     * fixed, deterministic count the three-document Sherlock demo dataset
     * produces.
     */
    private static String manyDisjointEntitiesCorpusText() {
        String[] names = {
                "Aria Solberg", "Bruno Castellan", "Celia Dunmore", "Dario Fenwick", "Elena Granger",
                "Felix Harrow", "Greta Ibsen", "Hugo Jarrow", "Ines Kestrel", "Jonas Larkspur",
                "Kira Marchetti", "Leo Norwood", "Mira Okafor", "Nils Prescott", "Odette Quillon",
                "Pavel Ronan", "Quinn Sorensen", "Rhea Thackeray", "Silas Underwood", "Tessa Voss"
        };
        StringBuilder text = new StringBuilder();
        for (String name : names) {
            text.append(name).append(" pioneered a completely unrelated idea. ");
        }
        return text.toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    void demoPipelineEmitsGranularSseEventsWithTheExpectedPayloadKeys() throws Exception {
        String responseBody = mockMvc.perform(multipart("/api/corpora/demo"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String corpusId = JsonPath.read(responseBody, "$.corpusId");

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(corpusProgressService, org.mockito.Mockito.atLeastOnce())
                .emit(eq(corpusId), eventTypeCaptor.capture(), payloadCaptor.capture());

        List<String> eventTypes = eventTypeCaptor.getAllValues();
        List<Map<String, Object>> payloads = payloadCaptor.getAllValues();

        assertThat(eventTypes).contains("entity-extracted", "relationship-extracted", "community-detected");

        Map<String, Object> entityPayload = payloads.get(eventTypes.indexOf("entity-extracted"));
        assertThat(entityPayload).containsKeys("identity", "name", "type");

        Map<String, Object> relationshipPayload = payloads.get(eventTypes.indexOf("relationship-extracted"));
        assertThat(relationshipPayload).containsKeys("sourceIdentity", "source", "targetIdentity", "target", "type");

        Map<String, Object> communityPayload = payloads.get(eventTypes.indexOf("community-detected"));
        assertThat(communityPayload).containsKeys("communityId", "summary", "memberEntityIdentities");
    }

    @Test
    void submittingAQuestionReturnsAnAnswerAndTraceIdsForTheCorpus() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "sherlock.txt", "text/plain",
                "Irene Adler outwitted Sherlock Holmes by stealing the photograph. Holmes admired her ingenuity.".getBytes(StandardCharsets.UTF_8));

        String uploadBody = mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(uploadBody, "$.corpusId");
        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());

        mockMvc.perform(post("/api/corpora/{corpusId}/query", corpusId)
                        .contentType("application/json")
                        .content("{\"question\":\"Why was Irene Adler able to outwit Holmes?\",\"mode\":\"LOCAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerId").exists())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.mode").value("LOCAL"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("Irene Adler")));
    }

    @Test
    void localSearchTraceIsFetchableAndReportsTheNamedEntitiesInTheMatchedSentence() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "sherlock-trace.txt", "text/plain",
                "Irene Adler outwitted Sherlock Holmes by stealing the photograph. Holmes admired her ingenuity."
                        .getBytes(StandardCharsets.UTF_8));

        String uploadBody = mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String corpusId = JsonPath.read(uploadBody, "$.corpusId");

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());

        String queryBody = mockMvc.perform(post("/api/corpora/{corpusId}/query", corpusId)
                        .contentType("application/json")
                        .content("{\"question\":\"Why was Irene Adler able to outwit Holmes?\",\"mode\":\"LOCAL\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String traceId = JsonPath.read(queryBody, "$.traceId");

        mockMvc.perform(get("/api/traces/{traceId}", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.steps[0].kind").value("ENTITY"))
                .andExpect(jsonPath("$.steps[*].label", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsStringIgnoringCase("Irene Adler"))));
    }

    @Test
    void localSearchTraceContainsGraphElementsInsteadOfDocumentSentences() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "order-trace.txt", "text/plain",
                "Irene Adler outwitted Sherlock Holmes by stealing the photograph."
                        .getBytes(StandardCharsets.UTF_8));

        String uploadBody = mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String corpusId = JsonPath.read(uploadBody, "$.corpusId");

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());

        String queryBody = mockMvc.perform(post("/api/corpora/{corpusId}/query", corpusId)
                        .contentType("application/json")
                        .content("{\"question\":\"Why was Irene Adler able to outwit Sherlock Holmes?\",\"mode\":\"LOCAL\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String traceId = JsonPath.read(queryBody, "$.traceId");

        String traceBody = mockMvc.perform(get("/api/traces/{traceId}", traceId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> kinds = JsonPath.read(traceBody, "$.steps[*].kind");
        assertThat(kinds).contains("ENTITY");
        assertThat(kinds).contains("RELATIONSHIP");
        assertThat(kinds).isNotEmpty();
    }

    @Test
    void queryingBeforeIngestionCompletesReturnsConflictWithReadinessGuidance() throws Exception {
        CorpusStore isolatedCorpusStore = new CorpusStore();
        Corpus corpus = new Corpus("building-corpus", List.of(new io.graphrag.core.domain.UploadedDocument("doc.txt", "content")));
        isolatedCorpusStore.put(corpus);
        CorpusController controller = new CorpusController(
                null, isolatedCorpusStore, List.of(), null, null, null, graphStorePort, new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "Is it ready?", "mode", "LOCAL"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void driftQueryWhileGraphIsStillBuildingReturnsTheExistingConflictResponse() {
        CorpusStore isolatedCorpusStore = new CorpusStore();
        Corpus corpus = new Corpus("building-corpus", List.of(new io.graphrag.core.domain.UploadedDocument("doc.txt", "content")));
        isolatedCorpusStore.put(corpus);
        CorpusController controller = new CorpusController(
                null, isolatedCorpusStore, List.of(), null, null, null, graphStorePort, new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "Is it ready?", "mode", "DRIFT"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("error",
                "The graph is still building for this corpus. Wait for “Knowledge Graph — Ready”, then ask your question.");
    }

    @Test
    void queryingAfterFailedIngestionReturnsConflictWithFailureGuidance() {
        CorpusStore isolatedCorpusStore = new CorpusStore();
        Corpus corpus = new Corpus("failed-corpus", List.of(new io.graphrag.core.domain.UploadedDocument("doc.txt", "content")));
        isolatedCorpusStore.put(corpus);
        isolatedCorpusStore.markFailed(corpus.id());
        CorpusController controller = new CorpusController(
                null, isolatedCorpusStore, List.of(), null, null, null, graphStorePort, new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "Is it ready?", "mode", "LOCAL"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void localSearchReadsOnlyGraphDataFromTheSelectedCorpus() {
        CorpusStore isolatedCorpusStore = new CorpusStore();
        Corpus corpusA = new Corpus("corpus-a", List.of(new io.graphrag.core.domain.UploadedDocument("a.txt", "A")));
        Corpus corpusB = new Corpus("corpus-b", List.of(new io.graphrag.core.domain.UploadedDocument("b.txt", "B")));
        isolatedCorpusStore.put(corpusA);
        isolatedCorpusStore.put(corpusB);
        isolatedCorpusStore.markReady(corpusA.id());
        isolatedCorpusStore.markReady(corpusB.id());

        com.graphraglens.adapter.neo4j.InMemoryGraphStoreAdapter scopedGraphStore =
                new com.graphraglens.adapter.neo4j.InMemoryGraphStoreAdapter();
        scopedGraphStore.persistEntities(corpusA.id(), List.of(new io.graphrag.core.domain.Entity("Irene Adler", "Person")));
        scopedGraphStore.persistEntities(corpusB.id(), List.of(new io.graphrag.core.domain.Entity("Professor Moriarty", "Person")));

        CorpusController controller = new CorpusController(
                null, isolatedCorpusStore, List.of(), null, null, null, scopedGraphStore, new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpusA.id(), Map.of("question", "Who is Moriarty?", "mode", "LOCAL"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsKey("answer");
        assertThat(String.valueOf(response.getBody().get("answer"))).doesNotContain("Moriarty");
    }

    @Test
    void localSearchNoMatchStillProducesAFetchableZeroStepTrace() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "no-match.txt", "text/plain",
                "Irene Adler outwitted Sherlock Holmes by stealing the photograph.".getBytes(StandardCharsets.UTF_8));

        String uploadBody = mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String corpusId = JsonPath.read(uploadBody, "$.corpusId");

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());

        String queryBody = mockMvc.perform(post("/api/corpora/{corpusId}/query", corpusId)
                        .contentType("application/json")
                        .content("{\"question\":\"zzqqxx nonexistent gibberish flimflam\",\"mode\":\"LOCAL\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String traceId = JsonPath.read(queryBody, "$.traceId");

        mockMvc.perform(get("/api/traces/{traceId}", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.steps", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void fetchingAnUnknownTraceIdReturns404WithAPlainLanguageError() throws Exception {
        mockMvc.perform(get("/api/traces/{traceId}", "no-such-trace-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("no-such-trace-id")));
    }

    @Test
    void globalSearchAnswersFromACommunitySummaryOnceCommunitiesExist() throws Exception {
        String responseBody = mockMvc.perform(multipart("/api/corpora/demo"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String corpusId = JsonPath.read(responseBody, "$.corpusId");

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());

        mockMvc.perform(post("/api/corpora/{corpusId}/query", corpusId)
                        .contentType("application/json")
                        .content("{\"question\":\"What community centers on Irene Adler?\",\"mode\":\"GLOBAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerId").exists())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.mode").value("GLOBAL"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("Irene Adler")))
                .andExpect(jsonPath("$.noAnswer").doesNotExist());
    }

    @Test
    void globalSearchTraceIsFetchableAndHasOneStepPerCommunityExamined() throws Exception {
        String responseBody = mockMvc.perform(multipart("/api/corpora/demo"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String corpusId = JsonPath.read(responseBody, "$.corpusId");

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());

        int communityCount = graphStorePort.communities(corpusId).size();

        String queryBody = mockMvc.perform(post("/api/corpora/{corpusId}/query", corpusId)
                        .contentType("application/json")
                        .content("{\"question\":\"What community centers on Irene Adler?\",\"mode\":\"GLOBAL\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String traceId = JsonPath.read(queryBody, "$.traceId");

        mockMvc.perform(get("/api/traces/{traceId}", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.steps", org.hamcrest.Matchers.hasSize(communityCount)))
                .andExpect(jsonPath("$.steps[0].kind").value("COMMUNITY"));
    }

    @Test
    void globalSearchStillReturnsAnOrdinaryAnswerWhenNoCommunityClearlyMatchesTheQuestion() throws Exception {
        String responseBody = mockMvc.perform(multipart("/api/corpora/demo"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String corpusId = JsonPath.read(responseBody, "$.corpusId");

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());

        mockMvc.perform(post("/api/corpora/{corpusId}/query", corpusId)
                        .contentType("application/json")
                        .content("{\"question\":\"zzqqxx nonsense gibberish flimflam\",\"mode\":\"GLOBAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerId").exists())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.mode").value("GLOBAL"))
                .andExpect(jsonPath("$.answer").exists())
                .andExpect(jsonPath("$.noAnswer").doesNotExist());
    }

    @Test
    void driftSearchReturnsTheDistinctNoAnswerShapeWhenNoCommunitiesExistYet() {
        CorpusStore isolatedCorpusStore = new CorpusStore();
        Corpus corpus = new Corpus("ready-corpus", List.of(new io.graphrag.core.domain.UploadedDocument("doc.txt", "content")));
        isolatedCorpusStore.put(corpus);
        isolatedCorpusStore.markReady(corpus.id());
        RetrievalTraceStore retrievalTraceStore = new RetrievalTraceStore();
        CorpusController controller = new CorpusController(
                null, isolatedCorpusStore, List.of(), null, null, stubLlmPort(), graphStorePort, retrievalTraceStore, null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "Try DRIFT", "mode", "DRIFT"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsKeys("answerId", "traceId", "traceStepCount", "noAnswer", "reason");
        assertThat(response.getBody()).containsEntry("traceStepCount", 0);
        assertThat(response.getBody()).containsEntry("noAnswer", true);
        assertThat(response.getBody()).containsEntry("reason",
                "DRIFT Search cannot run yet because this corpus has no Community summaries. Wait for the Community "
                        + "pass to finish, then try again.");
        assertThat(response.getBody()).doesNotContainKeys("answer", "mode");

        String traceId = String.valueOf(response.getBody().get("traceId"));
        assertThat(retrievalTraceStore.get(traceId)).isPresent();
        assertThat(retrievalTraceStore.get(traceId).orElseThrow().steps()).isEmpty();
    }

    private static LlmPort stubLlmPort() {
        return corpus -> new GraphExtraction(List.of(), List.of());
    }

    @Test
    void unrecognizedModesListDriftInTheValidationError() {
        CorpusStore isolatedCorpusStore = new CorpusStore();
        Corpus corpus = new Corpus("ready-corpus", List.of(new io.graphrag.core.domain.UploadedDocument("doc.txt", "content")));
        isolatedCorpusStore.put(corpus);
        isolatedCorpusStore.markReady(corpus.id());
        CorpusController controller = new CorpusController(
                null, isolatedCorpusStore, List.of(), null, null, null, graphStorePort, new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "Try something else", "mode", "FOO"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "Search mode must be LOCAL, GLOBAL, DRIFT, or VECTOR.");
    }

    @Test
    void uploadingAnUnsupportedFileTypeReturns400WithAPlainLanguageErrorNamingTheFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "test.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "not really a docx".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("test.docx")));
    }

    @Test
    void uploadingNoFilesReturns400WithANoFilesProvidedError() throws Exception {
        mockMvc.perform(multipart("/api/corpora"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("No files were provided."));
    }

    @Test
    void uploadingAMixOfSupportedAndUnsupportedFilesReturns400AndRegistersNoCorpus() throws Exception {
        int sizeBefore = corpusStore.size();
        MockMultipartFile valid = new MockMultipartFile(
                "files", "ok.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile invalid = new MockMultipartFile(
                "files", "bad.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "nope".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/corpora").file(valid).file(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bad.docx")));

        assertThat(corpusStore.size()).isEqualTo(sizeBefore);
    }

    @Test
    void uploadingAFileThatFailsToReadReturns500WithAGenericPlainLanguageError() throws Exception {
        MockMultipartFile file = new ThrowingMultipartFile("files", "broken.txt", "text/plain");

        mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Failed to read an uploaded file. Please try again."));
    }

    private static byte[] createPdfBytes(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(100, 700);
                contentStream.showText(text);
                contentStream.endText();
            }

            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }

    /**
     * A {@link MockMultipartFile} whose {@code getBytes()} always throws, to
     * exercise {@code CorpusController}'s {@code IOException} handling
     * without needing a real broken stream. Flows through {@code MockMvc}'s
     * multipart request unchanged (no re-parsing), so it reaches the
     * controller exactly as constructed here.
     */
    private static final class ThrowingMultipartFile extends MockMultipartFile {

        ThrowingMultipartFile(String name, String originalFilename, String contentType) {
            super(name, originalFilename, contentType, new byte[0]);
        }

        @Override
        public byte[] getBytes() throws IOException {
            throw new IOException("Simulated read failure");
        }
    }
}
