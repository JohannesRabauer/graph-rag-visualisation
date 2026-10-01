package com.graphraglens.adapter.langchain4j;

import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.usecase.EntityTypes;
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
                    { "name": "Sherlock Holmes", "type": "Person" },
                    { "name": "Dr. Watson", "type": "Person" }
                  ],
                  "relationships": [
                    { "source": "Sherlock Holmes", "sourceType": "Person", "type": "met", "target": "Dr. Watson", "targetType": "Person" }
                  ]
                }
                """;

        GraphExtraction extraction = port.parseExtraction(response);

        assertEquals(2, extraction.entities().size());
        assertEquals(1, extraction.relationships().size());
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
    }
}
