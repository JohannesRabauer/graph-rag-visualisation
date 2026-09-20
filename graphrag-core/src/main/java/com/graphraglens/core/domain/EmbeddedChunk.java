package com.graphraglens.core.domain;

public record EmbeddedChunk(Chunk chunk, float[] embedding, double[] projection) {
}
