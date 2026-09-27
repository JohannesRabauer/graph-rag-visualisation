package io.graphrag.core.domain;

/**
 * A {@link Chunk} together with its embedding vector and its 2D projection
 * (fitted once over the corpus's own chunk embeddings; see {@link
 * io.graphrag.core.usecase.ConstructVectorIndex}).
 *
 * @param chunk      the chunk this embedding was computed from; never null
 * @param embedding  the dense embedding vector for {@code chunk}'s text;
 *                   never null
 * @param projection the chunk's 2D position under the corpus's fitted
 *                   {@link ProjectionModel}; never null
 */
public record EmbeddedChunk(Chunk chunk, float[] embedding, double[] projection) {
}
