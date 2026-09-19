package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.GraphExtraction;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExtractEntitiesAndRelationshipsTest {

    @org.junit.jupiter.api.Test
    void runPersistsExtractionResults() {
        LlmPort llmPort = corpus -> new GraphExtraction(
                List.of(new Entity("Sherlock Holmes", "Person"), new Entity("Dr. Watson", "Person")),
                List.of(new Relationship("Sherlock Holmes", "Person", "met", "Dr. Watson", "Person"))
        );

        RecordingGraphStorePort graphStorePort = new RecordingGraphStorePort();
        ExtractEntitiesAndRelationships useCase = new ExtractEntitiesAndRelationships(llmPort, graphStorePort);
        Corpus corpus = new Corpus("c1", List.of(new com.graphraglens.core.domain.UploadedDocument("case.txt", "Sherlock Holmes met Dr. Watson.")));

        useCase.run(corpus);

        assertNotNull(graphStorePort.persistedExtraction);
        assertEquals(2, graphStorePort.persistedExtraction.entities().size());
        assertEquals(1, graphStorePort.persistedExtraction.relationships().size());
    }

    private static final class RecordingGraphStorePort implements GraphStorePort {
        private GraphExtraction persistedExtraction;

        @Override
        public void persistEntities(java.util.Collection<Entity> entities) {
            // no-op: the persistence is verified through the entire extraction object
        }

        @Override
        public void persistRelationships(java.util.Collection<Relationship> relationships) {
            // no-op: the persistence is verified through the entire extraction object
        }

        @Override
        public void persist(GraphExtraction extraction) {
            this.persistedExtraction = extraction;
        }
    }
}
