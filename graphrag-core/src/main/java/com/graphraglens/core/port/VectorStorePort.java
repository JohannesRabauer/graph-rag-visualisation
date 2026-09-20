package com.graphraglens.core.port;

import com.graphraglens.core.domain.EmbeddedChunk;

import java.util.Collection;
import java.util.List;

public interface VectorStorePort {

    void persistChunks(String corpusId, Collection<EmbeddedChunk> chunks);

    default Collection<EmbeddedChunk> chunks(String corpusId) {
        return List.of();
    }
}
