package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import dev.rabauer.graphrag.core.llm.LlmReplyException;
import dev.rabauer.graphrag.core.llm.PromptedLlmPort;
import dev.rabauer.graphrag.core.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A small model failing one item fails that item only, visibly and counted. */
class PerItemFailureIsolationTest {

    /** A "small model" that answers every Community summary except the one about T2. */
    private static final class FlakySmallModel extends PromptedLlmPort {
        @Override
        protected String complete(CompletionRequest request) {
            String prompt = request.messages().getFirst().text();
            if (prompt.contains("T2a")) {
                return "I am not sure what these have in common.";
            }
            return "Here you go: {\"title\": \"Triangle\", \"summary\": \"Three connected classes.\",}";
        }
    }

    private static TestGraphStore triangles() {
        TestGraphStore store = new TestGraphStore();
        for (int i = 1; i <= 3; i++) {
            String a = "T" + i + "a";
            String b = "T" + i + "b";
            String c = "T" + i + "c";
            store.persistEntities("c", List.of(new Entity(a, "Class"), new Entity(b, "Class"), new Entity(c, "Class")));
            store.persistRelationships("c", List.of(new Relationship(a, "Class", "CALLS", b, "Class"),
                    new Relationship(b, "Class", "CALLS", c, "Class"), new Relationship(c, "Class", "CALLS", a, "Class")));
        }
        return store;
    }

    @Test
    void anUnusableSummaryFailsOnlyItsCommunity() {
        TestGraphStore store = triangles();

        CommunityDetectionResult result = new DetectCommunities(store, new FlakySmallModel(),
                DetectCommunities.Options.defaults().withFailurePolicy(FailurePolicy.ISOLATE_ITEM)).run("c");

        assertEquals(CommunityDetectionResult.Status.PARTIAL, result.status());
        assertEquals(2, result.count(CommunityDetectionResult.SummaryStatus.GENERATED));
        assertEquals(1, result.count(CommunityDetectionResult.SummaryStatus.FAILED));
        assertTrue(result.summaries().get(1).error().contains("could not be used after 2 attempt(s)"));
        assertEquals("Triangle", result.communities().getFirst().title());
        assertEquals("This community centers on T2a, T2b, T2c.", result.communities().get(1).summary());
        assertEquals(3, store.communities("c").size());
    }

    @Test
    void withoutIsolationTheSameFailureStopsTheRun() {
        assertThrows(LlmReplyException.class, () -> new DetectCommunities(triangles(), new FlakySmallModel()).detect("c"));
    }

    @Test
    void anExtractionFailureIsIsolatedPerTextUnitAndReported() {
        LlmPort flaky = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus corpus) {
                return GraphExtraction.empty();
            }

            @Override
            public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
                if (unit.documentName().equals("b.txt")) {
                    throw new LlmReplyException(PromptedLlmPort.Purpose.EXTRACTION, 2, "???", "unusable reply", null);
                }
                return new GraphExtraction(List.of(new Entity("Ada", "Person")), List.of());
            }
        };
        Corpus corpus = new Corpus("c", List.of(new UploadedDocument("a.txt", "Ada."), new UploadedDocument("b.txt", "Bob."),
                new UploadedDocument("c.txt", "Cy.")));
        TestGraphStore store = new TestGraphStore();

        ExtractionReport report = new ExtractEntitiesAndRelationships(flaky, store, null, FailurePolicy.ISOLATE_ITEM)
                .runWithReport(corpus, null, null, null, null);

        assertEquals(3, report.textUnits());
        assertEquals(1, report.failures().size());
        ExtractionReport.UnitFailure failure = report.failures().getFirst();
        assertEquals("b.txt", failure.documentName());
        assertEquals(1, failure.passage());
        assertEquals("unusable reply", failure.message());
        assertEquals(3, store.textUnits("c").size());
        assertEquals(1, store.entities("c").size());
        assertThrows(IllegalStateException.class,
                () -> new ExtractEntitiesAndRelationships(flaky, new TestGraphStore()).run(corpus));
    }
}
