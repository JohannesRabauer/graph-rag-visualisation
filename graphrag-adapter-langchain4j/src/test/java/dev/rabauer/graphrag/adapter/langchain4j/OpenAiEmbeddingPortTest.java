package dev.rabauer.graphrag.adapter.langchain4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void theOpenAiModelIsASemanticEmbeddingModel() {
        assertTrue(new OpenAiEmbeddingPort("test-key").isSemantic());
    }
}
