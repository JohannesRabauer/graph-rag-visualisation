package io.graphrag.core.usecase;

import io.graphrag.core.domain.Citation;
import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.ContextItem;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.domain.SynthesizedAnswer;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.usecase.SemanticTestFixtures.FakeGraphStore;
import io.graphrag.core.usecase.SemanticTestFixtures.RecordingLlmPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 15.3: with an answer-synthesizing LLM, each DRIFT branch gathers a
 * Local-style context and one synthesis runs over their union; otherwise
 * DRIFT keeps the templated answer.
 */
class AnswerDriftSearchSynthesisTest {

    private static final String CORPUS_ID = "corpus-a";
    private static final String QUESTION = "How are Irene Adler and Sherlock Holmes connected?";

    private static TextUnit unit(String id, String text) {
        return new TextUnit(id, CORPUS_ID, "story.txt", 0, text);
    }

    private static FakeGraphStore sherlockStore() {
        return new FakeGraphStore()
                .communities(CORPUS_ID,
                        new Community("community-1", "Rivals", "Irene Adler and Sherlock Holmes clash."),
                        new Community("community-2", "Violin practice on Baker Street."))
                .entities(CORPUS_ID,
                        new Entity("Irene Adler", "Person", "An opera singer.", List.of("tu-a")),
                        new Entity("Sherlock Holmes", "Person", "A detective.", List.of("tu-b")))
                .relationships(CORPUS_ID,
                        new Relationship("Irene Adler", "Person", "outwitted", "Sherlock Holmes", "Person",
                                "She escaped him.", List.of("tu-a", "tu-c"), 1),
                        new Relationship("Sherlock Holmes", "Person", "admired", "Irene Adler", "Person",
                                "He kept her photograph.", List.of("tu-b"), 3))
                .textUnits(CORPUS_ID,
                        unit("tu-a", "Irene Adler left London."),
                        unit("tu-b", "Holmes admired her."),
                        unit("tu-c", "The photograph was gone."));
    }

    private static RecordingLlmPort twoBranchLlm(String answer) {
        return new RecordingLlmPort(context -> new SynthesizedAnswer(false, answer))
                .subQuestions("Who is Irene Adler?", "Who is Sherlock Holmes?");
    }

    private static List<String> stepIds(List<RetrievalStep> steps) {
        return steps.stream().map(step -> step.kind() + ":" + step.identifier()).toList();
    }

    private static final String ADMIRED = "sherlock holmes::person->admired->irene adler::person";
    private static final String OUTWITTED = "irene adler::person->outwitted->sherlock holmes::person";

    @Test
    void recordsEachBranchsContextUnderItsSpawnAndOneSynthesisStepLast() {
        RecordingLlmPort llm = twoBranchLlm("Answer.");

        DriftSearchAnswer result = new AnswerDriftSearch(sherlockStore(), llm).answer(QUESTION, CORPUS_ID);

        assertEquals(List.of(
                "COMMUNITY:community-1", "COMMUNITY:community-2",
                "SUB_QUESTION_SPAWNED:community-1",
                "ENTITY:irene adler::person", "RELATIONSHIP:" + ADMIRED, "RELATIONSHIP:" + OUTWITTED,
                "TEXT_UNIT:tu-b", "TEXT_UNIT:tu-a", "TEXT_UNIT:tu-c",
                "SUB_QUESTION_SPAWNED:",
                "ENTITY:sherlock holmes::person", "RELATIONSHIP:" + ADMIRED, "RELATIONSHIP:" + OUTWITTED,
                "TEXT_UNIT:tu-b", "TEXT_UNIT:tu-a", "TEXT_UNIT:tu-c",
                "SYNTHESIS:community-1"), stepIds(result.steps()));
        assertEquals("Who is Sherlock Holmes?", result.steps().get(9).label());
        assertEquals("Answer.", result.steps().getLast().label());
        assertEquals(1, result.steps().stream().filter(step -> step.kind() == RetrievalStep.Kind.SYNTHESIS).count());
        assertEquals(1, llm.contexts.size());
        assertEquals("Answer.", result.answer());
        assertFalse(result.noAnswer());
        assertTrue(result.citations().isEmpty());
    }

    @Test
    void synthesizesOnceOverTheCandidateSummariesAndTheDeDuplicatedBranchUnion() {
        RecordingLlmPort llm = twoBranchLlm("She escaped [6]; he admired her [5].");

        DriftSearchAnswer result = new AnswerDriftSearch(sherlockStore(), llm).answer(QUESTION, CORPUS_ID);

        List<ContextItem> context = llm.contexts.getFirst();
        assertEquals(List.of(
                RetrievalStep.Kind.COMMUNITY,
                RetrievalStep.Kind.ENTITY, RetrievalStep.Kind.RELATIONSHIP, RetrievalStep.Kind.RELATIONSHIP,
                RetrievalStep.Kind.TEXT_UNIT, RetrievalStep.Kind.TEXT_UNIT, RetrievalStep.Kind.TEXT_UNIT,
                RetrievalStep.Kind.ENTITY), context.stream().map(ContextItem::kind).toList());
        assertEquals("Rivals: Irene Adler and Sherlock Holmes clash.", context.getFirst().text());
        assertEquals("Sherlock Holmes (Person): A detective.", context.getLast().text());
        assertEquals(List.of("tu-b", "tu-a", "tu-c"), context.stream()
                .filter(ContextItem::isTextUnit).map(ContextItem::textUnitId).toList());
        for (int i = 0; i < context.size(); i++) {
            assertEquals(i + 1, context.get(i).number());
        }

        assertEquals("She escaped [1]; he admired her [2].", result.answer());
        assertEquals(List.of(new Citation("tu-a", "story.txt", "Irene Adler left London."),
                new Citation("tu-b", "story.txt", "Holmes admired her.")), result.citations());
        assertEquals(result.answer(), result.steps().getLast().label());

        Set<String> textUnitSteps = result.steps().stream()
                .filter(step -> step.kind() == RetrievalStep.Kind.TEXT_UNIT)
                .map(RetrievalStep::identifier)
                .collect(Collectors.toSet());
        for (Citation citation : result.citations()) {
            assertTrue(textUnitSteps.contains(citation.textUnitId()));
        }
    }

    @Test
    void capsTiedKeywordCandidatesAtThreeBeforeBranching() {
        FakeGraphStore store = sherlockStore();
        for (String id : List.of("community-7", "community-3", "community-5", "community-4", "community-6")) {
            store.communities(CORPUS_ID, new Community(id, "Irene Adler and Sherlock Holmes clash."));
        }
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));

        DriftSearchAnswer result = new AnswerDriftSearch(store, llm).answer(QUESTION, CORPUS_ID);

        // Six summaries tie at the top score; today's id order, then the first three.
        assertEquals(List.of("community-1", "community-3", "community-4"), result.steps().stream()
                .filter(step -> step.kind() == RetrievalStep.Kind.SUB_QUESTION_SPAWNED)
                .map(RetrievalStep::identifier).toList());
        assertEquals(3, llm.contexts.getFirst().stream()
                .filter(item -> item.kind() == RetrievalStep.Kind.COMMUNITY).count());
        assertEquals("SYNTHESIS:community-1", stepIds(result.steps()).getLast());
    }

    @Test
    void seedsEachBranchByMeaningWithASemanticEmbeddingPort() {
        FakeGraphStore store = sherlockStore();
        store.persistEntityEmbeddings(CORPUS_ID, java.util.Map.of(
                "sherlock holmes::person", new float[] {1, 0, 0, 0},
                "irene adler::person", new float[] {0, 1, 0, 0}));
        String subQuestion = "Tell me about the famous detective.";
        io.graphrag.core.usecase.SemanticTestFixtures.FakeEmbeddingPort embeddings =
                new io.graphrag.core.usecase.SemanticTestFixtures.FakeEmbeddingPort().map(subQuestion, 1, 0, 0, 0);
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."))
                .subQuestions(subQuestion);

        DriftSearchAnswer result = new AnswerDriftSearch(store, llm, embeddings).answer(QUESTION, CORPUS_ID);

        List<String> ids = stepIds(result.steps());
        int spawn = ids.indexOf("SUB_QUESTION_SPAWNED:community-1");
        assertEquals("ENTITY:sherlock holmes::person", ids.get(spawn + 1));
        assertTrue(llm.contexts.getFirst().stream()
                .anyMatch(item -> item.text().equals("Sherlock Holmes (Person): A detective.")));
        assertEquals("SYNTHESIS:community-1", ids.getLast());
    }

    @Test
    void keepsTheNoLocalMatchOutcomeWithoutAnLlmCallWhenNoBranchFindsContext() {
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Unused."))
                .subQuestions("zzqqxx flimflam", "gibberish nonsense");

        DriftSearchAnswer result = new AnswerDriftSearch(sherlockStore(), llm).answer(QUESTION, CORPUS_ID);

        assertTrue(llm.contexts.isEmpty());
        assertFalse(result.noAnswer());
        assertEquals("DRIFT matched a relevant Community, but its spawned sub-question did not find a "
                + "graph-grounded local match yet.", result.answer());
        assertEquals(List.of("COMMUNITY:community-1", "COMMUNITY:community-2", "SUB_QUESTION_SPAWNED:community-1",
                "SUB_QUESTION_SPAWNED:", "SYNTHESIS:"), stepIds(result.steps()));
        assertTrue(result.citations().isEmpty());
    }

    @Test
    void mapsNotInContextToTheNoAnswerShapeKeepingTheBranchSteps() {
        for (SynthesizedAnswer reply : List.of(
                new SynthesizedAnswer(true, ""),
                new SynthesizedAnswer(false, "\"NOT_IN_CONTEXT\""),
                new SynthesizedAnswer(false, ""),
                new SynthesizedAnswer(false, "[1] [2]"))) {
            RecordingLlmPort llm = new RecordingLlmPort(context -> reply)
                    .subQuestions("Who is Irene Adler?", "Who is Sherlock Holmes?");

            DriftSearchAnswer result = new AnswerDriftSearch(sherlockStore(), llm).answer(QUESTION, CORPUS_ID);

            assertTrue(result.noAnswer(), reply.toString());
            assertNull(result.answer());
            assertEquals(AnswerDriftSearch.NOT_IN_CONTEXT_REASON, result.reason());
            assertEquals(16, result.steps().size());
            assertTrue(result.steps().stream().noneMatch(step -> step.kind() == RetrievalStep.Kind.SYNTHESIS));
            assertTrue(result.citations().isEmpty());
        }
    }

    @Test
    void offlineOutputEqualsTodaysConstructors() {
        RecordingLlmPort offline = new RecordingLlmPort(false, context -> new SynthesizedAnswer(false, "Unused."));

        for (String question : List.of(QUESTION, "zzqqxx flimflam")) {
            DriftSearchAnswer expected = new AnswerDriftSearch(sherlockStore(), null).answer(question, CORPUS_ID);
            assertEquals(expected, new AnswerDriftSearch(sherlockStore(), offline).answer(question, CORPUS_ID));
            assertEquals(expected, new AnswerDriftSearch(sherlockStore(), offline, null).answer(question, CORPUS_ID));
            assertTrue(expected.citations().isEmpty());
        }
        assertTrue(new AnswerDriftSearch(sherlockStore(), offline).answer(QUESTION, CORPUS_ID).answer()
                .startsWith("DRIFT matched a relevant Community and then grounded the answer locally: "));
        assertTrue(offline.contexts.isEmpty());
    }

    @Test
    void propagatesAnLlmFailureWithoutRetrying() {
        int[] calls = {0};
        RecordingLlmPort llm = new RecordingLlmPort(context -> {
            calls[0]++;
            throw new IllegalStateException("boom");
        }).subQuestions("Who is Irene Adler?");

        assertThrows(IllegalStateException.class,
                () -> new AnswerDriftSearch(sherlockStore(), llm).answer(QUESTION, CORPUS_ID));
        assertEquals(1, calls[0]);
    }
}
