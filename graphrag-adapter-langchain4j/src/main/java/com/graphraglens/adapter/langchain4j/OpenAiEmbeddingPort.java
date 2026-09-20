package com.graphraglens.adapter.langchain4j;

import com.graphraglens.core.port.EmbeddingPort;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

public class OpenAiEmbeddingPort implements EmbeddingPort {

    private static final String DEFAULT_MODEL = "text-embedding-3-small";

    private final EmbeddingModel model;

    public OpenAiEmbeddingPort(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public OpenAiEmbeddingPort(String apiKey, String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenAI API key must not be blank");
        }
        String modelNameToUse = modelName == null || modelName.isBlank() ? DEFAULT_MODEL : modelName;
        this.model = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(modelNameToUse)
                .maxRetries(0)
                .build();
    }

    @Override
    public float[] embed(String text) {
        return model.embed(text == null ? "" : text).content().vector();
    }
}
