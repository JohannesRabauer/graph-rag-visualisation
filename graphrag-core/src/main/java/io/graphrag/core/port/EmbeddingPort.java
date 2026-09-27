package io.graphrag.core.port;

public interface EmbeddingPort {

    float[] embed(String text);
}
