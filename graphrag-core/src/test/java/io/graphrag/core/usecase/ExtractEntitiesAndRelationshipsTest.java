package io.graphrag.core.usecase;

import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.domain.UploadedDocument;
import io.graphrag.core.port.GraphStorePort;
import io.graphrag.core.port.LlmPort;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** Paragraph-separated sections, long enough for at least three Text Units. */
    private static String threeUnitText(String lastSentence) {
        String filler = "The quick brown fox jumps over the lazy dog again and again. ".repeat(80);
        return filler + "\n\n" + filler + "\n\n" + filler + "\n\n" + filler + "\n\n" + lastSentence;
    }

    @org.junit.jupiter.api.Test
    void anEntityNamedOnlyInTheLastUnitIsExtractedAndPersisted() {
        String text = threeUnitText("Ada Lovelace appears only here.");
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("long.txt", text)));
        List<TextUnit> units = TextUnitSplitter.split(corpus);
        assertTrue(units.size() >= 2);

        LlmPort llmPort = unitCorpus -> {
            String unitText = unitCorpus.documents().getFirst().content();
            return unitText.contains("Ada Lovelace")
                    ? new GraphExtraction(List.of(new Entity("Ada Lovelace", "person")), List.of())
                    : new GraphExtraction(List.of(), List.of());
        };
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();

        new ExtractEntitiesAndRelationships(llmPort, store).run(corpus);

        assertEquals(units.size(), store.persistedUnits.size());
        assertTrue(store.persistedEntities.contains(new Entity("Ada Lovelace", "Person")));
    }

    @org.junit.jupiter.api.Test
    void eachUnitIsPersistedBeforeTheNextUnitIsExtracted() {
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("long.txt", threeUnitText("End."))));
        int expectedUnits = TextUnitSplitter.split(corpus).size();
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();
        List<Integer> persistedUnitsAtCall = new ArrayList<>();
        LlmPort llmPort = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus ignored) {
                throw new AssertionError("the per-unit method must be used");
            }

            @Override
            public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
                assertEquals(EntityTypes.ALL, entityTypes);
                persistedUnitsAtCall.add(store.persistedUnits.size());
                return new GraphExtraction(List.of(new Entity("Unit " + unit.ordinal(), "Concept")), List.of());
            }
        };

        new ExtractEntitiesAndRelationships(llmPort, store).run(corpus);

        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < expectedUnits; i++) {
            expected.add(i);
        }
        assertEquals(expected, persistedUnitsAtCall);
        assertEquals(expectedUnits, store.persistCalls);
    }

    @org.junit.jupiter.api.Test
    void progressIsReportedPerUnitBeforeThatUnitsEntityAndRelationshipCallbacks() {
        Corpus corpus = new Corpus("c1", List.of(
                new UploadedDocument("a.txt", "Alice met Bob."),
                new UploadedDocument("b.txt", "Carol met Dave.")));
        LlmPort llmPort = unitCorpus -> {
            String[] words = unitCorpus.documents().getFirst().content().replace(".", "").split(" ");
            return new GraphExtraction(
                    List.of(new Entity(words[0], "Person"), new Entity(words[2], "Place")),
                    List.of(new Relationship(words[0], "person", "met", words[2], "")));
        };
        List<String> events = new ArrayList<>();

        new ExtractEntitiesAndRelationships(llmPort, new PerUnitRecordingGraphStorePort()).run(corpus,
                progress -> events.add("unit " + progress.index() + "/" + progress.total() + " " + progress.documentName()),
                entity -> events.add("entity " + entity.name() + ":" + entity.type()),
                relationship -> events.add("relationship " + relationship.sourceType() + "->" + relationship.targetType()));

        assertEquals(List.of(
                "unit 1/2 a.txt", "entity Alice:Person", "entity Bob:Concept", "relationship Person->Concept",
                "unit 2/2 b.txt", "entity Carol:Person", "entity Dave:Concept", "relationship Person->Concept"),
                events);
    }

    @org.junit.jupiter.api.Test
    void aFailingUnitStopsExtractionAndNamesTheDocumentAndPassage() {
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("long.txt", threeUnitText("End."))));
        assertTrue(TextUnitSplitter.split(corpus).size() >= 3);
        List<Integer> calledOrdinals = new ArrayList<>();
        LlmPort llmPort = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus ignored) {
                throw new AssertionError("unused");
            }

            @Override
            public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
                calledOrdinals.add(unit.ordinal());
                if (unit.ordinal() == 1) {
                    throw new RuntimeException("boom");
                }
                return new GraphExtraction(List.of(new Entity("Unit " + unit.ordinal(), "Concept")), List.of());
            }
        };
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ExtractEntitiesAndRelationships(llmPort, store).run(corpus));

        assertTrue(failure.getMessage().contains("long.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("passage 2"), failure.getMessage());
        assertEquals(List.of(0, 1), calledOrdinals);
        assertEquals(1, store.persistedUnits.size());
        assertEquals(List.of(new Entity("Unit 0", "Concept")), store.persistedEntities);
        assertFalse(calledOrdinals.contains(2));
    }

    @org.junit.jupiter.api.Test
    void extractMergesAllUnitsWithoutPersisting() {
        Corpus corpus = new Corpus("c1", List.of(
                new UploadedDocument("a.txt", "one"), new UploadedDocument("b.txt", "two")));
        LlmPort llmPort = unitCorpus -> new GraphExtraction(
                List.of(new Entity("Shared", "PERSON"), new Entity(unitCorpus.documents().getFirst().content(), null)),
                List.of(new Relationship("Shared", "Person", "knows", "Other", "Person")));
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();

        GraphExtraction merged = new ExtractEntitiesAndRelationships(llmPort, store).extract(corpus);

        assertEquals(List.of(new Entity("Shared", "Person"), new Entity("one", "Concept"), new Entity("two", "Concept")),
                merged.entities());
        assertEquals(1, merged.relationships().size());
        assertEquals(0, store.persistCalls);
    }

    private static final class PerUnitRecordingGraphStorePort implements GraphStorePort {
        private final List<TextUnit> persistedUnits = new ArrayList<>();
        private final List<Entity> persistedEntities = new ArrayList<>();
        private int persistCalls;

        @Override
        public void persistEntities(java.util.Collection<Entity> entities) {
            persistedEntities.addAll(entities);
        }

        @Override
        public void persistRelationships(java.util.Collection<Relationship> relationships) {
            // not asserted
        }

        @Override
        public void persist(String corpusId, GraphExtraction extraction) {
            persistCalls++;
            persistedEntities.addAll(extraction.entities());
        }

        @Override
        public void persistTextUnits(String corpusId, java.util.Collection<TextUnit> textUnits) {
            persistedUnits.addAll(textUnits);
        }
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
