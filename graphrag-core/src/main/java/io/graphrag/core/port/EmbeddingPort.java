package io.graphrag.core.port;

/**
 * Port for turning chunk text into a dense embedding vector.
 *
 * <p>Implementations wrap a specific embedding model or provider. The core
 * only relies on the returned vector's dimensionality being stable across
 * calls for a given implementation, since {@link
 * io.graphrag.core.usecase.ConstructVectorIndex} fits a single 2D projection
 * over a batch of these vectors.
 */
public interface EmbeddingPort {

    /**
     * Embeds a piece of text into a dense vector.
     *
     * @param text the chunk text to embed; never null
     * @return the dense embedding vector for {@code text}; never null;
     *         implementations return a vector of consistent dimensionality
     *         across calls
     */
    float[] embed(String text);
}
