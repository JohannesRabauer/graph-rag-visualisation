package com.graphraglens.web;

import com.graphraglens.adapter.neo4j.InMemoryGraphStoreAdapter;
import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.GraphExtraction;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.domain.RetrievalTrace;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.port.LlmPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusControllerDriftSearchTest {

    @Test
    void driftSearchReturnsARealSynthesizedAnswerAndStoresItsTrace() {
        CorpusStore corpusStore = new CorpusStore();
        Corpus corpus = new Corpus("corpus-1", List.of(new UploadedDocument("doc.txt", "Some content.")));
        corpusStore.put(corpus);
        corpusStore.markReady(corpus.id());
        RetrievalTraceStore retrievalTraceStore = new RetrievalTraceStore();

        InMemoryGraphStoreAdapter graphStore = new InMemoryGraphStoreAdapter();
        graphStore.persistCommunities(corpus.id(), List.of(
                new Community("community-1", "This community centers on Sherlock Holmes and Irene Adler.")));
        graphStore.persistEntities(corpus.id(), List.of(
                new Entity("Sherlock Holmes", "Person"),
                new Entity("Irene Adler", "Person")));
        graphStore.persistRelationships(corpus.id(), List.of(
                new Relationship("Sherlock Holmes", "Person", "INVESTIGATES", "Irene Adler", "Person")));

        CorpusController controller = new CorpusController(
                null, corpusStore, List.of(), null, null, stubLlmPort(), graphStore, retrievalTraceStore, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "What connects Sherlock Holmes to Irene Adler?", "mode", "DRIFT"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("answerId", "traceId", "traceStepCount", "answer", "mode");
        assertThat(body).containsEntry("mode", "DRIFT");
        assertThat((String) body.get("answer")).contains("Sherlock Holmes investigates Irene Adler.");
        assertThat(body).doesNotContainKey("noAnswer");
        assertThat(body.get("traceStepCount")).isEqualTo(6);

        String traceId = (String) body.get("traceId");
        Optional<RetrievalTrace> storedTrace = retrievalTraceStore.get(traceId);
        assertThat(storedTrace).isPresent();
        assertThat(storedTrace.get().steps())
                .extracting(step -> step.kind().name())
                .containsExactly("COMMUNITY", "SUB_QUESTION_SPAWNED", "ENTITY", "RELATIONSHIP", "ENTITY",
                        "SYNTHESIS");
    }

    @Test
    void driftSearchReturnsTheDistinctNoAnswerShapeWhenCommunitiesExistButNoneMatch() {
        CorpusStore corpusStore = new CorpusStore();
        Corpus corpus = new Corpus("corpus-2", List.of(new UploadedDocument("doc.txt", "Some content.")));
        corpusStore.put(corpus);
        corpusStore.markReady(corpus.id());

        InMemoryGraphStoreAdapter graphStore = new InMemoryGraphStoreAdapter();
        graphStore.persistCommunities(corpus.id(), List.of(
                new Community("community-1", "This community centers on Baker Street and violin practice.")));

        RetrievalTraceStore retrievalTraceStore = new RetrievalTraceStore();
        CorpusController controller = new CorpusController(
                null, corpusStore, List.of(), null, null, stubLlmPort(), graphStore, retrievalTraceStore, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "zzqqxx nonsense gibberish flimflam", "mode", "DRIFT"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("noAnswer", true);
        assertThat((String) body.get("reason")).contains("no sub-questions could be generated");
        assertThat(body).doesNotContainKeys("answer", "mode");

        // Even a noAnswer outcome must still preserve the Community pass's
        // trace (Story 5.1 AC2) — the pass ran and found one community, so
        // the trace is one COMMUNITY step, never zero or missing.
        assertThat(body.get("traceStepCount")).isEqualTo(1);
        String traceId = (String) body.get("traceId");
        Optional<RetrievalTrace> storedTrace = retrievalTraceStore.get(traceId);
        assertThat(storedTrace).isPresent();
        assertThat(storedTrace.get().steps())
                .extracting(step -> step.kind().name())
                .containsExactly("COMMUNITY");
    }

    @Test
    void driftSearchUsesOnlyCommunitiesAndEntitiesForTheSelectedCorpus() {
        CorpusStore corpusStore = new CorpusStore();
        Corpus firstCorpus = new Corpus("corpus-1", List.of(new UploadedDocument("a.txt", "A")));
        Corpus secondCorpus = new Corpus("corpus-2", List.of(new UploadedDocument("b.txt", "B")));
        corpusStore.put(firstCorpus);
        corpusStore.put(secondCorpus);
        corpusStore.markReady(firstCorpus.id());
        corpusStore.markReady(secondCorpus.id());

        InMemoryGraphStoreAdapter graphStore = new InMemoryGraphStoreAdapter();
        graphStore.persistCommunities(firstCorpus.id(), List.of(
                new Community("community-1", "This community centers on Irene Adler and disguises.")));
        graphStore.persistEntities(firstCorpus.id(), List.of(new Entity("Irene Adler", "Person")));
        graphStore.persistCommunities(secondCorpus.id(), List.of(
                new Community("community-2", "This community centers on Professor Moriarty and networks.")));
        graphStore.persistEntities(secondCorpus.id(), List.of(new Entity("Professor Moriarty", "Person")));

        CorpusController controller = new CorpusController(
                null, corpusStore, List.of(), null, null, stubLlmPort(), graphStore, new RetrievalTraceStore(), null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                firstCorpus.id(), Map.of("question", "Tell me about Irene Adler", "mode", "DRIFT"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsKey("answer");
        assertThat((String) response.getBody().get("answer")).doesNotContain("Moriarty");
    }

    private static LlmPort stubLlmPort() {
        return corpus -> new GraphExtraction(List.of(), List.of());
    }
}
