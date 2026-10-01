package dev.rabauer.graphrag.core.port;

import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.ProjectionModel;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Port for persisting and querying a corpus's embedded chunks and its fitted
 * 2D projection model.
 */
public interface VectorStorePort {

    /**
     * Persists a corpus's embedded chunks.
     *
     * @param corpusId the corpus the chunks belong to; never null
     * @param chunks   the embedded chunks (text chunk + embedding vector +
     *                 2D projection) to persist for that corpus; never null
     */
    void persistChunks(String corpusId, Collection<EmbeddedChunk> chunks);

    default Collection<EmbeddedChunk> chunks(String corpusId) {
        return List.of();
    }

    /**
     * Persists the fitted 2D projection model (mean + principal components)
     * computed once over a corpus's chunk embeddings at ingestion time
     * (Story 8.1), so a later query embedding can be projected into the
     * same settled space (Story 8.5) without recomputing or reshuffling it.
     */
    default void persistProjectionModel(String corpusId, ProjectionModel model) {
    }

    default Optional<ProjectionModel> projectionModel(String corpusId) {
        return Optional.empty();
    }
}
