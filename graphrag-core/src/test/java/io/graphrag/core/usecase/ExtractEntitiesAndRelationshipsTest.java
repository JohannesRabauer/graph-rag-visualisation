package io.graphrag.core.usecase;

import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.port.GraphStorePort;
import io.graphrag.core.port.LlmPort;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractEntitiesAndRelationshipsTest {

    @org.junit.jupiter.api.Test
    void runPersistsExtractionResults() {
        LlmPort llmPort = corpus -> new GraphExtraction(
                List.of(new Entity("Sherlock Holmes", "Person"), new Entity("Dr. Watson", "Person")),
                List.of(new Relationship("Sherlock Holmes", "Person", "met", "Dr. Watson", "Person"))
        );

        RecordingGraphStorePort graphStorePort = new RecordingGraphStorePort();
        ExtractEntitiesAndRelationships useCase = new ExtractEntitiesAndRelationships(llmPort, graphStorePort);
        Corpus corpus = new Corpus("c1", List.of(new io.graphrag.core.domain.UploadedDocument("case.txt", "Sherlock Holmes met Dr. Watson.")));

        useCase.run(corpus);

        assertNotNull(graphStorePort.persistedExtraction);
        assertEquals("c1", graphStorePort.persistedCorpusId);
        assertEquals(2, graphStorePort.persistedExtraction.entities().size());
        assertEquals(1, graphStorePort.persistedExtraction.relationships().size());
    }

    @org.junit.jupiter.api.Test
    void runWithCallbacksInvokesThemOncePerPersistedEntityAndRelationshipAfterPersisting() {
        LlmPort llmPort = corpus -> new GraphExtraction(
                List.of(new Entity("Sherlock Holmes", "Person"), new Entity("Dr. Watson", "Person")),
                List.of(new Relationship("Sherlock Holmes", "Person", "met", "Dr. Watson", "Person"))
        );

        RecordingGraphStorePort graphStorePort = new RecordingGraphStorePort();
        ExtractEntitiesAndRelationships useCase = new ExtractEntitiesAndRelationships(llmPort, graphStorePort);
        Corpus corpus = new Corpus("c1", List.of(new io.graphrag.core.domain.UploadedDocument("case.txt", "Sherlock Holmes met Dr. Watson.")));

        List<Entity> entityCallbacks = new ArrayList<>();
        List<Relationship> relationshipCallbacks = new ArrayList<>();
        List<Boolean> persistedBeforeEntityCallback = new ArrayList<>();
        List<Boolean> persistedBeforeRelationshipCallback = new ArrayList<>();

        useCase.run(corpus,
                entity -> {
                    entityCallbacks.add(entity);
                    // Persistence happens before the callback loop runs, so a
                    // throwing callback can no longer lose already-persisted data.
                    persistedBeforeEntityCallback.add(graphStorePort.persistedExtraction != null);
                },
                relationship -> {
                    relationshipCallbacks.add(relationship);
                    persistedBeforeRelationshipCallback.add(graphStorePort.persistedExtraction != null);
                });

        assertEquals(2, entityCallbacks.size());
        assertEquals(1, relationshipCallbacks.size());
        assertTrue(persistedBeforeEntityCallback.stream().allMatch(Boolean::booleanValue));
        assertTrue(persistedBeforeRelationshipCallback.stream().allMatch(Boolean::booleanValue));
    }

    private static final class RecordingGraphStorePort implements GraphStorePort {
        private GraphExtraction persistedExtraction;
        private String persistedCorpusId;

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

        @Override
        public void persist(String corpusId, GraphExtraction extraction) {
            this.persistedCorpusId = corpusId;
            this.persistedExtraction = extraction;
        }
    }
}
