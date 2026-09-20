package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Chunk;
import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.EmbeddedChunk;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.port.EmbeddingPort;
import com.graphraglens.core.port.VectorStorePort;

import java.util.ArrayList;
import java.util.List;

public class ConstructVectorIndex {

    private static final int CHUNK_SIZE = 500;
    private static final int MIN_CHUNK_SIZE = CHUNK_SIZE / 2;

    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStorePort;

    public ConstructVectorIndex(EmbeddingPort embeddingPort, VectorStorePort vectorStorePort) {
        this.embeddingPort = embeddingPort;
        this.vectorStorePort = vectorStorePort;
    }

    public List<EmbeddedChunk> run(Corpus corpus) {
        List<Chunk> chunks = chunk(corpus);
        if (chunks.isEmpty()) {
            return List.of();
        }

        List<float[]> embeddings = chunks.stream()
                .map(chunk -> embeddingPort.embed(chunk.text()))
                .toList();
        double[][] projections = TwoDProjection.project(embeddings);

        List<EmbeddedChunk> embeddedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            embeddedChunks.add(new EmbeddedChunk(chunks.get(i), embeddings.get(i), projections[i]));
        }
        vectorStorePort.persistChunks(corpus.id(), embeddedChunks);
        return embeddedChunks;
    }

    private List<Chunk> chunk(Corpus corpus) {
        List<Chunk> chunks = new ArrayList<>();
        if (corpus == null || corpus.documents() == null || corpus.documents().isEmpty()) {
            return chunks;
        }

        int ordinal = 0;
        for (UploadedDocument document : corpus.documents()) {
            if (document == null || document.content() == null || document.content().isBlank()) {
                continue;
            }

            String content = document.content();
            int start = 0;
            while (start < content.length()) {
                int end = Math.min(content.length(), start + CHUNK_SIZE);
                if (end < content.length()) {
                    int candidate = end;
                    while (candidate > start + MIN_CHUNK_SIZE && content.charAt(candidate - 1) != ' ') {
                        candidate--;
                    }
                    if (candidate > start + MIN_CHUNK_SIZE && content.charAt(candidate - 1) == ' ') {
                        end = candidate;
                    }
                }

                String text = content.substring(start, end).trim();
                if (!text.isBlank()) {
                    chunks.add(new Chunk(corpus.id() + "::chunk-" + ordinal, corpus.id(), ordinal, text));
                    ordinal++;
                }
                start = end;
            }
        }
        return chunks;
    }
}
