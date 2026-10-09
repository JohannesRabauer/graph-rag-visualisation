package dev.rabauer.graphrag.core.port;

import java.util.ArrayList;
import java.util.List;

/**
 * Port for turning chunk text into a dense embedding vector.
 *
 * <p>Implementations wrap a specific embedding model or provider. The core
 * only relies on the returned vector's dimensionality being stable across
 * calls for a given implementation, since {@link
 * dev.rabauer.graphrag.core.usecase.ConstructVectorIndex} fits a single 2D projection
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

    /**
     * Embeds several texts at once. A model with a batch call (an
     * {@code /api/embed} that takes a list, OpenAI's {@code input} array)
     * should override this with one request, since a request per text is slow
     * and, on a local server that swaps models, very slow;
     * {@link dev.rabauer.graphrag.core.usecase.EmbedGraphElements} calls it in
     * batches of a configurable size.
     *
     * <p>The default embeds the texts one by one with {@link #embed(String)}, so
     * existing implementations, lambdas included, keep working.
     *
     * @param texts the texts to embed; never null, may be empty
     * @return one vector per text, in the order of {@code texts} and of the same
     *         dimensionality as {@link #embed(String)}'s; never null
     */
    default List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    /**
     * Whether this port is backed by a real semantic embedding model, i.e.
     * whether nearby vectors mean nearby meanings. Deterministic offline
     * stubs return {@code false}; Entities and Communities are then never
     * embedded and the searches keep matching by keywords.
     *
     * @return {@code true} by default
     */
    default boolean isSemantic() {
        return true;
    }
}
