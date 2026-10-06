package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SourceLocator;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import dev.rabauer.graphrag.core.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Attributes, locators and free-form types survive the use cases end to end. */
class AttributesThroughUseCasesTest {

    private static final String CORPUS = "repo";
    private static final SourceLocator SERVICE_AT = SourceLocator.of("src/OrderService.java", 10, 60);
    private static final SourceLocator CALL_AT = SourceLocator.of("src/OrderService.java", 42);
    private static final SourceLocator SNIPPET_AT = SourceLocator.of("src/OrderService.java", 40, 45);

    @Test
    void mergingKeepsTheFirstLocatorAndUnitesAttributes() {
        Entity first = new Entity("A", "Class", "First.", List.of("t1"), Map.of("kind", "class"), null);
        Entity second = new Entity("A", "Class", "Second.", List.of("t2"),
                Map.of("kind", "enum", "module", "core"), SERVICE_AT);

        Entity merged = GraphElementMerger.merge(first, second);

        assertEquals(Map.of("kind", "class", "module", "core"), merged.attributes());
        assertEquals(SERVICE_AT, merged.locator());
        Relationship summed = GraphElementMerger.mergeSummingWeights(
                new Relationship("A", "Class", "CALLS", "B", "Class", "", List.of(), 2, Map.of(), CALL_AT),
                new Relationship("A", "Class", "CALLS", "B", "Class", "", List.of(), 3, Map.of("x", "y"), null));
        assertEquals(5, summed.weight());
        assertEquals(CALL_AT, summed.locator());
        assertEquals(Map.of("x", "y"), summed.attributes());
    }

    @Test
    void extractionUsesTheConfiguredEntityTypesAndKeepsAttributes() {
        List<List<String>> promptedTypes = new ArrayList<>();
        LlmPort port = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus corpus) {
                return GraphExtraction.empty();
            }

            @Override
            public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
                promptedTypes.add(entityTypes);
                return new GraphExtraction(List.of(
                        new Entity("OrderService", "class", "", List.of(), Map.of("kind", "class"), SERVICE_AT),
                        new Entity("Shipping", "Department")), List.of());
            }
        };
        TestGraphStore store = new TestGraphStore();
        List<String> codeTypes = List.of("Class", "Interface", "Method", "Other");

        new ExtractEntitiesAndRelationships(port, store, codeTypes)
                .run(new Corpus(CORPUS, List.of(new UploadedDocument("notes.txt", "OrderService ships."))));

        assertEquals(List.of(codeTypes), promptedTypes);
        Entity service = store.stored(CORPUS, "orderservice::class");
        assertEquals("Class", service.type());
        assertEquals(Map.of("kind", "class"), service.attributes());
        assertEquals(SERVICE_AT, service.locator());
        assertEquals("Other", store.stored(CORPUS, "shipping::other").type());
    }

    @Test
    void theDefaultTypeListStillMapsOffListTypesToConcept() {
        assertEquals("Concept", EntityTypes.normalize("Department", null));
        assertEquals("Person", EntityTypes.normalize("person", EntityTypes.ALL));
        assertEquals("Method", EntityTypes.normalize("METHOD", List.of("Class", "Method", "Other")));
        assertEquals("Other", EntityTypes.normalize(" ", List.of("Class", "Method", "Other")));
    }

    @Test
    void localSearchStepsAndCitationsCarryTheLocators() {
        SemanticTestFixtures.FakeGraphStore store = new SemanticTestFixtures.FakeGraphStore()
                .entities(CORPUS,
                        new Entity("OrderService", "Class", "Places orders.", List.of("tu-1"),
                                Map.of("kind", "class"), SERVICE_AT),
                        new Entity("OrderRepository", "Interface"))
                .relationships(CORPUS, new Relationship("OrderService", "Class", "CALLS", "OrderRepository",
                        "Interface", "", List.of("tu-1"), 2, Map.of("callCount", "2"), CALL_AT))
                .textUnits(CORPUS, new TextUnit("tu-1", CORPUS, "OrderService.java", 0, "repository.save(order);",
                        Map.of("language", "java"), SNIPPET_AT));
        SemanticTestFixtures.RecordingLlmPort llm =
                new SemanticTestFixtures.RecordingLlmPort(context -> new SynthesizedAnswer(false, "It saves [3]."));

        LocalSearchAnswer answer = new AnswerLocalSearch(store, null, llm).answer("What does OrderService do?", CORPUS);

        List<RetrievalStep> steps = answer.steps();
        assertEquals(RetrievalStep.Kind.ENTITY, steps.get(0).kind());
        assertEquals(SERVICE_AT, steps.get(0).locator());
        assertEquals(Map.of("kind", "class"), steps.get(0).attributes());
        assertEquals(CALL_AT, steps.get(1).locator());
        assertEquals(SNIPPET_AT, steps.get(2).locator());
        Citation citation = answer.citations().getFirst();
        assertEquals(SNIPPET_AT, citation.locator());
        assertEquals(Map.of("language", "java"), citation.attributes());
    }
}
