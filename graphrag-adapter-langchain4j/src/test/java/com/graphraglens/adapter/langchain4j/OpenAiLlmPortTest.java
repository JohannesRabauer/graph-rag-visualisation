package com.graphraglens.adapter.langchain4j;

import com.graphraglens.core.domain.GraphExtraction;
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
}
