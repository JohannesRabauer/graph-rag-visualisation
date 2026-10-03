package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.core.usecase.CompareAllModes.EvidenceRow;
import dev.rabauer.graphrag.core.usecase.CompareAllModes.Method;
import dev.rabauer.graphrag.core.usecase.CompareAllModes.MethodRun;
import dev.rabauer.graphrag.core.usecase.CompareAllModes.Outcome;
import dev.rabauer.graphrag.core.usecase.CompareAllModes.PassageKind;
import dev.rabauer.graphrag.core.usecase.CompareAllModes.Use;
import dev.rabauer.graphrag.core.usecase.SemanticTestFixtures.FakeGraphStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareAllModesTest {

    private static final String CORPUS = "corpus-a";

    /** Answers every context with {@code answer}; counts the synthesis calls. */
    private static class SynthesizingLlm implements LlmPort {
        private final Function<List<ContextItem>, SynthesizedAnswer> answer;
        final List<List<ContextItem>> contexts = new ArrayList<>();

        SynthesizingLlm(Function<List<ContextItem>, SynthesizedAnswer> answer) {
            this.answer = answer;
        }

        @Override
        public GraphExtraction extract(Corpus corpus) {
            return new GraphExtraction(List.of(), List.of());
        }

        @Override
        public boolean synthesizesAnswers() {
            return true;
        }

        @Override
        public SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context) {
            contexts.add(context);
            return answer.apply(context);
        }
    }

    private static SynthesizedAnswer citeFirstPassage(List<ContextItem> context) {
        int first = context.stream().filter(ContextItem::isTextUnit).findFirst().orElse(context.getFirst()).number();
        return new SynthesizedAnswer(false, "Irene Adler left London [" + first + "].");
    }

    private static FakeGraphStore graph() {
        return new FakeGraphStore()
                .entities(CORPUS, new Entity("Irene Adler", "Person", "An opera singer.", List.of("tu-a")))
                .textUnits(CORPUS, new TextUnit("tu-a", CORPUS, "story.txt", 0,
                        "Irene Adler   left\nLondon. She sang at the opera."))
                .communities(CORPUS, new Community("community-1", "Opera", "Irene Adler sang at the opera."))
                .memberships(CORPUS, "community-1", "irene adler::person");
    }

    private static EmbeddedChunk chunk(String id, String document, String text, float... embedding) {
        return new EmbeddedChunk(new Chunk(id, CORPUS, 0, text, document), embedding, new double[]{0, 0});
    }

    private static VectorStorePort store(EmbeddedChunk... chunks) {
        List<EmbeddedChunk> list = List.of(chunks);
        return new VectorStorePort() {
            @Override
            public void persistChunks(String corpusId, Collection<EmbeddedChunk> c) {
            }

            @Override
            public Collection<EmbeddedChunk> chunks(String corpusId) {
                return list;
            }
        };
    }

    /** ch-0 lies inside tu-a; ch-1..ch-6 are unrelated chunks, so ch-5 and ch-6 rank below the top-5 cut-off. */
    private static VectorStorePort vectors() {
        return store(
                chunk("ch-0", "story.txt", "Irene Adler left London.", 1f, 0f),
                chunk("ch-1", "other.txt", "Unrelated one.", 0.9f, 0.1f),
                chunk("ch-2", "other.txt", "Unrelated two.", 0.8f, 0.2f),
                chunk("ch-3", "other.txt", "Unrelated three.", 0.7f, 0.3f),
                chunk("ch-4", "other.txt", "Unrelated four.", 0.6f, 0.4f),
                chunk("ch-5", "other.txt", "Unrelated five.", 0.5f, 0.5f),
                chunk("ch-6", "other.txt", "Unrelated six.", 0.1f, 0.9f));
    }

    private static CompareAllModes compareAll(FakeGraphStore graph, VectorStorePort vectors, LlmPort llm) {
        EmbeddingPort query = text -> new float[]{1f, 0f};
        return new CompareAllModes(graph, null, llm, vectors, new AnswerVectorBaseline(query, vectors, llm));
    }

    private static MethodRun run(CompareAllModes.Comparison result, Method method) {
        return result.runs().stream().filter(run -> run.method() == method).findFirst().orElseThrow();
    }

    private static EvidenceRow row(CompareAllModes.Comparison result, String id) {
        return result.evidence().stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void runsAllFourMethodsInOrderWithTimingAndFootprint() {
        SynthesizingLlm llm = new SynthesizingLlm(CompareAllModesTest::citeFirstPassage);

        CompareAllModes.Comparison result = compareAll(graph(), vectors(), llm).compare("Who is Irene Adler?", CORPUS);

        assertEquals(List.of(Method.LOCAL, Method.GLOBAL, Method.DRIFT, Method.VECTOR),
                result.runs().stream().map(MethodRun::method).toList());
        for (MethodRun run : result.runs()) {
            assertEquals(Outcome.ANSWERED, run.outcome(), run.method() + " should answer");
            assertNotNull(run.answer());
            assertNull(run.failure());
            assertTrue(run.timing().llmCalls() >= 1, run.method() + " should count its LLM calls");
            assertEquals(run.timing().totalMs(),
                    run.timing().retrievalMs() + run.timing().embeddingMs() + run.timing().llmMs(),
                    1, run.method() + " stages should add up to the total");
        }
        MethodRun local = run(result, Method.LOCAL);
        assertEquals(1, local.footprint().get(RetrievalStep.Kind.ENTITY));
        assertEquals(1, local.footprint().get(RetrievalStep.Kind.TEXT_UNIT));
        MethodRun vector = run(result, Method.VECTOR);
        assertEquals(5, vector.footprint().get(RetrievalStep.Kind.VECTOR_CHUNK));
        assertEquals(1, vector.timing().embeddingCalls());
        assertEquals(7, vector.scoredChunkCount());
        assertEquals(7, vector.ranking().size());
        assertTrue(local.ranking().isEmpty());
    }

    @Test
    void theEvidenceTableJoinsAContainedChunkOntoItsTextUnitAndMarksHowFarEachPassageGot() {
        SynthesizingLlm llm = new SynthesizingLlm(CompareAllModesTest::citeFirstPassage);

        CompareAllModes.Comparison result = compareAll(graph(), vectors(), llm).compare("Who is Irene Adler?", CORPUS);

        // tu-a is cited by Local and (through ch-0) by Vector Search, so it comes first.
        EvidenceRow first = result.evidence().getFirst();
        assertEquals("tu-a", first.id());
        assertEquals(PassageKind.TEXT_UNIT, first.kind());
        assertEquals("story.txt", first.documentName());
        assertEquals(Use.CITED, first.marks().get(Method.LOCAL).use());
        assertEquals(List.of(1), first.marks().get(Method.LOCAL).citationNumbers());
        assertEquals(Use.CITED, first.marks().get(Method.VECTOR).use());
        assertEquals(1, first.marks().get(Method.VECTOR).rank());
        assertEquals(0, first.marks().get(Method.LOCAL).rank());

        // ch-1 was in Vector Search's context (rank 2) but the answer cites only [1].
        EvidenceRow inContext = row(result, "ch-1");
        assertEquals(PassageKind.CHUNK, inContext.kind());
        assertEquals(Use.IN_CONTEXT, inContext.marks().get(Method.VECTOR).use());
        assertEquals(2, inContext.marks().get(Method.VECTOR).rank());
        assertEquals(Use.NOT_RETRIEVED, inContext.marks().get(Method.LOCAL).use());

        // ch-5 and ch-6 ranked below the top-5 cut-off: the model never saw them.
        assertEquals(Use.RANKED_BELOW_CUTOFF, row(result, "ch-5").marks().get(Method.VECTOR).use());
        assertEquals(6, row(result, "ch-5").marks().get(Method.VECTOR).rank());
        assertEquals(Use.RANKED_BELOW_CUTOFF, row(result, "ch-6").marks().get(Method.VECTOR).use());

        // Every method has a mark on every row.
        for (EvidenceRow evidence : result.evidence()) {
            assertEquals(4, evidence.marks().size());
        }
    }

    @Test
    void aFailingMethodIsReportedWithoutStoppingTheOthers() {
        LlmPort failingDrift = new SynthesizingLlm(CompareAllModesTest::citeFirstPassage) {
            @Override
            public List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
                throw new IllegalStateException("drift planning failed");
            }
        };

        CompareAllModes.Comparison result = compareAll(graph(), vectors(), failingDrift)
                .compare("Who is Irene Adler?", CORPUS);

        MethodRun drift = run(result, Method.DRIFT);
        assertEquals(Outcome.FAILED, drift.outcome());
        assertInstanceOf(IllegalStateException.class, drift.failure());
        assertEquals("drift planning failed", drift.reason());
        assertTrue(drift.steps().isEmpty());
        assertEquals(Outcome.ANSWERED, run(result, Method.LOCAL).outcome());
        assertEquals(Outcome.ANSWERED, run(result, Method.VECTOR).outcome());
        assertTrue(result.summary().contains("DRIFT failed"), result.summary());
    }

    @Test
    void anOfflineRunKeepsTemplatedAnswersAndOnlyVectorEvidence() {
        LlmPort offline = corpus -> new GraphExtraction(List.of(), List.of());

        CompareAllModes.Comparison result = compareAll(graph(), vectors(), offline).compare("Who is Irene Adler?", CORPUS);

        assertEquals(Outcome.ANSWERED, run(result, Method.LOCAL).outcome());
        assertTrue(run(result, Method.LOCAL).citations().isEmpty());
        assertTrue(run(result, Method.VECTOR).answer().startsWith("Based on the retrieved text passages: "));
        // No graph method read a Text Unit, so every row is a vector chunk.
        assertTrue(result.evidence().stream().allMatch(row -> row.kind() == PassageKind.CHUNK));
        assertEquals(7, result.evidence().size());
        assertTrue(result.summary().contains("No answer cites a passage."), result.summary());
    }

    @Test
    void anEmptyVectorIndexIsANoAnswerAndTheSummaryNamesIt() {
        SynthesizingLlm llm = new SynthesizingLlm(CompareAllModesTest::citeFirstPassage);

        CompareAllModes.Comparison result = compareAll(graph(), store(), llm).compare("Who is Irene Adler?", CORPUS);

        MethodRun vector = run(result, Method.VECTOR);
        assertEquals(Outcome.NO_ANSWER, vector.outcome());
        assertNull(vector.answer());
        assertTrue(vector.reason().contains("vector index"));
        assertTrue(result.summary().startsWith("3 of 4 methods answered; no answer from Vector Search."),
                result.summary());
    }

    @Test
    void theSummaryNamesTheFastestAndSlowestRunAndTheSharedEvidence() {
        SynthesizingLlm llm = new SynthesizingLlm(CompareAllModesTest::citeFirstPassage);

        String summary = compareAll(graph(), vectors(), llm).compare("Who is Irene Adler?", CORPUS).summary();

        assertTrue(summary.startsWith("4 of 4 methods answered."), summary);
        assertTrue(summary.contains("Fastest: "), summary);
        assertTrue(summary.contains("; slowest: "), summary);
        assertTrue(summary.contains("by every method that answered"), summary);
    }
}
