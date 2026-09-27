package io.graphrag.core.port;

import io.graphrag.core.domain.EmbeddedChunk;
import io.graphrag.core.domain.ProjectionModel;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VectorStorePort {

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
