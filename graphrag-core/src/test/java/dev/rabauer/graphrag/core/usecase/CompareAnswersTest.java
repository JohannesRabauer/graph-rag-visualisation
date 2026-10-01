package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.ComparisonFacts;
import dev.rabauer.graphrag.core.domain.ComparisonVerdict;
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
import dev.rabauer.graphrag.core.usecase.SemanticTestFixtures.FakeGraphStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareAnswersTest {

    private static final String CORPUS = "corpus-a";

    /** Cites the first Source passage of every context; the verdict is configurable. */
    private static final class ComparingLlmPort implements LlmPort {
        private final boolean synthesizes;
        private final RuntimeException verdictFailure;
        private final ComparisonVerdict verdict;
        final List<ComparisonFacts> verdictFacts = new ArrayList<>();
        final List<String> verdictAnswers = new ArrayList<>();

        ComparingLlmPort(boolean synthesizes, ComparisonVerdict verdict, RuntimeException verdictFailure) {
            this.synthesizes = synthesizes;
            this.verdict = verdict;
            this.verdictFailure = verdictFailure;
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
            int first = context.stream().filter(ContextItem::isTextUnit).findFirst().orElseThrow().number();
            return new SynthesizedAnswer(false, "Irene Adler left London [" + first + "].");
        }

        @Override
        public ComparisonVerdict compareAnswers(String question, String graphAnswer, String vectorAnswer,
                                                ComparisonFacts facts) {
            verdictFacts.add(facts);
            verdictAnswers.add(graphAnswer);
            verdictAnswers.add(vectorAnswer);
            if (verdictFailure != null) {
                throw verdictFailure;
            }
            return verdict != null ? verdict : LlmPort.super.compareAnswers(question, graphAnswer, vectorAnswer, facts);
        }
    }

    private static FakeGraphStore graph() {
        return new FakeGraphStore()
                .entities(CORPUS, new Entity("Irene Adler", "Person", "An opera singer.", List.of("tu-a")))
                .textUnits(CORPUS, new TextUnit("tu-a", CORPUS, "story.txt", 0,
                        "Irene Adler   left\nLondon. She sang at the opera."));
    }

    private static EmbeddedChunk chunk(String id, String document, String text, float... embedding) {
        return new EmbeddedChunk(new Chunk(id, CORPUS, 0, text, document), embedding, new double[]{0, 0});
    }

    /** ch-0 is inside tu-a (same document); ch-2 has the same text but another document. */
    private static VectorStorePort vectors() {
        List<EmbeddedChunk> chunks = List.of(
                chunk("ch-0", "story.txt", "Irene Adler left London.", 1f, 0f),
                chunk("ch-1", "other.txt", "Unrelated text.", 0.5f, 0.5f),
                chunk("ch-2", "copy.txt", "Irene Adler left London.", 0.8f, 0.2f));
        return new VectorStorePort() {
            @Override
            public void persistChunks(String corpusId, Collection<EmbeddedChunk> c) {
            }

            @Override
            public Collection<EmbeddedChunk> chunks(String corpusId) {
                return chunks;
            }
        };
    }

    private static CompareAnswers compareAnswers(LlmPort llm) {
        EmbeddingPort query = text -> new float[]{1f, 0f};
        VectorStorePort vectors = vectors();
        return new CompareAnswers(graph(), null, llm, vectors, new AnswerVectorBaseline(query, vectors, llm));
    }

    @Test
    void aLiveComparisonCitesBothSidesAndMarksTheSharedPassage() {
        ComparisonVerdict llmVerdict = new ComparisonVerdict("GraphRAG read the whole passage.",
                ComparisonVerdict.Source.LLM);
        ComparingLlmPort llm = new ComparingLlmPort(true, llmVerdict, null);

        CompareAnswers.Comparison result = compareAnswers(llm).compare("Who is Irene Adler?", CORPUS,
                CompareAnswers.Mode.LOCAL);

        assertEquals(CompareAnswers.Mode.LOCAL, result.graph().mode());
        assertFalse(result.graph().noAnswer());
        assertEquals("Irene Adler left London [1].", result.graph().answer());
        assertEquals("tu-a", result.graph().citations().getFirst().textUnitId());
        assertEquals(List.of(true), result.graphCitationShared());

        VectorBaselineAnswer vector = result.vector().answer();
        assertEquals("Irene Adler left London [1].", vector.answer());
        assertEquals("ch-0", vector.citations().getFirst().textUnitId());
        assertEquals("story.txt", vector.citations().getFirst().documentName());
        assertEquals(List.of(true), result.vectorCitationShared());

        // ENTITY + TEXT_UNIT for GraphRAG; three VECTOR_CHUNK steps for Vector Search.
        assertEquals(2, result.graph().stats().contextItems());
        assertEquals(1, result.graph().stats().distinctDocuments());
        assertEquals(3, result.vector().stats().contextItems());
        assertEquals(3, result.vector().stats().distinctDocuments());
        assertTrue(result.graph().stats().latencyMs() >= 0);

        assertEquals(3, result.facts().vectorPassages());
        assertEquals(1, result.facts().sharedPassages());
        assertEquals("LOCAL", result.facts().graphMode());
        assertSame(llmVerdict, result.verdict());
        assertEquals(List.of(result.facts()), llm.verdictFacts);
    }

    @Test
    void anOfflineComparisonUsesTheTemplatedAndJoinedAnswersAndTheRuleVerdict() {
        ComparingLlmPort llm = new ComparingLlmPort(false, null, null);

        CompareAnswers.Comparison result = compareAnswers(llm).compare("Who is Irene Adler?", CORPUS, null);

        assertEquals(CompareAnswers.Mode.LOCAL, result.graph().mode());
        assertTrue(result.graph().answer().startsWith("In this corpus graph"));
        assertTrue(result.graph().citations().isEmpty());
        assertTrue(result.vector().answer().answer().startsWith("Based on the retrieved text passages: "));
        assertTrue(result.vector().answer().citations().isEmpty());
        assertEquals(ComparisonVerdict.Source.RULE, result.verdict().source());
        assertEquals(ComparisonVerdict.ruleBased(result.facts()), result.verdict());
        // The templated graph answer reads no Text Unit, so nothing overlaps.
        assertEquals(0, result.facts().sharedPassages());
        assertEquals(0, result.graph().stats().distinctDocuments());
    }

    @Test
    void aFailingVerdictCallFallsBackToTheRule() {
        ComparingLlmPort llm = new ComparingLlmPort(true, null, new IllegalStateException("boom"));

        CompareAnswers.Comparison result = compareAnswers(llm).compare("Who is Irene Adler?", CORPUS,
                CompareAnswers.Mode.LOCAL);

        assertEquals(ComparisonVerdict.ruleBased(result.facts()), result.verdict());
        assertFalse(result.graph().noAnswer());
    }

    @Test
    void aFailingAnswerCallFailsTheComparison() {
        LlmPort throwing = new LlmPort() {
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
                throw new IllegalStateException("answer call failed");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> compareAnswers(throwing).compare("Who is Irene Adler?", CORPUS, CompareAnswers.Mode.LOCAL));
    }

    @Test
    void noAnswersArePassedToTheVerdictAsTheirReasons() {
        ComparingLlmPort llm = new ComparingLlmPort(false, null, null);
        VectorStorePort empty = (corpusId, chunks) -> {
        };
        CompareAnswers compare = new CompareAnswers(graph(), null, llm, empty,
                new AnswerVectorBaseline(text -> new float[]{1f}, empty, llm));

        CompareAnswers.Comparison result = compare.compare("Who is Irene Adler?", CORPUS, CompareAnswers.Mode.GLOBAL);

        assertTrue(result.graph().noAnswer());
        assertTrue(result.vector().answer().noChunks());
        assertTrue(llm.verdictAnswers.get(0).startsWith("(no answer: "));
        assertTrue(llm.verdictAnswers.get(1).startsWith("(no answer: "));
        assertEquals(0, result.facts().vectorPassages());
    }

    @Test
    void graphStepsOnlyCountContextKinds() {
        assertEquals(List.of(RetrievalStep.Kind.ENTITY, RetrievalStep.Kind.TEXT_UNIT),
                compareAnswers(new ComparingLlmPort(true, null, null))
                        .compare("Who is Irene Adler?", CORPUS, CompareAnswers.Mode.LOCAL)
                        .graph().steps().stream().map(RetrievalStep::kind).toList());
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

    private static CompareAnswers compareAnswers(FakeGraphStore graph, VectorStorePort vectors, LlmPort llm) {
        return new CompareAnswers(graph, null, llm, vectors,
                new AnswerVectorBaseline(text -> new float[]{1f, 0f}, vectors, llm));
    }

    /** Two Communities that both mention Irene Adler, so DRIFT spawns two branches seeding the same Entity. */
    private static FakeGraphStore graphWithCommunities() {
        return graph()
                .communities(CORPUS,
                        new Community("community-1", "Rivals", "Irene Adler outwitted Sherlock Holmes."),
                        new Community("community-2", "Opera", "Irene Adler sang at the opera."))
                .memberships(CORPUS, "community-1", "irene adler::person")
                .memberships(CORPUS, "community-2", "irene adler::person");
    }

    @Test
    void aLegacyChunkWithoutADocumentNameMatchesANamedTextUnitOnTextAlone() {
        VectorStorePort legacy = store(chunk("ch-old", "", "Irene Adler left London.", 1f, 0f));

        CompareAnswers.Comparison result = compareAnswers(graph(), legacy, new ComparingLlmPort(true, null, null))
                .compare("Who is Irene Adler?", CORPUS, CompareAnswers.Mode.LOCAL);

        assertEquals(1, result.facts().sharedPassages());
        assertEquals(List.of(true), result.vectorCitationShared());
        assertEquals(List.of(true), result.graphCitationShared());
        // "" is an unknown document, not a document of its own.
        assertEquals(0, result.vector().stats().distinctDocuments());
        assertEquals(1, result.vector().stats().contextItems());
        assertEquals(List.of(new CompareAnswers.RetrievedPassage("ch-old", "", "Irene Adler left London.", true)),
                result.vectorRetrieved());
        assertEquals("tu-a", result.graphRetrieved().getFirst().id());
        assertEquals("story.txt", result.graphRetrieved().getFirst().documentName());
        assertTrue(result.graphRetrieved().getFirst().shared());
    }

    @Test
    void retrievedPassagesListEveryRetrievedChunkWithItsOverlap() {
        CompareAnswers.Comparison result = compareAnswers(new ComparingLlmPort(false, null, null))
                .compare("Who is Irene Adler?", CORPUS, CompareAnswers.Mode.LOCAL);

        assertEquals(List.of("ch-0", "ch-2", "ch-1"),
                result.vectorRetrieved().stream().map(CompareAnswers.RetrievedPassage::id).toList());
        // Offline keyword Local Search reads no Text Unit, so nothing is shared.
        assertTrue(result.graphRetrieved().isEmpty());
        assertFalse(result.vectorRetrieved().getFirst().shared());
    }

    @Test
    void driftCountsEachContextItemOnceAndRecordsItsSubQuestions() {
        CompareAnswers.Comparison result = compareAnswers(graphWithCommunities(), vectors(),
                new ComparingLlmPort(true, null, null))
                .compare("Who is Irene Adler?", CORPUS, CompareAnswers.Mode.DRIFT);

        assertEquals(CompareAnswers.Mode.DRIFT, result.graph().mode());
        List<RetrievalStep> steps = result.graph().steps();
        assertTrue(steps.stream().anyMatch(step -> step.kind() == RetrievalStep.Kind.SUB_QUESTION_SPAWNED));
        long entitySteps = steps.stream().filter(step -> step.kind() == RetrievalStep.Kind.ENTITY).count();
        assertTrue(entitySteps >= 2, "both branches seed Irene Adler");
        // Two Communities, one Entity, one Text Unit — however often the branches revisit them.
        assertEquals(4, result.graph().stats().contextItems());
        assertEquals("DRIFT", result.facts().graphMode());
    }

    @Test
    void globalRecordsCommunitySteps() {
        CompareAnswers.Comparison result = compareAnswers(graphWithCommunities(), vectors(),
                new ComparingLlmPort(true, null, null))
                .compare("Who is Irene Adler?", CORPUS, CompareAnswers.Mode.GLOBAL);

        assertEquals(CompareAnswers.Mode.GLOBAL, result.graph().mode());
        assertTrue(result.graph().steps().stream().anyMatch(step -> step.kind() == RetrievalStep.Kind.COMMUNITY));
        assertEquals("GLOBAL", result.facts().graphMode());
    }

    @Test
    void parsesModes() {
        assertEquals(Optional.of(CompareAnswers.Mode.LOCAL), CompareAnswers.Mode.parse(null));
        assertEquals(Optional.of(CompareAnswers.Mode.LOCAL), CompareAnswers.Mode.parse(" "));
        assertEquals(Optional.of(CompareAnswers.Mode.DRIFT), CompareAnswers.Mode.parse(" drift "));
        assertEquals(Optional.of(CompareAnswers.Mode.GLOBAL), CompareAnswers.Mode.parse("GLOBAL"));
        assertEquals(Optional.empty(), CompareAnswers.Mode.parse("VECTOR"));
        assertEquals(Optional.empty(), CompareAnswers.Mode.parse("nonsense"));
    }

    @Test
    void normalizesWhitespace() {
        assertEquals("a b c", CompareAnswers.normalize("  a \n b\t\tc "));
        assertEquals("", CompareAnswers.normalize(null));
    }
}
