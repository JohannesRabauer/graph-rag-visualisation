package dev.rabauer.graphrag.core.llm;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every completion says it expects one JSON object, so a client can switch its JSON mode on. */
class PromptedLlmPortJsonModeTest {

    private static final String SUMMARY = "{\"title\":\"T\",\"summary\":\"S\"}";
    private static final String EXTRACTION = "{\"entities\":[{\"name\":\"Ada\",\"type\":\"Person\"}],\"relationships\":[]}";

    @Test
    void everyRequestExpectsJsonByDefault() {
        PromptedLlmPortTest.ScriptedPort port = new PromptedLlmPortTest.ScriptedPort(
                PromptedLlmPort.Options.defaults().withExtraction(true).withGleanings(1),
                SUMMARY, EXTRACTION, "{\"entities\":[{\"name\":\"Grace\",\"type\":\"Person\"}],\"relationships\":[]}");

        port.summarizeCommunity(List.of(new Entity("A", "Class")), List.<Relationship>of());
        port.extract(new TextUnit("tu", "c", "doc", 0, "Ada wrote code.", java.util.Map.of(), null), List.of("Person"));

        // One summary, one extraction and one gleaning turn.
        assertEquals(3, port.requests.size());
        assertTrue(port.requests.stream().allMatch(PromptedLlmPort.CompletionRequest::jsonExpected));
    }

    @Test
    void theCorrectiveRetryExpectsJsonToo() {
        PromptedLlmPortTest.ScriptedPort port = new PromptedLlmPortTest.ScriptedPort("not json", SUMMARY);

        port.summarizeCommunity(List.of(new Entity("A", "Class")), List.of());

        assertEquals(2, port.requests.size());
        assertTrue(port.requests.stream().allMatch(PromptedLlmPort.CompletionRequest::jsonExpected));
    }

    @Test
    void theHintCanBeSwitchedOff() {
        PromptedLlmPortTest.ScriptedPort port = new PromptedLlmPortTest.ScriptedPort(
                PromptedLlmPort.Options.defaults().withJsonMode(false), SUMMARY);

        port.summarizeCommunity(List.of(new Entity("A", "Class")), List.of());

        assertFalse(port.requests.getFirst().jsonExpected());
        assertEquals(1, port.requests.size());
    }

    @Test
    void aRequestBuiltWithoutTheFlagExpectsJson() {
        PromptedLlmPort.CompletionRequest request = new PromptedLlmPort.CompletionRequest(
                PromptedLlmPort.Purpose.ANSWER, List.of(), "{}", 10);

        assertTrue(request.jsonExpected());
        assertTrue(PromptedLlmPort.Options.defaults().jsonMode());
        assertEquals(PromptedLlmPort.Options.defaults(), new PromptedLlmPort.Options(false, false, true, 4096, 512,
                512, 1024, 0, 256, false));
    }
}
