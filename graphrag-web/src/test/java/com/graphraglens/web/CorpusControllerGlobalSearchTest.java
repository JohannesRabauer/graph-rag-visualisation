package com.graphraglens.web;

import com.graphraglens.adapter.neo4j.InMemoryGraphStoreAdapter;
import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.UploadedDocument;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

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
        CorpusStore corpusStore = new CorpusStore();
        Corpus corpus = new Corpus("corpus-1", List.of(new UploadedDocument("doc.txt", "Some content.")));
        corpusStore.put(corpus);

        CorpusController controller = new CorpusController(
                null, corpusStore, List.of(), null, null, null, new InMemoryGraphStoreAdapter());

        ResponseEntity<Map<String, Object>> response = controller.query(
                "corpus-1", Map.of("question", "What is this corpus about?", "mode", "GLOBAL"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("answerId", "traceId", "reason");
        assertThat(body.get("noAnswer")).isEqualTo(true);
        assertThat(body).doesNotContainKey("answer");
        assertThat(body).doesNotContainKey("mode");
    }
}
