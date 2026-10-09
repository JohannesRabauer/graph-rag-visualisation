package dev.rabauer.graphrag.adapter.langchain4j;

import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.util.List;

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

    /** One request for all {@code texts} (OpenAI takes a list of inputs). */
    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<TextSegment> segments = texts.stream().map(text -> TextSegment.from(text == null ? "" : text)).toList();
        return model.embedAll(segments).content().stream().map(Embedding::vector).toList();
    }
}
