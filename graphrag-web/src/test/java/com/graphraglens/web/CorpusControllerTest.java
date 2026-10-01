package com.graphraglens.web;

import com.graphraglens.adapter.neo4j.Neo4jCorpusRegistry;
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
import static org.mockito.Mockito.mockingDetails;
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
    // rather than in-memory maps. Story 13.2 also persists provenance
    // properties and MENTIONED_IN links, so the pipeline genuinely takes
    // longer end to end. Widened rather than tightened elsewhere, to keep
    // this an honest wait for real I/O instead of masking a regression.
    private static final long PIPELINE_TIMEOUT_MS = 30_000L;

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        SharedNeo4jTestContainer.registerDynamicProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Neo4jCorpusRegistry corpusRegistry;

    @Autowired
    private GraphStorePort graphStorePort;

    @MockitoSpyBean
    private CorpusProgressService corpusProgressService;

    @MockitoSpyBean
    private ConstructVectorIndex constructVectorIndex;

    @Autowired
    private io.graphrag.core.usecase.IngestCorpus ingestCorpus;

    @Autowired
    private List<io.graphrag.core.port.DocumentParserPort> documentParsers;

    @Test
    void progressEndpointStreamsHeartbeatEventsWithNamedEventEnvelope() throws Exception {
        mockMvc.perform(get("/api/corpora/progress-test/progress"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:heartbeat")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"type\":\"heartbeat\"")));
    }

    @Test
    void progressEndpointReplaysMoreThanFiveHundredBufferedEventsToALateSubscriber() throws Exception {
        // Story 13.1: per-passage extraction emits far more events than the
        // old 500-event replay cap; a late subscriber must still see the first.
        String corpusId = "replay-buffer-" + java.util.UUID.randomUUID();
        for (int i = 0; i < 1_200; i++) {
            corpusProgressService.emit(corpusId, "text-unit-extracted",
                    Map.of("index", i + 1, "total", 1_200, "documentName", "passage-" + (i + 1) + ".txt"));
        }

        mockMvc.perform(get("/api/corpora/" + corpusId + "/progress"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"passage-1.txt\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"passage-1200.txt\"")));
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
        Optional<Corpus> stored = corpusRegistry.get(corpusId);

        // Story 12.4: Neo4jCorpusRegistry never persists raw document bytes, only
        // documentNames() -- a registry lookup reconstructs Corpus.documents() as
        // empty-content placeholders, so content is asserted from the immediate
        // upload response/ingest result elsewhere, never from a registry re-fetch.
        assertThat(stored).isPresent();
        assertThat(stored.get().documentNames()).containsExactly("test.txt");
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
        Optional<Corpus> stored = corpusRegistry.get(corpusId);

        // Story 12.4: the registry never persists raw PDF-extracted text, only the
        // filename -- extraction itself is covered by the response's documentNames
        // assertion above and by PdfDocumentParserAdapter's own unit tests.
        assertThat(stored).isPresent();
        assertThat(stored.get().documentNames()).containsExactly("report.pdf");
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
        Optional<Corpus> stored = corpusRegistry.get(corpusId);

        // Story 12.4: the registry never persists raw document text, only
        // documentNames() -- the demo dataset's actual text content is covered by
        // DemoDatasetService's own tests, not by a post-ingestion registry re-fetch.
        assertThat(stored).isPresent();
        assertThat(stored.get().name()).isEqualTo("Sherlock Holmes — Demo Dataset");
        assertThat(stored.get().documentNames()).containsExactly(
                "A Scandal in Bohemia.txt",
                "The Adventure of the Speckled Band.txt",
                "The Final Problem.txt");
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
        assertThat(corpusRegistry.isOffline(corpusId)).isTrue();

        waitForProgressEvent(corpusId, "ingestion-complete");
        assertThat(corpusRegistry.status(corpusId)).isEqualTo(Neo4jCorpusRegistry.CorpusWorkflowStatus.READY);
    }

    private void waitForProgressEvent(String corpusId, String eventType) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PIPELINE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            boolean seen = mockingDetails(corpusProgressService).getInvocations().stream()
                    .anyMatch(invocation -> invocation.getMethod().getName().equals("emit")
                            && invocation.getArguments().length >= 2
                            && corpusId.equals(invocation.getArgument(0))
                            && eventType.equals(invocation.getArgument(1)));
            if (seen) {
                return;
            }
            Thread.sleep(100L);
        }
        verify(corpusProgressService).emit(eq(corpusId), eq(eventType), any());
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
        assertThat(corpusRegistry.isOffline(corpusId)).isFalse();
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
        assertThat(corpusRegistry.status(corpusId)).isEqualTo(Neo4jCorpusRegistry.CorpusWorkflowStatus.READY);

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
        assertThat(corpusRegistry.status(corpusId)).isEqualTo(Neo4jCorpusRegistry.CorpusWorkflowStatus.READY);

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
        assertThat(entityPayload).containsKeys("identity", "name", "type", "description");

        Map<String, Object> relationshipPayload = payloads.get(eventTypes.indexOf("relationship-extracted"));
        assertThat(relationshipPayload).containsKeys("sourceIdentity", "source", "targetIdentity", "target", "type",
                "description");

        Map<String, Object> communityPayload = payloads.get(eventTypes.indexOf("community-detected"));
        assertThat(communityPayload).containsKeys("communityId", "summary", "memberEntityIdentities");
    }

    @Test
    void typeFlipEmitsEntityRetypedProgressEventWithPreviousIdentity() {
        LlmPort flippingLlmPort = unit -> {
            String documentName = unit.documents().getFirst().filename();
            String type = documentName.equals("jaguar-animal.txt") ? "Animal" : "Organization";
            return new GraphExtraction(List.of(new io.graphrag.core.domain.Entity("Jaguar", type)), List.of());
        };
        CorpusController controller = new CorpusController(ingestCorpus, corpusRegistry, documentParsers, null,
                corpusProgressService, flippingLlmPort, graphStorePort, new RetrievalTraceStore(),
                constructVectorIndex, null, null);
        List<org.springframework.web.multipart.MultipartFile> files = List.of(
                new MockMultipartFile("files", "jaguar-animal.txt", "text/plain", "Jaguar".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("files", "jaguar-org-1.txt", "text/plain", "Jaguar".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("files", "jaguar-org-2.txt", "text/plain", "Jaguar".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<Map<String, Object>> response = controller.upload(files);
        String corpusId = String.valueOf(response.getBody().get("corpusId"));

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS)).emit(eq(corpusId), eq("entity-retyped"),
                org.mockito.ArgumentMatchers.argThat(payload ->
                        "jaguar::concept".equals(payload.get("previousIdentity"))
                                && "jaguar::organization".equals(payload.get("identity"))
                                && "Jaguar".equals(payload.get("name"))
                                && "Organization".equals(payload.get("type"))
                                && payload.containsKey("description")));
    }

    /**
     * Story 13.1: a document well over one Text Unit long, every paragraph
     * naming a proper noun, so each unit yields at least one Entity.
     */
    private static String multiUnitCorpusText() {
        StringBuilder text = new StringBuilder();
        int paragraph = 0;
        while (text.length() < 14_000) {
            text.append("Ada Lovelace met Charles Babbage over paragraph ").append(paragraph++)
                    .append(" of the engine notes. ")
                    .append("The notes kept going on about the analytical engine and its many gears. ".repeat(3))
                    .append("\n\n");
        }
        text.append("Grace Hopper closed the final passage.");
        return text.toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiUnitUploadEmitsOneTextUnitEventPerPassageBeforeThatPassagesEntities() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "engine-notes.txt", "text/plain", multiUnitCorpusText().getBytes(StandardCharsets.UTF_8));

        String responseBody = mockMvc.perform(multipart("/api/corpora").file(file))
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

        List<Integer> unitEventPositions = new java.util.ArrayList<>();
        for (int i = 0; i < eventTypes.size(); i++) {
            if ("text-unit-extracted".equals(eventTypes.get(i))) {
                unitEventPositions.add(i);
            }
        }
        assertThat(unitEventPositions).hasSizeGreaterThanOrEqualTo(2);
        int total = unitEventPositions.size();
        for (int n = 0; n < total; n++) {
            Map<String, Object> payload = payloads.get(unitEventPositions.get(n));
            assertThat(payload).containsEntry("index", n + 1)
                    .containsEntry("total", total)
                    .containsEntry("documentName", "engine-notes.txt");
            // Every unit names a proper noun, so its own entity-extracted
            // events directly follow it, before the next unit's progress event.
            assertThat(eventTypes.get(unitEventPositions.get(n) + 1)).isEqualTo("entity-extracted");
        }
        assertThat(unitEventPositions.getFirst()).isLessThan(eventTypes.indexOf("entity-extracted"));
        assertThat(eventTypes.indexOf("relationship-extracted")).isGreaterThan(unitEventPositions.getFirst());
        int lastUnit = unitEventPositions.getLast();
        assertThat(eventTypes.indexOf("community-detected")).isGreaterThan(lastUnit);
        assertThat(eventTypes.lastIndexOf("entity-extracted")).isLessThan(eventTypes.indexOf("community-detected"));
        assertThat(eventTypes.indexOf("ingestion-complete")).isGreaterThan(eventTypes.lastIndexOf("community-detected"));
        assertThat(graphStorePort.textUnits(corpusId)).hasSize(total);
        assertThat(graphStorePort.entities(corpusId))
                .anyMatch(entity -> entity.name().equals("Grace Hopper"));
        // Story 13.2: every extraction event carries a description, and every
        // stored Entity points back at persisted Text Units.
        for (int i = 0; i < eventTypes.size(); i++) {
            if (eventTypes.get(i).equals("entity-extracted") || eventTypes.get(i).equals("relationship-extracted")) {
                assertThat(payloads.get(i)).containsKey("description");
            }
        }
        java.util.Set<String> textUnitIds = graphStorePort.textUnits(corpusId).stream()
                .map(io.graphrag.core.domain.TextUnit::id)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(graphStorePort.entities(corpusId)).allSatisfy(entity ->
                assertThat(entity.sourceTextUnitIds()).isNotEmpty().allMatch(textUnitIds::contains));
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aFailingSecondPassageMarksTheCorpusFailedEmitsErrorAndLogsTheDocumentAndPassage(
            org.springframework.boot.test.system.CapturedOutput output) {
        java.util.List<Integer> calledOrdinals = new java.util.concurrent.CopyOnWriteArrayList<>();
        LlmPort failingOnSecondUnit = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus corpus) {
                return new GraphExtraction(List.of(), List.of());
            }

            @Override
            public GraphExtraction extract(io.graphrag.core.domain.TextUnit unit, List<String> entityTypes) {
                calledOrdinals.add(unit.ordinal());
                if (unit.ordinal() == 1) {
                    throw new IllegalStateException("simulated LLM outage");
                }
                return new GraphExtraction(List.of(new io.graphrag.core.domain.Entity("Ada Lovelace", "Person")),
                        List.of());
            }
        };
        CorpusController controller = new CorpusController(ingestCorpus, corpusRegistry, documentParsers, null,
                corpusProgressService, failingOnSecondUnit, graphStorePort, new RetrievalTraceStore(),
                constructVectorIndex, null, null);
        MockMultipartFile file = new MockMultipartFile(
                "files", "engine-notes.txt", "text/plain", multiUnitCorpusText().getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Map<String, Object>> response = controller.upload(List.<org.springframework.web.multipart.MultipartFile>of(file));
        String corpusId = String.valueOf(response.getBody().get("corpusId"));

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS)).emit(eq(corpusId), eq("error"), any());
        verify(corpusProgressService, never()).emit(eq(corpusId), eq("ingestion-complete"), any());
        assertThat(corpusRegistry.status(corpusId)).isEqualTo(Neo4jCorpusRegistry.CorpusWorkflowStatus.FAILED);
        assertThat(calledOrdinals).containsExactly(0, 1);
        assertThat(graphStorePort.textUnits(corpusId)).hasSize(1);
        assertThat(output.getAll()).contains("engine-notes.txt").contains("passage 2");
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
        Neo4jCorpusRegistry isolatedCorpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        Corpus corpus = new Corpus("building-corpus", List.of(new io.graphrag.core.domain.UploadedDocument("doc.txt", "content")));
        isolatedCorpusRegistry.put(corpus);
        CorpusController controller = new CorpusController(
                null, isolatedCorpusRegistry, List.of(), null, null, null, graphStorePort, new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "Is it ready?", "mode", "LOCAL"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void driftQueryWhileGraphIsStillBuildingReturnsTheExistingConflictResponse() {
        Neo4jCorpusRegistry isolatedCorpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        Corpus corpus = new Corpus("building-corpus", List.of(new io.graphrag.core.domain.UploadedDocument("doc.txt", "content")));
        isolatedCorpusRegistry.put(corpus);
        CorpusController controller = new CorpusController(
                null, isolatedCorpusRegistry, List.of(), null, null, null, graphStorePort, new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "Is it ready?", "mode", "DRIFT"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("error",
                "The graph is still building for this corpus. Wait for “Knowledge Graph — Ready”, then ask your question.");
    }

    @Test
    void queryingAfterFailedIngestionReturnsConflictWithFailureGuidance() {
        Neo4jCorpusRegistry isolatedCorpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        Corpus corpus = new Corpus("failed-corpus", List.of(new io.graphrag.core.domain.UploadedDocument("doc.txt", "content")));
        isolatedCorpusRegistry.put(corpus);
        isolatedCorpusRegistry.markFailed(corpus.id());
        CorpusController controller = new CorpusController(
                null, isolatedCorpusRegistry, List.of(), null, null, null, graphStorePort, new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "Is it ready?", "mode", "LOCAL"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void localSearchReadsOnlyGraphDataFromTheSelectedCorpus() {
        Neo4jCorpusRegistry isolatedCorpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        Corpus corpusA = new Corpus("corpus-a", List.of(new io.graphrag.core.domain.UploadedDocument("a.txt", "A")));
        Corpus corpusB = new Corpus("corpus-b", List.of(new io.graphrag.core.domain.UploadedDocument("b.txt", "B")));
        isolatedCorpusRegistry.put(corpusA);
        isolatedCorpusRegistry.put(corpusB);
        isolatedCorpusRegistry.markReady(corpusA.id());
        isolatedCorpusRegistry.markReady(corpusB.id());

        com.graphraglens.adapter.neo4j.InMemoryGraphStoreAdapter scopedGraphStore =
                new com.graphraglens.adapter.neo4j.InMemoryGraphStoreAdapter();
        scopedGraphStore.persistEntities(corpusA.id(), List.of(new io.graphrag.core.domain.Entity("Irene Adler", "Person")));
        scopedGraphStore.persistEntities(corpusB.id(), List.of(new io.graphrag.core.domain.Entity("Professor Moriarty", "Person")));

        CorpusController controller = new CorpusController(
                null, isolatedCorpusRegistry, List.of(), null, null, null, scopedGraphStore, new RetrievalTraceStore(), null, null, null);

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
        Neo4jCorpusRegistry isolatedCorpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        Corpus corpus = new Corpus("ready-corpus", List.of(new io.graphrag.core.domain.UploadedDocument("doc.txt", "content")));
        isolatedCorpusRegistry.put(corpus);
        isolatedCorpusRegistry.markReady(corpus.id());
        RetrievalTraceStore retrievalTraceStore = new RetrievalTraceStore();
        CorpusController controller = new CorpusController(
                null, isolatedCorpusRegistry, List.of(), null, null, stubLlmPort(), graphStorePort, retrievalTraceStore, null, null, null);

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
        Neo4jCorpusRegistry isolatedCorpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        Corpus corpus = new Corpus("ready-corpus", List.of(new io.graphrag.core.domain.UploadedDocument("doc.txt", "content")));
        isolatedCorpusRegistry.put(corpus);
        isolatedCorpusRegistry.markReady(corpus.id());
        CorpusController controller = new CorpusController(
                null, isolatedCorpusRegistry, List.of(), null, null, null, graphStorePort, new RetrievalTraceStore(), null, null, null);

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
        MockMultipartFile valid = new MockMultipartFile(
                "files", "ok.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile invalid = new MockMultipartFile(
                "files", "bad.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "nope".getBytes(StandardCharsets.UTF_8));

        // Validation fails while reading the files, before ingestCorpus.ingest()/corpusRegistry.put()
        // ever runs, so no corpusId is ever minted for this request -- there is nothing to look
        // up afterward to prove that. The 400 + file-naming error below is the observable contract,
        // plus a direct count of CorpusMeta nodes below to prove no corpus was registered.
        long corpusMetaCountBefore = countCorpusMetaNodes();

        mockMvc.perform(multipart("/api/corpora").file(valid).file(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bad.docx")));

        assertThat(countCorpusMetaNodes()).isEqualTo(corpusMetaCountBefore);
    }

    private static long countCorpusMetaNodes() {
        try (org.neo4j.driver.Session session = SharedNeo4jTestContainer.driver().session()) {
            return session.run("MATCH (c:CorpusMeta) RETURN count(c) AS count")
                    .single()
                    .get("count")
                    .asLong();
        }
    }

    @Test
    void listCorporaOrdersMostRecentlyActivatedFirst() throws Exception {
        MockMultipartFile fileA = new MockMultipartFile(
                "files", "history-a.txt", "text/plain", "a".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile fileB = new MockMultipartFile(
                "files", "history-b.txt", "text/plain", "b".getBytes(StandardCharsets.UTF_8));

        String bodyA = mockMvc.perform(multipart("/api/corpora").file(fileA))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String corpusIdA = JsonPath.read(bodyA, "$.corpusId");

        String bodyB = mockMvc.perform(multipart("/api/corpora").file(fileB))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String corpusIdB = JsonPath.read(bodyB, "$.corpusId");

        mockMvc.perform(post("/api/corpora/{corpusId}/activate", corpusIdA))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/corpora/{corpusId}/activate", corpusIdB))
                .andExpect(status().isOk());

        String listBody = mockMvc.perform(get("/api/corpora"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corpora[*].name").exists())
                .andExpect(jsonPath("$.corpora[*].status").exists())
                .andExpect(jsonPath("$.corpora[*].createdAt").exists())
                .andExpect(jsonPath("$.corpora[*].lastActivatedAt").exists())
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(listBody, "$.corpora[*].id");
        int indexOfA = ids.indexOf(corpusIdA);
        int indexOfB = ids.indexOf(corpusIdB);
        assertThat(indexOfA).isGreaterThanOrEqualTo(0);
        assertThat(indexOfB).isGreaterThanOrEqualTo(0);
        assertThat(indexOfB).isLessThan(indexOfA);

        List<String> names = JsonPath.read(listBody, "$.corpora[*].name");
        List<String> statuses = JsonPath.read(listBody, "$.corpora[*].status");
        assertThat(names.get(indexOfA)).isEqualTo("history-a.txt");
        assertThat(names.get(indexOfB)).isEqualTo("history-b.txt");
        assertThat(statuses.get(indexOfA)).isIn("BUILDING", "READY", "FAILED");
        assertThat(statuses.get(indexOfB)).isIn("BUILDING", "READY", "FAILED");
    }

    @Test
    void graphEndpointReturnsEntitiesRelationshipsAndCommunitiesWithMembersGroupedByCommunity() throws Exception {
        String responseBody = mockMvc.perform(multipart("/api/corpora/demo"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String corpusId = JsonPath.read(responseBody, "$.corpusId");

        verify(corpusProgressService, timeout(PIPELINE_TIMEOUT_MS))
                .emit(eq(corpusId), eq("ingestion-complete"), any());

        String graphBody = mockMvc.perform(get("/api/corpora/{corpusId}/graph", corpusId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corpusId").value(corpusId))
                .andExpect(jsonPath("$.entities").isArray())
                .andExpect(jsonPath("$.relationships").isArray())
                .andExpect(jsonPath("$.communities").isArray())
                .andExpect(jsonPath("$.entities[0]").value(org.hamcrest.Matchers.aMapWithSize(4)))
                .andExpect(jsonPath("$.entities[0]", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.hasKey("identity"),
                        org.hamcrest.Matchers.hasKey("name"),
                        org.hamcrest.Matchers.hasKey("type"),
                        org.hamcrest.Matchers.hasKey("description"))))
                .andExpect(jsonPath("$.relationships[0]", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.hasKey("sourceIdentity"),
                        org.hamcrest.Matchers.hasKey("source"),
                        org.hamcrest.Matchers.hasKey("targetIdentity"),
                        org.hamcrest.Matchers.hasKey("target"),
                        org.hamcrest.Matchers.hasKey("type"),
                        org.hamcrest.Matchers.hasKey("description"))))
                .andExpect(jsonPath("$.communities[0]", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.hasKey("communityId"),
                        org.hamcrest.Matchers.hasKey("summary"),
                        org.hamcrest.Matchers.hasKey("memberEntityIdentities"))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Map<String, Object>> communities = JsonPath.read(graphBody, "$.communities");
        List<io.graphrag.core.domain.CommunityMembership> memberships =
                new java.util.ArrayList<>(graphStorePort.communityMemberships(corpusId));
        for (Map<String, Object> community : communities) {
            String communityId = String.valueOf(community.get("communityId"));
            List<String> expectedMembers = memberships.stream()
                    .filter(membership -> membership.communityId().equals(communityId))
                    .map(io.graphrag.core.domain.CommunityMembership::entityIdentity)
                    .toList();
            @SuppressWarnings("unchecked")
            List<String> actualMembers = (List<String>) community.get("memberEntityIdentities");
            assertThat(actualMembers).containsExactlyInAnyOrderElementsOf(expectedMembers);
        }
    }

    @Test
    void graphEndpointForAnUnknownCorpusReturns404WithAPlainLanguageError() throws Exception {
        mockMvc.perform(get("/api/corpora/{corpusId}/graph", "nonexistent-corpus"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(
                        "No corpus was found for id nonexistent-corpus"));
    }

    @Test
    void activatingAnUnknownCorpusReturns404WithAPlainLanguageError() throws Exception {
        mockMvc.perform(post("/api/corpora/{corpusId}/activate", "nonexistent-corpus"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(
                        "No corpus was found for id nonexistent-corpus"));
    }

    @Test
    void activatingAnOfflineDemoCorpusStillReturns200() throws Exception {
        String responseBody = mockMvc.perform(post("/api/corpora/demo-offline"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String corpusId = JsonPath.read(responseBody, "$.corpusId");

        // The write itself is harmless -- an offline corpus's lastActivatedAt is
        // never read by list() while it stays offline (Neo4jCorpusRegistry#list()
        // filters it out) -- this only pins down that activation is not blocked
        // for an offline/demo corpus id.
        mockMvc.perform(post("/api/corpora/{corpusId}/activate", corpusId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(corpusId))
                .andExpect(jsonPath("$.activated").value(true));
    }

    @Test
    void offlineDemoCorpusIsExcludedFromTheCorporaHistoryList() throws Exception {
        String responseBody = mockMvc.perform(post("/api/corpora/demo-offline"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String corpusId = JsonPath.read(responseBody, "$.corpusId");

        String listBody = mockMvc.perform(get("/api/corpora"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(listBody, "$.corpora[*].id");
        assertThat(ids).doesNotContain(corpusId);
    }

    @Test
    void emptyRegistryReturnsAnEmptyCorporaArrayRatherThanAnError() {
        Neo4jCorpusRegistry emptyRegistry = org.mockito.Mockito.mock(Neo4jCorpusRegistry.class);
        org.mockito.Mockito.when(emptyRegistry.list()).thenReturn(List.of());
        CorpusController controller = new CorpusController(
                null, emptyRegistry, List.of(), null, null, null, graphStorePort, new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.corpora();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("corpora", List.of());
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
