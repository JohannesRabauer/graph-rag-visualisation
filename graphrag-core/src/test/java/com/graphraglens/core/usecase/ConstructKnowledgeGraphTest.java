package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.ExtractedEntity;
import com.graphraglens.core.domain.ExtractedRelationship;
import com.graphraglens.core.domain.ExtractionResult;
import com.graphraglens.core.domain.IngestionProgressEvent;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructKnowledgeGraphTest {

    @Test
    void constructsGraphAndPublishesProgressEvents() {
        RecordingGraphStore graphStore = new RecordingGraphStore();
        LlmPort llm = text -> new ExtractionResult(
                List.of(new ExtractedEntity("Sherlock Holmes", "Person")),
                List.of(new ExtractedRelationship(
                        new ExtractedEntity("Sherlock Holmes", "Person"),
                        new ExtractedEntity("Dr. Watson", "Person"),
                        "KNOWS")));

        ConstructKnowledgeGraph useCase = new ConstructKnowledgeGraph(llm, graphStore);
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("doc.txt", "sample text")));
        List<IngestionProgressEvent> events = new ArrayList<>();

        useCase.construct(corpus, events::add);

        assertEquals(1, graphStore.entities.size());
        assertEquals(1, graphStore.relationships.size());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("entity-extracted")));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("relationship-extracted")));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("ingestion-complete")));
    }

    private static class RecordingGraphStore implements GraphStorePort {

        private final List<ExtractedEntity> entities = new ArrayList<>();
        private final List<ExtractedRelationship> relationships = new ArrayList<>();

        @Override
        public void mergeEntity(ExtractedEntity entity) {
            entities.add(entity);
        }

        @Override
        public void mergeRelationship(ExtractedRelationship relationship) {
            relationships.add(relationship);
        }
    }
}
