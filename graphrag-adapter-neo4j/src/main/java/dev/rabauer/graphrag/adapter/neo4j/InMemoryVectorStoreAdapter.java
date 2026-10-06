package dev.rabauer.graphrag.adapter.neo4j;

import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.ProjectionModel;
import dev.rabauer.graphrag.core.port.VectorStorePort;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryVectorStoreAdapter implements VectorStorePort {

    private final Map<String, Map<String, EmbeddedChunk>> chunksByCorpusId = new LinkedHashMap<>();
    private final Map<String, ProjectionModel> projectionModelByCorpusId = new LinkedHashMap<>();

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

    @Override
    public void persistProjectionModel(String corpusId, ProjectionModel model) {
        if (corpusId == null || corpusId.isBlank() || model == null) {
            return;
        }
        projectionModelByCorpusId.put(corpusId, model);
    }

    @Override
    public void deleteChunksOf(String corpusId, String documentName) {
        Map<String, EmbeddedChunk> scopedChunks = chunksByCorpusId.get(corpusId);
        if (scopedChunks != null && documentName != null) {
            scopedChunks.values().removeIf(chunk -> documentName.equals(chunk.chunk().documentName()));
        }
    }

    @Override
    public Optional<ProjectionModel> projectionModel(String corpusId) {
        return Optional.ofNullable(projectionModelByCorpusId.get(corpusId));
    }
}
