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

    @org.junit.jupiter.api.Test
    void runWithCallbacksInvokesEachOncePerPersistedEntityAndRelationship() {
        LlmPort llmPort = corpus -> new GraphExtraction(
                List.of(new Entity("Sherlock Holmes", "Person"), new Entity("Dr. Watson", "Person")),
                List.of(new Relationship("Sherlock Holmes", "Person", "met", "Dr. Watson", "Person"))
        );

        RecordingGraphStorePort graphStorePort = new RecordingGraphStorePort();
        ExtractEntitiesAndRelationships useCase = new ExtractEntitiesAndRelationships(llmPort, graphStorePort);
        Corpus corpus = new Corpus("c1", List.of(new com.graphraglens.core.domain.UploadedDocument("case.txt", "Sherlock Holmes met Dr. Watson.")));

        List<Entity> notifiedEntities = new ArrayList<>();
        List<Relationship> notifiedRelationships = new ArrayList<>();
        useCase.run(corpus, notifiedEntities::add, notifiedRelationships::add);

        assertEquals(2, notifiedEntities.size());
        assertEquals(List.of("Sherlock Holmes", "Dr. Watson"),
                notifiedEntities.stream().map(Entity::name).toList());
        assertEquals(1, notifiedRelationships.size());
        assertEquals("met", notifiedRelationships.get(0).type());
    }

    @org.junit.jupiter.api.Test
    void runWithNullCallbacksBehavesLikeRunWithoutCallbacks() {
        LlmPort llmPort = corpus -> new GraphExtraction(
                List.of(new Entity("Sherlock Holmes", "Person")), List.of());
        RecordingGraphStorePort graphStorePort = new RecordingGraphStorePort();
        ExtractEntitiesAndRelationships useCase = new ExtractEntitiesAndRelationships(llmPort, graphStorePort);
        Corpus corpus = new Corpus("c1", List.of(new com.graphraglens.core.domain.UploadedDocument("case.txt", "Sherlock Holmes.")));

        useCase.run(corpus, null, null);

        assertNotNull(graphStorePort.persistedExtraction);
        assertEquals(1, graphStorePort.persistedExtraction.entities().size());
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
