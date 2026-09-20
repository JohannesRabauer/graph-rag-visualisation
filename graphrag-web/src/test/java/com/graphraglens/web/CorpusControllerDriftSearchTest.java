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
                null, corpusStore, List.of(), null, null, stubLlmPort(), graphStore, retrievalTraceStore);

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "What connects Sherlock Holmes to Irene Adler?", "mode", "DRIFT"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("answerId", "traceId", "traceStepCount", "answer", "mode");
        assertThat(body).containsEntry("mode", "DRIFT");
        assertThat((String) body.get("answer")).contains("Sherlock Holmes investigates Irene Adler.");
        assertThat(body).doesNotContainKey("noAnswer");
        assertThat(body.get("traceStepCount")).isEqualTo(4);

        String traceId = (String) body.get("traceId");
        Optional<RetrievalTrace> storedTrace = retrievalTraceStore.get(traceId);
        assertThat(storedTrace).isPresent();
        assertThat(storedTrace.get().steps())
                .extracting(step -> step.kind().name())
                .containsExactly("COMMUNITY", "ENTITY", "RELATIONSHIP", "ENTITY");
    }

    @Test
    void driftSearchReturnsAnOrdinaryAnswerWhenCommunitiesExistButNoneMatch() {
        CorpusStore corpusStore = new CorpusStore();
        Corpus corpus = new Corpus("corpus-2", List.of(new UploadedDocument("doc.txt", "Some content.")));
        corpusStore.put(corpus);
        corpusStore.markReady(corpus.id());

        InMemoryGraphStoreAdapter graphStore = new InMemoryGraphStoreAdapter();
        graphStore.persistCommunities(corpus.id(), List.of(
                new Community("community-1", "This community centers on Baker Street and violin practice.")));

        CorpusController controller = new CorpusController(
                null, corpusStore, List.of(), null, null, stubLlmPort(), graphStore, new RetrievalTraceStore());

        ResponseEntity<Map<String, Object>> response = controller.query(
                corpus.id(), Map.of("question", "zzqqxx nonsense gibberish flimflam", "mode", "DRIFT"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("mode", "DRIFT");
        assertThat((String) response.getBody().get("answer")).contains("none of them clearly matched");
        assertThat(response.getBody()).doesNotContainKey("noAnswer");
    }

    private static LlmPort stubLlmPort() {
        return corpus -> new GraphExtraction(List.of(), List.of());
    }
}
