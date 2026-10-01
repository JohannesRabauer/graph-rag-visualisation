package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.core.usecase.SemanticTestFixtures.RecordingLlmPort;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With an answer-synthesizing LLM the vector baseline answers from its top-k
 * chunks as citable "Source passage" items, through the same synthesis and
 * citation machinery as GraphRAG; offline it keeps the joined-text answer.
 */
class AnswerVectorBaselineSynthesisTest {

    private static final EmbeddingPort QUERY = text -> new float[]{1f, 0f};

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

    private static EmbeddedChunk chunk(String id, String document, String text, float... embedding) {
        return new EmbeddedChunk(new Chunk(id, "c", 0, text, document), embedding, new double[]{0, 0});
    }

    private static final EmbeddedChunk FIRST =
            chunk("c::chunk-0", "a.txt", "Holmes   lives\nat Baker Street.", 1f, 0f);
    private static final EmbeddedChunk SECOND = chunk("c::chunk-1", "b.txt", "Watson is a doctor.", 0.6f, 0.4f);

    @Test
    void numbersTheTopChunksAsSourcePassagesAndResolvesCitationsToChunks() {
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false,
                "Holmes lives at Baker Street [1]. Watson is a doctor [2][9]."));

        VectorBaselineAnswer result = new AnswerVectorBaseline(QUERY, store(SECOND, FIRST), llm)
                .answer("Where does Holmes live?", "c");

        List<ContextItem> context = llm.contexts.getFirst();
        assertEquals(2, context.size());
        assertEquals(RetrievalStep.Kind.TEXT_UNIT, context.get(0).kind());
        assertEquals("c::chunk-0", context.get(0).textUnitId());
        assertEquals(1, context.get(0).number());
        assertEquals("c::chunk-1", context.get(1).textUnitId());

        assertFalse(result.noAnswer());
        assertEquals("Holmes lives at Baker Street [1]. Watson is a doctor [2].", result.answer());
        assertEquals(List.of(
                new Citation("c::chunk-0", "a.txt", "Holmes lives at Baker Street."),
                new Citation("c::chunk-1", "b.txt", "Watson is a doctor.")), result.citations());

        List<String> chunkSteps = result.steps().stream()
                .filter(step -> step.kind() == RetrievalStep.Kind.VECTOR_CHUNK)
                .map(RetrievalStep::identifier).toList();
        for (Citation citation : result.citations()) {
            assertTrue(chunkSteps.contains(citation.textUnitId()));
        }
        RetrievalStep last = result.steps().getLast();
        assertEquals(RetrievalStep.Kind.SYNTHESIS, last.kind());
        assertEquals(result.answer(), last.label());
    }

    @Test
    void notInContextBecomesANoAnswerWithAReasonAndKeepsTheRetrievalSteps() {
        for (SynthesizedAnswer said : List.of(new SynthesizedAnswer(true, ""),
                new SynthesizedAnswer(false, " \"not_in_context.\" "))) {
            VectorBaselineAnswer result = new AnswerVectorBaseline(QUERY, store(FIRST),
                    new RecordingLlmPort(context -> said)).answer("q", "c");

            assertTrue(result.noAnswer());
            assertFalse(result.noChunks());
            assertNull(result.answer());
            assertEquals(VectorBaselineAnswer.NOT_IN_CONTEXT_REASON, result.reason());
            assertEquals(List.of(RetrievalStep.Kind.VECTOR_QUERY_EMBEDDED, RetrievalStep.Kind.VECTOR_CHUNK),
                    result.steps().stream().map(RetrievalStep::kind).toList());
            assertTrue(result.citations().isEmpty());
        }
    }

    @Test
    void anAnswerOfOnlyInvalidMarkersIsNotInContext() {
        VectorBaselineAnswer result = new AnswerVectorBaseline(QUERY, store(FIRST),
                new RecordingLlmPort(context -> new SynthesizedAnswer(false, "[7]"))).answer("q", "c");

        assertTrue(result.noAnswer());
        assertEquals(VectorBaselineAnswer.NOT_IN_CONTEXT_REASON, result.reason());
    }

    @Test
    void aNonSynthesizingPortKeepsTheJoinedTextAnswerWithoutCitations() {
        RecordingLlmPort llm = new RecordingLlmPort(false, context -> new SynthesizedAnswer(false, "unused"));

        VectorBaselineAnswer result = new AnswerVectorBaseline(QUERY, store(FIRST), llm).answer("q", "c");

        assertTrue(llm.contexts.isEmpty());
        assertFalse(result.noAnswer());
        assertTrue(result.answer().startsWith("Based on the retrieved text passages: "));
        assertTrue(result.citations().isEmpty());
        assertEquals(List.of(RetrievalStep.Kind.VECTOR_QUERY_EMBEDDED, RetrievalStep.Kind.VECTOR_CHUNK,
                RetrievalStep.Kind.SYNTHESIS), result.steps().stream().map(RetrievalStep::kind).toList());
    }

    @Test
    void noChunksIsStillTheNoChunksOutcomeWithoutAnLlmCall() {
        RecordingLlmPort llm = new RecordingLlmPort(context -> new SynthesizedAnswer(false, "unused"));

        VectorBaselineAnswer result = new AnswerVectorBaseline(QUERY, store(), llm).answer("q", "c");

        assertTrue(result.noChunks());
        assertTrue(result.noAnswer());
        assertTrue(llm.contexts.isEmpty());
    }

    @Test
    void chunksKeepTheOldConstructorWithAnUnknownDocument() {
        assertEquals("", new Chunk("id", "c", 0, "text").documentName());
        assertEquals("", new Chunk("id", "c", 0, "text", null).documentName());
    }
}
