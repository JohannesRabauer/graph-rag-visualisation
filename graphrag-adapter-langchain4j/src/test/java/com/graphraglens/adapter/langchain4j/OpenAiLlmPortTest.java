package com.graphraglens.adapter.langchain4j;

import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.usecase.EntityTypes;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiLlmPortTest {

    @Test
    void rejectsBlankApiKey() {
        assertThrows(IllegalArgumentException.class, () -> new OpenAiLlmPort(" "));
        assertThrows(IllegalArgumentException.class, () -> new OpenAiLlmPort(null));
    }

    @Test
    void parsesWellFormedJsonExtractionResponse() {
        OpenAiLlmPort port = new OpenAiLlmPort("test-key");

        String response = """
                {
                  "entities": [
                    { "name": "Sherlock Holmes", "type": "Person", "description": "A detective." },
                    { "name": "Dr. Watson", "type": "Person" }
                  ],
                  "relationships": [
                    { "source": "Sherlock Holmes", "sourceType": "Person", "type": "met", "target": "Dr. Watson", "targetType": "Person", "description": "Holmes met Watson." }
                  ]
                }
                """;

        GraphExtraction extraction = port.parseExtraction(response);

        assertEquals(2, extraction.entities().size());
        assertEquals(1, extraction.relationships().size());
        assertEquals("A detective.", extraction.entities().getFirst().description());
        assertEquals("", extraction.entities().get(1).description());
        assertEquals("Holmes met Watson.", extraction.relationships().getFirst().description());
        assertTrue(extraction.relationships().getFirst().type().equals("met"));
    }

    @Test
    void stripsMarkdownCodeFencesBeforeParsing() {
        OpenAiLlmPort port = new OpenAiLlmPort("test-key");

        String response = """
                ```json
                { "entities": [ { "name": "Irene Adler", "type": "Person" } ], "relationships": [] }
                ```
                """;

        GraphExtraction extraction = port.parseExtraction(response);

        assertEquals(1, extraction.entities().size());
        assertEquals("Irene Adler", extraction.entities().getFirst().name());
    }

    @Test
    void invalidJsonSurfacesAsLlmCallFailedException() {
        OpenAiLlmPort port = new OpenAiLlmPort("test-key");

        assertThrows(OpenAiLlmPort.LlmCallFailedException.class, () -> port.parseExtraction("not json at all"));
    }

    @Test
    void blankResponseYieldsEmptyExtraction() {
        OpenAiLlmPort port = new OpenAiLlmPort("test-key");

        GraphExtraction extraction = port.parseExtraction("   ");

        assertTrue(extraction.entities().isEmpty());
        assertTrue(extraction.relationships().isEmpty());
    }

    @Test
    void extractionPromptListsEveryEntityTypeAndContainsTheUnitText() {
        OpenAiLlmPort port = new OpenAiLlmPort("test-key");
        TextUnit unit = new TextUnit("c1::doc-0::tu-3", "c1", "history-of-java.txt", 3,
                "James Gosling started the Green Project at Sun Microsystems in 1991.");

        String prompt = port.extractionPrompt(unit, EntityTypes.ALL);

        for (String type : EntityTypes.ALL) {
            assertTrue(prompt.contains(type), "prompt should list " + type);
        }
        assertTrue(prompt.contains(unit.text()));
        assertTrue(prompt.toLowerCase().contains("json"), "JSON mode requires the word json in the prompt");
        assertTrue(prompt.contains("description"));
    }

    @Test
    void extractThrowsWhenFinishReasonIsLength() {
        ChatModel json = new FakeChatModel(FinishReason.LENGTH, "{\"entities\":[],\"relationships\":[]}");
        OpenAiLlmPort port = new OpenAiLlmPort(json, new FakeChatModel(FinishReason.STOP, ""));

        OpenAiLlmPort.LlmCallFailedException failure = assertThrows(OpenAiLlmPort.LlmCallFailedException.class,
                () -> port.extract(new TextUnit("u0", "c1", "doc.txt", 0, "Ada Lovelace."), EntityTypes.ALL));

        assertTrue(failure.getMessage().contains("output-token limit"));
    }

    @Test
    void extractParsesStopResponseFromChatRequest() {
        String response = """
                { "entities": [ { "name": "Ada Lovelace", "type": "Person", "description": "A mathematician." } ],
                  "relationships": [] }
                """;
        OpenAiLlmPort port = new OpenAiLlmPort(new FakeChatModel(FinishReason.STOP, response),
                new FakeChatModel(FinishReason.STOP, ""));

        GraphExtraction extraction = port.extract(new TextUnit("u0", "c1", "doc.txt", 0, "Ada Lovelace."),
                EntityTypes.ALL);

        assertEquals("Ada Lovelace", extraction.entities().getFirst().name());
        assertEquals("A mathematician.", extraction.entities().getFirst().description());
    }

    private static final class FakeChatModel implements ChatModel {
        private final FinishReason finishReason;
        private final String text;

        private FakeChatModel(FinishReason finishReason, String text) {
            this.finishReason = finishReason;
            this.text = text;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            assertEquals(Integer.valueOf(OpenAiLlmPort.MAX_EXTRACTION_OUTPUT_TOKENS), request.maxOutputTokens());
            assertEquals(1, request.messages().size());
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .finishReason(finishReason)
                    .build();
        }
    }
}
