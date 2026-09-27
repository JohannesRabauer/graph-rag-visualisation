package io.graphrag.core.domain;

public record EmbeddedChunk(Chunk chunk, float[] embedding, double[] projection) {
}
