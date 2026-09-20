package com.graphraglens.adapter.langchain4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiEmbeddingPortTest {

    @Test
    void rejectsBlankApiKey() {
        assertThrows(IllegalArgumentException.class, () -> new OpenAiEmbeddingPort(" "));
        assertThrows(IllegalArgumentException.class, () -> new OpenAiEmbeddingPort(null));
    }

    @Test
    void fallsBackToTheDefaultModelWhenTheConfiguredNameIsBlank() {
        assertDoesNotThrow(() -> new OpenAiEmbeddingPort("test-key", " "));
    }
}
