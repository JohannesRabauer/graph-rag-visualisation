package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.ExtractedEntity;
import com.graphraglens.core.domain.ExtractedRelationship;
import com.graphraglens.core.domain.ExtractionResult;
import com.graphraglens.core.domain.IngestionProgressEvent;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds a knowledge graph from one corpus via live LLM extraction.
 */
public class ConstructKnowledgeGraph {

    private final LlmPort llmPort;
    private final GraphStorePort graphStorePort;

    public ConstructKnowledgeGraph(LlmPort llmPort, GraphStorePort graphStorePort) {
        this.llmPort = llmPort;
        this.graphStorePort = graphStorePort;
    }

    public void construct(Corpus corpus, Consumer<IngestionProgressEvent> progressConsumer) {
        int entityCount = 0;
        int relationshipCount = 0;
        int documentIndex = 0;

        for (UploadedDocument document : corpus.documents()) {
            documentIndex += 1;
            progressConsumer.accept(new IngestionProgressEvent(
                    "document-processing",
                    Map.of(
                            "corpusId", corpus.id(),
                            "documentIndex", documentIndex,
                            "documentName", document.filename())));

            ExtractionResult result = llmPort.extractEntitiesAndRelationships(document.content());

            for (ExtractedEntity entity : result.entities()) {
                graphStorePort.mergeEntity(entity);
                entityCount += 1;
                progressConsumer.accept(new IngestionProgressEvent(
                        "entity-extracted",
                        Map.of(
                                "corpusId", corpus.id(),
                                "name", entity.name(),
                                "type", entity.type(),
                                "count", entityCount)));
            }

            for (ExtractedRelationship relationship : result.relationships()) {
                graphStorePort.mergeRelationship(relationship);
                relationshipCount += 1;
                progressConsumer.accept(new IngestionProgressEvent(
                        "relationship-extracted",
                        Map.of(
                                "corpusId", corpus.id(),
                                "source", relationship.source().name(),
                                "target", relationship.target().name(),
                                "type", relationship.type(),
                                "count", relationshipCount)));
            }
        }

        progressConsumer.accept(new IngestionProgressEvent(
                "ingestion-complete",
                Map.of(
                        "corpusId", corpus.id(),
                        "documentsProcessed", documentIndex,
                        "entitiesExtracted", entityCount,
                        "relationshipsExtracted", relationshipCount)));
    }
}
