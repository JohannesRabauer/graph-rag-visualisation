package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.usecase.SemanticTestFixtures.FakeEmbeddingPort;
import dev.rabauer.graphrag.core.usecase.SemanticTestFixtures.FakeGraphStore;
import dev.rabauer.graphrag.core.usecase.SemanticTestFixtures.RecordingLlmPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 15.3: with an answer-synthesizing LLM, Global Search answers from the
 * top Communities and their members' highest-weight Text Units, citing the
 * Text Units; otherwise it keeps the templated answer.
 */
class AnswerGlobalSearchSynthesisTest {

    private static final String CORPUS_ID = "corpus-a";
    private static final String QUESTION = "What happened in the Adler case?";

    private static TextUnit unit(String id) {
        return new TextUnit(id, CORPUS_ID, "story.txt", 0, "Passage " + id + ".");
    }

    /**
     * community-1 (Holmes, Irene): tu-3 = 1 + 5, tu-4 = 5, tu-2 = 2, tu-1 = 1; the Irene→Lestrade
     * edge (weight 9, tu-5) is not internal. community-2 (Watson, Holmes): tu-6 = 1 + 2, then
     * tu-4 (already added), tu-1, tu-2. community-3 (Lestrade): tu-5. community-4 does not match.
     */
    private static FakeGraphStore adlerStore() {
        return new FakeGraphStore()
                .communities(CORPUS_ID,
                        new Community("community-1", "Adler case", "Holmes and Irene Adler clash over the Adler case."),
                        new Community("community-2", "Watson records the Adler case."),
                        new Community("community-3", "Lestrade handles the case at Scotland Yard."),
                        new Community("community-4", "Violin practice on Baker Street."))
                .entities(CORPUS_ID,
                        new Entity("Holmes", "Person", "", List.of("tu-1", "tu-2")),
                        new Entity("Irene", "Person", "", List.of("tu-2", "tu-3")),
                        new Entity("Watson", "Person", "", List.of("tu-4", "tu-6")),
                        new Entity("Lestrade", "Person", "", List.of("tu-5")))
                .relationships(CORPUS_ID,
                        new Relationship("Holmes", "Person", "outwitted_by", "Irene", "Person", "",
                                List.of("tu-3", "tu-4"), 5),
                        new Relationship("Irene", "Person", "evaded", "Lestrade", "Person", "", List.of("tu-5"), 9),
                        new Relationship("Watson", "Person", "assists", "Holmes", "Person", "", List.of("tu-6"), 2))
                .memberships(CORPUS_ID, "community-1", "holmes::person", "irene::person")
                .memberships(CORPUS_ID, "community-2", "watson::person", "holmes::person")
                .memberships(CORPUS_ID, "community-3", "lestrade::person")
                .textUnits(CORPUS_ID, unit("tu-1"), unit("tu-2"), unit("tu-3"), unit("tu-4"), unit("tu-5"),
                        unit("tu-6"));
    }

    private static List<String> stepIds(List<RetrievalStep> steps) {
        return steps.stream().map(step -> step.kind() + ":" + step.identifier()).toList();
    }

    private static void assertCitationsAreTextUnitSteps(List<Citation> citations, List<RetrievalStep> steps) {
        Set<String> textUnitSteps = steps.stream()
                .filter(step -> step.kind() == RetrievalStep.Kind.TEXT_UNIT)
                .map(RetrievalStep::identifier)
                .collect(Collectors.toSet());
        for (Citation citation : citations) {
            assertTrue(textUnitSteps.contains(citation.textUnitId()), citation.textUnitId());
        }
    }

    @Test
    void recordsEachTopCommunityFollowedByItsTwoHighestWeightUnseenMemberTextUnits() {
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));

        GlobalSearchAnswer result = new AnswerGlobalSearch(adlerStore(), null, llm).answer(QUESTION, CORPUS_ID);

        assertEquals(List.of(
                "COMMUNITY:community-1", "TEXT_UNIT:tu-3", "TEXT_UNIT:tu-4",
                "COMMUNITY:community-2", "TEXT_UNIT:tu-6", "TEXT_UNIT:tu-1",
                "COMMUNITY:community-3", "TEXT_UNIT:tu-5"), stepIds(result.steps()));
        assertEquals("Holmes and Irene Adler clash over the Adler case.", result.steps().getFirst().label());
        assertEquals("Passage tu-3.", result.steps().get(1).label());

        List<ContextItem> context = llm.contexts.getFirst();
        assertEquals(result.steps().stream().map(RetrievalStep::kind).toList(),
                context.stream().map(ContextItem::kind).toList());
        assertEquals("Adler case: Holmes and Irene Adler clash over the Adler case.", context.getFirst().text());
        assertEquals("Watson records the Adler case.", context.get(3).text());
        assertEquals("tu-3", context.get(1).textUnitId());
        for (int i = 0; i < context.size(); i++) {
            assertEquals(i + 1, context.get(i).number());
        }
        assertEquals("Answer.", result.answer());
        assertFalse(result.noAnswer());
        assertTrue(result.citations().isEmpty());
    }

    @Test
    void resolvesCitationsToTextUnitStepsOfTheTrace() {
        RecordingLlmPort llm = new RecordingLlmPort(
                context -> new SynthesizedAnswer(false, "Holmes lost the photograph [2]. Watson wrote it up [5] [4]."));

        GlobalSearchAnswer result = new AnswerGlobalSearch(adlerStore(), null, llm).answer(QUESTION, CORPUS_ID);

        assertEquals("Holmes lost the photograph [1]. Watson wrote it up [2].", result.answer());
        assertEquals(List.of("tu-3", "tu-6"), result.citations().stream().map(Citation::textUnitId).toList());
        assertEquals(new Citation("tu-3", "story.txt", "Passage tu-3."), result.citations().getFirst());
        assertCitationsAreTextUnitSteps(result.citations(), result.steps());
        assertFalse(result.noAnswer());
        assertNull(result.reason());
    }

    @Test
    void capsMemberTextUnitsAtTwoPerCommunityHighestWeightFirst() {
        FakeGraphStore store = new FakeGraphStore()
                .communities(CORPUS_ID, new Community("community-1", "The Adler case."))
                .entities(CORPUS_ID,
                        new Entity("A", "Person", "", List.of("tu-1", "tu-2", "tu-3")),
                        new Entity("B", "Person", "", List.of("tu-4", "tu-5", "tu-6")))
                .relationships(CORPUS_ID,
                        new Relationship("A", "Person", "knows", "B", "Person", "", List.of("tu-5"), 4),
                        new Relationship("B", "Person", "likes", "A", "Person", "", List.of("tu-2"), 2))
                .memberships(CORPUS_ID, "community-1", "a::person", "b::person")
                .textUnits(CORPUS_ID, unit("tu-1"), unit("tu-2"), unit("tu-3"), unit("tu-4"), unit("tu-5"),
                        unit("tu-6"));
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));

        GlobalSearchAnswer result = new AnswerGlobalSearch(store, null, llm).answer(QUESTION, CORPUS_ID);

        assertEquals(List.of("COMMUNITY:community-1", "TEXT_UNIT:tu-5", "TEXT_UNIT:tu-2"), stepIds(result.steps()));
    }

    @Test
    void takesAtMostThreeCommunitiesByKeywordScoreWithIdTiebreak() {
        FakeGraphStore store = new FakeGraphStore().communities(CORPUS_ID,
                new Community("community-e", "Adler."),
                new Community("community-d", "The Adler case."),
                new Community("community-c", "The Adler case."),
                new Community("community-b", "Violin."),
                new Community("community-a", "The case."));
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));

        GlobalSearchAnswer result = new AnswerGlobalSearch(store, null, llm).answer(QUESTION, CORPUS_ID);

        assertEquals(List.of("COMMUNITY:community-c", "COMMUNITY:community-d", "COMMUNITY:community-a"),
                stepIds(result.steps()));
    }

    @Test
    void usesTheSemanticCommunitiesWhenTheEmbeddingPortIsSemantic() {
        FakeGraphStore store = adlerStore();
        store.persistCommunityEmbeddings(CORPUS_ID, Map.of(
                "community-1", new float[] {0, 1, 0, 0},
                "community-3", new float[] {1, 0, 0, 0},
                "community-4", new float[] {0.9f, 0.1f, 0, 0}));
        FakeEmbeddingPort embeddings = new FakeEmbeddingPort().map(QUESTION, 1, 0, 0, 0);
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));

        GlobalSearchAnswer result = new AnswerGlobalSearch(store, embeddings, llm).answer(QUESTION, CORPUS_ID);

        assertEquals(List.of(
                "COMMUNITY:community-3", "TEXT_UNIT:tu-5",
                "COMMUNITY:community-4",
                "COMMUNITY:community-1", "TEXT_UNIT:tu-3", "TEXT_UNIT:tu-4"), stepIds(result.steps()));
    }

    @Test
    void mapsNotInContextToTheNoAnswerShapeKeepingTheSteps() {
        for (SynthesizedAnswer reply : List.of(
                new SynthesizedAnswer(true, ""),
                new SynthesizedAnswer(false, "not_in_context."),
                new SynthesizedAnswer(false, "   "),
                new SynthesizedAnswer(false, "[1] [4]"))) {
            RecordingLlmPort llm = new RecordingLlmPort(context -> reply);

            GlobalSearchAnswer result = new AnswerGlobalSearch(adlerStore(), null, llm).answer(QUESTION, CORPUS_ID);

            assertTrue(result.noAnswer(), reply.toString());
            assertNull(result.answer());
            assertEquals(AnswerGlobalSearch.NOT_IN_CONTEXT_REASON, result.reason());
            assertEquals(8, result.steps().size());
            assertTrue(result.citations().isEmpty());
        }
    }

    @Test
    void keepsTheExistingNoMatchAnswerWithoutAnLlmCallWhenNoCommunityMatches() {
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer."));

        GlobalSearchAnswer synthesizing = new AnswerGlobalSearch(adlerStore(), null, llm)
                .answer("zzqqxx flimflam", CORPUS_ID);

        assertEquals(new AnswerGlobalSearch(adlerStore()).answer("zzqqxx flimflam", CORPUS_ID), synthesizing);
        assertTrue(llm.contexts.isEmpty());
        assertEquals(GlobalSearchAnswer.noCommunitiesYet(),
                new AnswerGlobalSearch(new FakeGraphStore(), null, llm).answer(QUESTION, CORPUS_ID));
    }

    @Test
    void offlineOutputEqualsTodaysConstructors() {
        RecordingLlmPort offline = new RecordingLlmPort(false, context -> new SynthesizedAnswer(false, "Unused."));

        for (String question : List.of(QUESTION, "zzqqxx flimflam")) {
            GlobalSearchAnswer expected = new AnswerGlobalSearch(adlerStore()).answer(question, CORPUS_ID);
            assertEquals(expected, new AnswerGlobalSearch(adlerStore(), null, offline).answer(question, CORPUS_ID));
            assertEquals(expected, new AnswerGlobalSearch(adlerStore(), null, null).answer(question, CORPUS_ID));
            assertTrue(expected.citations().isEmpty());
        }
        assertTrue(new AnswerGlobalSearch(adlerStore()).answer(QUESTION, CORPUS_ID).answer()
                .startsWith("Across the corpus, the strongest signal is that "));
        assertTrue(offline.contexts.isEmpty());
    }

    @Test
    void propagatesAnLlmFailureWithoutRetrying() {
        int[] calls = {0};
        RecordingLlmPort llm = new RecordingLlmPort(context -> {
            calls[0]++;
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class,
                () -> new AnswerGlobalSearch(adlerStore(), null, llm).answer(QUESTION, CORPUS_ID));
        assertEquals(1, calls[0]);
    }
}
