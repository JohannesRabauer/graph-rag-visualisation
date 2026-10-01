package io.graphrag.core.usecase;

import io.graphrag.core.domain.Citation;
import io.graphrag.core.domain.ContextItem;
import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.domain.SynthesizedAnswer;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.port.LlmPort;
import io.graphrag.core.usecase.SemanticTestFixtures.FakeEmbeddingPort;
import io.graphrag.core.usecase.SemanticTestFixtures.FakeGraphStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 15.2: with an answer-synthesizing LLM, Local Search assembles a
 * bounded, cited context and resolves the model's citations; otherwise it
 * keeps the templated answer.
 */
class AnswerLocalSearchSynthesisTest {

    private static final String CORPUS_ID = "corpus-a";

    /** Records the context it is given and answers with {@code answer.apply(context)}. */
    private static final class RecordingLlmPort implements LlmPort {
        private final boolean synthesizes;
        private final Function<List<ContextItem>, SynthesizedAnswer> answer;
        final List<List<ContextItem>> contexts = new ArrayList<>();

        RecordingLlmPort(Function<List<ContextItem>, SynthesizedAnswer> answer) {
            this(true, answer);
        }

        RecordingLlmPort(boolean synthesizes, Function<List<ContextItem>, SynthesizedAnswer> answer) {
            this.synthesizes = synthesizes;
            this.answer = answer;
        }

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
            contexts.add(context);
            return answer.apply(context);
        }
    }

    private static TextUnit unit(String id, String text) {
        return new TextUnit(id, CORPUS_ID, "story.txt", 0, text);
    }

    /** Irene Adler (cited in tu-a) — admired by Holmes (tu-b, weight 3), outwitted Holmes (tu-a, tu-c). */
    private static FakeGraphStore sherlockStore() {
        return new FakeGraphStore()
                .entities(CORPUS_ID,
                        new Entity("Irene Adler", "Person", "An opera singer.", List.of("tu-a")),
                        new Entity("Sherlock Holmes", "Person", "A detective.", List.of("tu-b")))
                .relationships(CORPUS_ID,
                        new Relationship("Irene Adler", "Person", "outwitted", "Sherlock Holmes", "Person",
                                "She escaped him.", List.of("tu-a", "tu-c"), 1),
                        new Relationship("Sherlock Holmes", "Person", "admired", "Irene Adler", "Person",
                                "He kept her photograph.", List.of("tu-b"), 3))
                .textUnits(CORPUS_ID,
                        unit("tu-a", "Irene Adler   left\nLondon."),
                        unit("tu-b", "Holmes admired her."),
                        unit("tu-c", "The photograph was gone."));
    }

    private static List<RetrievalStep.Kind> kinds(List<RetrievalStep> steps) {
        return steps.stream().map(RetrievalStep::kind).toList();
    }

    @Test
    void assemblesSeedsThenRelationshipsByWeightThenRankedTextUnitsAsMatchingSteps() {
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));

        LocalSearchAnswer result = new AnswerLocalSearch(sherlockStore(), null, llm)
                .answer("Who is Irene Adler?", CORPUS_ID);

        List<ContextItem> context = llm.contexts.getFirst();
        assertEquals(List.of(RetrievalStep.Kind.ENTITY, RetrievalStep.Kind.RELATIONSHIP,
                RetrievalStep.Kind.RELATIONSHIP, RetrievalStep.Kind.TEXT_UNIT, RetrievalStep.Kind.TEXT_UNIT,
                RetrievalStep.Kind.TEXT_UNIT), context.stream().map(ContextItem::kind).toList());
        assertEquals("Irene Adler (Person): An opera singer.", context.get(0).text());
        // Highest weight first.
        assertTrue(context.get(1).text().startsWith("Sherlock Holmes -[admired]-> Irene Adler"));
        // tu-b: 3 (admired); tu-a: 1 (seed) + 1 (outwitted); tu-c: 1.
        assertEquals(List.of("tu-b", "tu-a", "tu-c"),
                context.subList(3, 6).stream().map(ContextItem::textUnitId).toList());
        for (int i = 0; i < context.size(); i++) {
            assertEquals(i + 1, context.get(i).number());
        }

        assertEquals(kinds(result.steps()), context.stream().map(ContextItem::kind).toList());
        assertEquals("irene adler::person", result.steps().get(0).identifier());
        assertEquals("sherlock holmes::person->admired->irene adler::person", result.steps().get(1).identifier());
        assertEquals("tu-a", result.steps().get(4).identifier());
        assertEquals("Irene Adler left London.", result.steps().get(4).label());
        assertEquals("Answer.", result.answer());
        assertFalse(result.noAnswer());
        assertTrue(result.citations().isEmpty());
    }

    @Test
    void capsRelationshipsAtTenByWeightAndTextUnitsAtFive() {
        FakeGraphStore store = new FakeGraphStore()
                .entities(CORPUS_ID, new Entity("Hub", "Concept", "", List.of()));
        for (int i = 0; i < 25; i++) {
            store.relationships(CORPUS_ID, new Relationship("Hub", "Concept", "links", "Leaf " + i, "Concept",
                    "", List.of("tu-" + (i % 12)), i + 1));
        }
        for (int i = 0; i < 12; i++) {
            store.textUnits(CORPUS_ID, unit("tu-" + i, "Passage " + i));
        }
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));

        LocalSearchAnswer result = new AnswerLocalSearch(store, null, llm).answer("Tell me about the hub", CORPUS_ID);

        List<RetrievalStep> relationships = result.steps().stream()
                .filter(step -> step.kind() == RetrievalStep.Kind.RELATIONSHIP).toList();
        assertEquals(10, relationships.size());
        assertEquals("hub::concept->links->leaf 24::concept", relationships.getFirst().identifier());
        assertEquals("hub::concept->links->leaf 15::concept", relationships.getLast().identifier());
        List<RetrievalStep> textUnits = result.steps().stream()
                .filter(step -> step.kind() == RetrievalStep.Kind.TEXT_UNIT).toList();
        assertEquals(5, textUnits.size());
        // Leaves 15..24 cite tu-3..tu-11 and tu-0 (24 % 12); highest weight first.
        assertEquals(List.of("tu-0", "tu-11", "tu-10", "tu-9", "tu-8"),
                textUnits.stream().map(RetrievalStep::identifier).toList());
        assertEquals(16, llm.contexts.getFirst().size());
    }

    @Test
    void usesUpToThreeSemanticSeeds() {
        FakeGraphStore store = sherlockStore();
        store.entities(CORPUS_ID, new Entity("Dr Watson", "Person"), new Entity("Mycroft", "Person"));
        store.persistEntityEmbeddings(CORPUS_ID, Map.of(
                "irene adler::person", new float[] {1, 0, 0, 0},
                "sherlock holmes::person", new float[] {1, 0.5f, 0, 0},
                "dr watson::person", new float[] {1, 1, 0, 0},
                "mycroft::person", new float[] {0, 1, 0, 0}));
        FakeEmbeddingPort embedding = new FakeEmbeddingPort().map("question", 1, 0, 0, 0);
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));

        LocalSearchAnswer result = new AnswerLocalSearch(store, embedding, llm).answer("question", CORPUS_ID);

        assertEquals(List.of("irene adler::person", "sherlock holmes::person", "dr watson::person"),
                result.steps().subList(0, 3).stream().map(RetrievalStep::identifier).toList());
        assertEquals(RetrievalStep.Kind.RELATIONSHIP, result.steps().get(3).kind());
    }

    @Test
    void resolvesCitationsSoEveryCitationIsATextUnitStepOfTheTrace() {
        // Items: 1 seed, 2-3 relationships, 4 = tu-b, 5 = tu-a, 6 = tu-c.
        RecordingLlmPort llm = new RecordingLlmPort(context ->
                new SynthesizedAnswer(false, "Holmes admired Adler [4][1]. She left [5, 9] and [4]."));

        LocalSearchAnswer result = new AnswerLocalSearch(sherlockStore(), null, llm)
                .answer("Who is Irene Adler?", CORPUS_ID);

        assertEquals("Holmes admired Adler [1]. She left [2] and [1].", result.answer());
        assertEquals(List.of(new Citation("tu-b", "story.txt", "Holmes admired her."),
                new Citation("tu-a", "story.txt", "Irene Adler left London.")), result.citations());
        List<String> textUnitSteps = result.steps().stream()
                .filter(step -> step.kind() == RetrievalStep.Kind.TEXT_UNIT)
                .map(RetrievalStep::identifier).toList();
        for (Citation citation : result.citations()) {
            assertTrue(textUnitSteps.contains(citation.textUnitId()));
        }
    }

    @Test
    void notInContextBecomesNoAnswerAndKeepsTheSteps() {
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(true, ""));

        LocalSearchAnswer result = new AnswerLocalSearch(sherlockStore(), null, llm)
                .answer("Who is Irene Adler?", CORPUS_ID);

        assertTrue(result.noAnswer());
        assertNull(result.answer());
        assertEquals(AnswerLocalSearch.NOT_IN_CONTEXT_REASON, result.reason());
        assertEquals(6, result.steps().size());
        assertTrue(result.citations().isEmpty());
    }

    @Test
    void aBlankOrLiteralNotInContextAnswerAlsoBecomesNoAnswer() {
        for (String text : List.of("   ", "NOT_IN_CONTEXT")) {
            RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, text));

            assertTrue(new AnswerLocalSearch(sherlockStore(), null, llm)
                    .answer("Who is Irene Adler?", CORPUS_ID).noAnswer());
        }
    }

    @Test
    void aSentinelWithQuotesPunctuationOrOtherCaseAlsoBecomesNoAnswer() {
        for (String text : List.of("NOT_IN_CONTEXT.", "\"NOT_IN_CONTEXT\"", "  not_in_context!\n", "'Not_In_Context.'")) {
            RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, text));

            LocalSearchAnswer result = new AnswerLocalSearch(sherlockStore(), null, llm)
                    .answer("Who is Irene Adler?", CORPUS_ID);

            assertTrue(result.noAnswer(), text);
            assertEquals(AnswerLocalSearch.NOT_IN_CONTEXT_REASON, result.reason());
        }
        assertFalse(SynthesizedAnswer.isNotInContextSentinel("NOT_IN_CONTEXT is not the answer."));
    }

    @Test
    void anAnswerThatIsOnlyANonTextUnitCitationBecomesNoAnswer() {
        // [1] is the seed Entity, so the resolved text is blank.
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "[1]"));

        LocalSearchAnswer result = new AnswerLocalSearch(sherlockStore(), null, llm)
                .answer("Who is Irene Adler?", CORPUS_ID);

        assertTrue(result.noAnswer());
        assertEquals(AnswerLocalSearch.NOT_IN_CONTEXT_REASON, result.reason());
        assertEquals(6, result.steps().size());
    }

    /** Delegates to {@link #sherlockStore()} but answers {@code textUnit} with {@code loader}. */
    private static io.graphrag.core.port.GraphStorePort storeWithTextUnitLoader(
            java.util.function.Function<String, java.util.Optional<TextUnit>> loader) {
        FakeGraphStore delegate = sherlockStore();
        return new io.graphrag.core.port.GraphStorePort() {
            @Override
            public void persistEntities(java.util.Collection<Entity> entities) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void persistRelationships(java.util.Collection<Relationship> relationships) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Collection<Entity> entities(String corpusId) {
                return delegate.entities(corpusId);
            }

            @Override
            public java.util.Collection<Relationship> relationships(String corpusId) {
                return delegate.relationships(corpusId);
            }

            @Override
            public java.util.Optional<TextUnit> textUnit(String corpusId, String textUnitId) {
                return loader.apply(textUnitId);
            }
        };
    }

    @Test
    void aTextUnitTheStoreReturnsAsEmptyOrNullIsSkippedAndTheOthersKept() {
        FakeGraphStore units = sherlockStore();
        for (String missing : List.of("tu-a", "tu-c")) {
            RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));
            io.graphrag.core.port.GraphStorePort store = storeWithTextUnitLoader(id ->
                    id.equals(missing) ? ("tu-a".equals(missing) ? java.util.Optional.empty() : null)
                            : units.textUnit(CORPUS_ID, id));

            LocalSearchAnswer result = new AnswerLocalSearch(store, null, llm).answer("Who is Irene Adler?", CORPUS_ID);

            List<String> expected = new ArrayList<>(List.of("tu-b", "tu-a", "tu-c"));
            expected.remove(missing);
            assertEquals(expected, result.steps().stream()
                    .filter(step -> step.kind() == RetrievalStep.Kind.TEXT_UNIT)
                    .map(RetrievalStep::identifier).toList());
            assertEquals(expected, llm.contexts.getFirst().stream()
                    .filter(ContextItem::isTextUnit).map(ContextItem::textUnitId).toList());
        }
    }

    @Test
    void aTextUnitStoreFailurePropagates() {
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));
        io.graphrag.core.port.GraphStorePort store = storeWithTextUnitLoader(id -> {
            throw new IllegalStateException("neo4j down");
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AnswerLocalSearch(store, null, llm).answer("Who is Irene Adler?", CORPUS_ID));
        assertEquals("neo4j down", failure.getMessage());
        assertTrue(llm.contexts.isEmpty());
    }

    @Test
    void textUnitsThatCannotBeLoadedAreSkipped() {
        FakeGraphStore store = new FakeGraphStore()
                .entities(CORPUS_ID, new Entity("Irene Adler", "Person", "", List.of("missing", "tu-a")))
                .textUnits(CORPUS_ID, unit("tu-a", "Irene Adler left London."));
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));

        LocalSearchAnswer result = new AnswerLocalSearch(store, null, llm).answer("Who is Irene Adler?", CORPUS_ID);

        assertEquals(List.of(RetrievalStep.Kind.ENTITY, RetrievalStep.Kind.TEXT_UNIT), kinds(result.steps()));
        assertEquals("tu-a", result.steps().get(1).identifier());
    }

    @Test
    void anLlmFailurePropagates() {
        RecordingLlmPort llm = new RecordingLlmPort(context -> {
            throw new IllegalStateException("model down");
        });

        assertThrows(IllegalStateException.class,
                () -> new AnswerLocalSearch(sherlockStore(), null, llm).answer("Who is Irene Adler?", CORPUS_ID));
    }

    @Test
    void noSeedStillGivesTheNoMatchAnswerWithoutCallingTheLlm() {
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));

        assertEquals(LocalSearchAnswer.noMatch(),
                new AnswerLocalSearch(sherlockStore(), null, llm).answer("zzqqxx gibberish", CORPUS_ID));
        assertTrue(llm.contexts.isEmpty());
    }

    @Test
    void aNonSynthesizingPortGivesExactlyTheKeywordOnlyAnswer() {
        RecordingLlmPort llm = new RecordingLlmPort(false, context -> new SynthesizedAnswer(false, "unused"));
        LlmPort lambdaPort = corpus -> new GraphExtraction(List.of(), List.of());

        for (String question : List.of("How did Irene Adler outwit Sherlock Holmes?", "Who is Irene Adler?",
                "zzqqxx gibberish")) {
            LocalSearchAnswer expected = new AnswerLocalSearch(sherlockStore()).answer(question, CORPUS_ID);
            assertEquals(expected, new AnswerLocalSearch(sherlockStore(), null, llm).answer(question, CORPUS_ID));
            assertEquals(expected, new AnswerLocalSearch(sherlockStore(), null, lambdaPort).answer(question, CORPUS_ID));
            assertEquals(expected, new AnswerLocalSearch(sherlockStore(), null, null).answer(question, CORPUS_ID));
            assertTrue(expected.citations().isEmpty());
            assertFalse(expected.noAnswer());
        }
        assertTrue(llm.contexts.isEmpty());
    }

    @Test
    void excerptCollapsesWhitespaceAndCutsAtTwoHundredCharacters() {
        assertEquals("a b c", AnswerLocalSearch.excerpt("  a \n\t b   c "));
        String longText = "x".repeat(250);
        assertEquals("x".repeat(200) + "…", AnswerLocalSearch.excerpt(longText));
        assertEquals("x".repeat(200), AnswerLocalSearch.excerpt("x".repeat(200)));
    }
}
