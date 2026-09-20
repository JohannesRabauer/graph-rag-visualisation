package com.graphraglens.adapter.neo4j;

import com.graphraglens.core.domain.EmbeddedChunk;
import com.graphraglens.core.port.VectorStorePort;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryVectorStoreAdapter implements VectorStorePort {

    private final Map<String, Map<String, EmbeddedChunk>> chunksByCorpusId = new LinkedHashMap<>();

    @Override
    public void persistChunks(String corpusId, Collection<EmbeddedChunk> chunks) {
        if (corpusId == null || corpusId.isBlank() || chunks == null) {
            return;
        }

        Map<String, EmbeddedChunk> scopedChunks = chunksByCorpusId.computeIfAbsent(corpusId,
                ignored -> new LinkedHashMap<>());
        for (EmbeddedChunk chunk : chunks) {
            if (chunk == null || chunk.chunk() == null) {
                continue;
            }
            scopedChunks.put(chunk.chunk().id(), chunk);
        }
    }

    @Override
    public Collection<EmbeddedChunk> chunks(String corpusId) {
        Map<String, EmbeddedChunk> scopedChunks = chunksByCorpusId.get(corpusId);
        if (scopedChunks == null) {
            return List.of();
        }
        return Collections.unmodifiableCollection(scopedChunks.values());
    }
}
