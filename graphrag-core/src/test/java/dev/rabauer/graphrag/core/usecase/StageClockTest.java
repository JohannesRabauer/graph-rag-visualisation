package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.StageTiming;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageClockTest {

    @Test
    void theTimedLlmPortOverridesEveryLlmPortMethod() throws Exception {
        LlmPort timed = new StageClock().llm(corpus -> new GraphExtraction(List.of(), List.of()));
        for (Method method : LlmPort.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Method implementation = timed.getClass().getMethod(method.getName(), method.getParameterTypes());
            assertEquals(timed.getClass(), implementation.getDeclaringClass(),
                    "StageClock's LlmPort must forward " + method + " so the delegate's override answers");
        }
    }

    @Test
    void callsAreForwardedCountedAndTheStagesAddUp() {
        StageClock clock = new StageClock();
        LlmPort llm = clock.llm(new LlmPort() {
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
                return new SynthesizedAnswer(false, "answer to " + question);
            }
        });
        EmbeddingPort embedding = clock.embedding(new EmbeddingPort() {
            @Override
            public float[] embed(String text) {
                return new float[]{1f};
            }

            @Override
            public boolean isSemantic() {
                return false;
            }
        });

        assertTrue(llm.synthesizesAnswers());
        assertEquals("answer to q", llm.synthesizeAnswer("q", List.of()).text());
        llm.synthesizeAnswer("q", List.of());
        embedding.embed("q");
        assertFalse(embedding.isSemantic());

        StageTiming timing = clock.timing();
        assertEquals(2, timing.llmCalls());
        assertEquals(1, timing.embeddingCalls());
        assertEquals(timing.totalMs(), timing.retrievalMs() + timing.embeddingMs() + timing.llmMs(), 1);
    }

    @Test
    void aFailingCallIsStillCountedAndNullPortsStayNull() {
        StageClock clock = new StageClock();
        LlmPort llm = clock.llm(new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus corpus) {
                throw new IllegalStateException("boom");
            }
        });

        try {
            llm.extract(new Corpus("c", List.of()));
        } catch (IllegalStateException expected) {
            // counted below
        }

        assertEquals(1, clock.timing().llmCalls());
        assertNull(clock.llm(null));
        assertNull(clock.embedding(null));
    }
}
