package com.graphraglens.web;

import com.graphraglens.adapter.neo4j.InMemoryGraphStoreAdapter;
import com.graphraglens.adapter.neo4j.Neo4jCorpusRegistry;
import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.RetrievalTrace;
import io.graphrag.core.domain.UploadedDocument;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plain, non-Spring unit test for the GLOBAL query branch's "no
 * Communities exist yet" wiring (AD-13's distinct noAnswer shape).
 *
 * <p>{@link CorpusControllerTest} is a shared {@code @SpringBootTest} whose
 * {@code GraphStorePort} bean is a global, unreset singleton across every
 * test method in that class (no per-Corpus isolation — an accepted,
 * pre-existing limitation), so it can never reliably observe an empty
 * {@code communities()} collection once any other test in the class has run.
 * This test sidesteps that entirely by constructing {@link CorpusController}
 * directly against a fresh, empty {@link InMemoryGraphStoreAdapter}.
 */
class CorpusControllerGlobalSearchTest {

    @Test
    void globalSearchReturnsTheDistinctNoAnswerShapeWhenNoCommunitiesArePersistedYet() {
        Neo4jCorpusRegistry corpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        Corpus corpus = new Corpus("corpus-1", List.of(new UploadedDocument("doc.txt", "Some content.")));
        corpusRegistry.put(corpus);
        corpusRegistry.markReady(corpus.id());
        RetrievalTraceStore retrievalTraceStore = new RetrievalTraceStore();

        CorpusController controller = new CorpusController(
                null, corpusRegistry, List.of(), null, null, null, new InMemoryGraphStoreAdapter(),
                retrievalTraceStore, null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                "corpus-1", Map.of("question", "What is this corpus about?", "mode", "GLOBAL"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("answerId", "traceId", "reason");
        assertThat(body.get("noAnswer")).isEqualTo(true);
        assertThat(body).doesNotContainKey("answer");
        assertThat(body).doesNotContainKey("mode");

        // The traceId returned above must already address a fetchable trace,
        // never a 404 — "found nothing" is still a captured, zero-step trace
        // (Story 5.1 AC2).
        String traceId = (String) body.get("traceId");
        Optional<RetrievalTrace> storedTrace = retrievalTraceStore.get(traceId);
        assertThat(storedTrace).isPresent();
        assertThat(storedTrace.get().steps()).isEmpty();

        ResponseEntity<Map<String, Object>> traceResponse = controller.trace(traceId);
        assertThat(traceResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(traceResponse.getBody()).containsEntry("traceId", traceId);
        assertThat((List<?>) traceResponse.getBody().get("steps")).isEmpty();
    }

    @Test
    void fetchingAnUnknownTraceIdThrowsIllegalArgumentExceptionThatMapsTo404() {
        CorpusController controller = new CorpusController(
                null, new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver()), List.of(), null, null, null,
                new InMemoryGraphStoreAdapter(), new RetrievalTraceStore(), null, null, null);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                        () -> controller.trace("unknown-trace-id")))
                .hasMessageContaining("unknown-trace-id");
    }

    @Test
    void globalSearchUsesOnlyCommunitiesForTheSelectedCorpus() {
        Neo4jCorpusRegistry corpusRegistry = new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
        Corpus firstCorpus = new Corpus("corpus-1", List.of(new UploadedDocument("a.txt", "A")));
        Corpus secondCorpus = new Corpus("corpus-2", List.of(new UploadedDocument("b.txt", "B")));
        corpusRegistry.put(firstCorpus);
        corpusRegistry.put(secondCorpus);
        corpusRegistry.markReady(firstCorpus.id());
        corpusRegistry.markReady(secondCorpus.id());

        InMemoryGraphStoreAdapter graphStore = new InMemoryGraphStoreAdapter();
        graphStore.persistCommunities(firstCorpus.id(), List.of(
                new io.graphrag.core.domain.Community("community-1",
                        "This community centers on Irene Adler and disguises.")));
        graphStore.persistCommunities(secondCorpus.id(), List.of(
                new io.graphrag.core.domain.Community("community-2",
                        "This community centers on Professor Moriarty and networks.")));

        CorpusController controller = new CorpusController(
                null, corpusRegistry, List.of(), null, null, null, graphStore, new RetrievalTraceStore(), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.query(
                firstCorpus.id(), Map.of("question", "Tell me about Moriarty", "mode", "GLOBAL"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsKey("answer");
        assertThat((String) response.getBody().get("answer")).doesNotContain("Moriarty");
    }
}
