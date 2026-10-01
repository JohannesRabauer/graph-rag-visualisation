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
        assertTrue(store.persistedEntities.stream()
                .anyMatch(entity -> entity.name().equals("Ada Lovelace") && entity.type().equals("Person")));
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
        assertEquals("Unit 0", store.persistedEntities.getFirst().name());
        assertEquals("Concept", store.persistedEntities.getFirst().type());
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

        assertEquals(List.of("Shared", "one", "two"), merged.entities().stream().map(Entity::name).toList());
        assertEquals(2, merged.entities().getFirst().sourceTextUnitIds().size());
        assertEquals(1, merged.relationships().size());
        assertEquals(2, merged.relationships().getFirst().weight());
        assertEquals(0, store.persistCalls);
    }

    @org.junit.jupiter.api.Test
    void runStampsUnitIdsAndMergesRepeatedEntitiesAndRelationshipsBeforePersistingAndCallback() {
        Corpus corpus = new Corpus("c1", List.of(
                new UploadedDocument("a.txt", "Ada Lovelace wrote about Engine."),
                new UploadedDocument("b.txt", "Ada Lovelace wrote about Engine.")));
        List<TextUnit> units = TextUnitSplitter.split(corpus);
        LlmPort llmPort = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus ignored) {
                throw new AssertionError("unused");
            }

            @Override
            public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
                return new GraphExtraction(
                        List.of(new Entity("Ada Lovelace", "person",
                                unit.documentName().equals("a.txt") ? "A mathematician." : "She wrote notes.",
                                List.of("llm-supplied-id"))),
                        List.of(new Relationship("Ada Lovelace", "person", "wrote_about", "Engine", "concept",
                                "Ada wrote about Engine.", List.of("llm-supplied-id"), 99)));
            }
        };
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();
        List<Entity> entityCallbacks = new ArrayList<>();
        List<Relationship> relationshipCallbacks = new ArrayList<>();

        new ExtractEntitiesAndRelationships(llmPort, store).run(corpus, progress -> {
        }, entityCallbacks::add, relationshipCallbacks::add);

        Entity persistedAda = store.persistedEntities.getLast();
        Relationship persistedRelationship = store.persistedRelationships.getLast();
        assertEquals("Person", persistedAda.type());
        assertEquals("A mathematician. She wrote notes.", persistedAda.description());
        assertEquals(units.stream().map(TextUnit::id).toList(), persistedAda.sourceTextUnitIds());
        assertEquals(persistedAda, entityCallbacks.getLast());
        assertEquals(units.stream().map(TextUnit::id).toList(), persistedRelationship.sourceTextUnitIds());
        assertEquals(2, persistedRelationship.weight());
        assertEquals(persistedRelationship, relationshipCallbacks.getLast());
    }

    @org.junit.jupiter.api.Test
    void missingDescriptionsDefaultToBlankWhenStamped() {
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("a.txt", "Ada Lovelace.")));
        LlmPort llmPort = unitCorpus -> new GraphExtraction(List.of(new Entity("Ada Lovelace", "Person")), List.of());
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();

        new ExtractEntitiesAndRelationships(llmPort, store).run(corpus);

        assertEquals("", store.persistedEntities.getFirst().description());
        assertEquals(TextUnitSplitter.split(corpus).getFirst().id(), store.persistedEntities.getFirst().sourceTextUnitIds().getFirst());
    }

    @org.junit.jupiter.api.Test
    void firstSightingDescriptionIsCappedAndDuplicateRelationshipsInOneUnitPersistOnce() {
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("a.txt", "Ada Lovelace wrote about Engine.")));
        String overlong = "A".repeat(1_200);
        Relationship wrote = new Relationship("Ada Lovelace", "Person", "wrote_about", "Engine", "Concept",
                overlong, List.of(), 1);
        LlmPort llmPort = unitCorpus -> new GraphExtraction(
                List.of(new Entity("Ada Lovelace", "Person", overlong, List.of())), List.of(wrote, wrote));
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();
        List<Relationship> relationshipCallbacks = new ArrayList<>();

        new ExtractEntitiesAndRelationships(llmPort, store).run(corpus, null, relationshipCallbacks::add);

        assertEquals(1_000, store.persistedEntities.getFirst().description().length());
        assertEquals(1, store.persistedRelationships.size());
        assertEquals(1_000, store.persistedRelationships.getFirst().description().length());
        assertEquals(1, store.persistedRelationships.getFirst().weight());
        assertEquals(1, relationshipCallbacks.size());
    }

    @org.junit.jupiter.api.Test
    void resolvesCasePunctuationVariantsBeforePersisting() {
        Corpus corpus = new Corpus("c1", List.of(
                new UploadedDocument("a.txt", "OpenAI appears."),
                new UploadedDocument("b.txt", "openai appears again.")));
        LlmPort llmPort = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus ignored) {
                throw new AssertionError("unused");
            }

            @Override
            public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
                return unit.documentName().equals("a.txt")
                        ? new GraphExtraction(List.of(new Entity("OpenAI", "Organization")), List.of())
                        : new GraphExtraction(List.of(new Entity("openai.", "Organization")), List.of());
            }
        };
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();
        EntityResolver resolver = new EntityResolver();

        GraphExtraction extracted = new ExtractEntitiesAndRelationships(llmPort, store).extract(corpus);
        new ExtractEntitiesAndRelationships(llmPort, store).run(corpus);

        assertEquals(List.of("OpenAI"), extracted.entities().stream().map(Entity::name).toList());
        assertTrue(store.persistedEntities.stream()
                .anyMatch(entity -> entity.name().equals("OpenAI") && entity.sourceTextUnitIds().size() == 2));
        assertEquals(EntityResolver.nameKey("Ada  Lovelace"), EntityResolver.nameKey("\uFF21\uFF24\uFF21 Lovelace"));
        assertEquals("Ada  Lovelace", resolver.resolve(new Entity("Ada  Lovelace", "Person")).entity().name());
        assertEquals("Ada  Lovelace", resolver.resolve(new Entity("\uFF21\uFF24\uFF21 Lovelace", "Person")).entity().name());
        assertEquals("...", EntityResolver.nameKey("..."));
    }

    @org.junit.jupiter.api.Test
    void typeConflictsKeepMajorityTypeAndTieKeepsEarliest() {
        Corpus corpus = new Corpus("c1", List.of(
                new UploadedDocument("a.txt", "Paris one."),
                new UploadedDocument("b.txt", "Paris two.")));
        LlmPort llmPort = unit -> unit.documents().getFirst().filename().equals("a.txt")
                ? new GraphExtraction(List.of(new Entity("Paris", "Location")), List.of())
                : new GraphExtraction(List.of(new Entity("Paris", "Person")), List.of());
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();

        new ExtractEntitiesAndRelationships(llmPort, store).run(corpus);

        assertEquals("Location", store.persistedEntities.getLast().type());
        assertEquals(List.of(), store.retyped);
    }

    @org.junit.jupiter.api.Test
    void typeFlipRekeysStoredEntityRelationshipsAndCallbacksAfterPersistence() {
        Corpus corpus = new Corpus("c1", List.of(
                new UploadedDocument("a.txt", "Jaguar animal."),
                new UploadedDocument("b.txt", "Jaguar org."),
                new UploadedDocument("c.txt", "Jaguar org again.")));
        LlmPort llmPort = unit -> {
            String name = unit.documents().getFirst().filename();
            String type = name.equals("a.txt") ? "Animal" : "Organization";
            return new GraphExtraction(List.of(new Entity("Jaguar", type)),
                    List.of(new Relationship("Jaguar", type, "appears_in", "Market", "Concept"),
                            new Relationship("Market", "Concept", "features", "Jaguar", type)));
        };
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();
        List<String> events = new ArrayList<>();

        new ExtractEntitiesAndRelationships(llmPort, store).run(corpus,
                ignored -> events.add("unit persisted=" + store.persistCalls),
                entity -> events.add("entity " + entity.normalizedIdentity()),
                relationship -> events.add("relationship " + relationship.sourceType()),
                (previous, entity) -> events.add("retyped persisted=" + store.persistCalls + " " + previous
                        + "->" + entity.normalizedIdentity()));

        assertEquals(List.of("jaguar::concept->jaguar::organization"), store.retyped);
        assertEquals("Organization", store.persistedEntities.getLast().type());
        Relationship outgoing = store.persistedRelationships.stream()
                .filter(relationship -> relationship.type().equals("appears_in")).reduce((a, b) -> b).orElseThrow();
        Relationship incoming = store.persistedRelationships.stream()
                .filter(relationship -> relationship.type().equals("features")).reduce((a, b) -> b).orElseThrow();
        assertEquals("Organization", outgoing.sourceType());
        assertEquals("Organization", incoming.targetType());
        assertEquals(3, outgoing.weight());
        assertEquals(3, outgoing.sourceTextUnitIds().size());
        assertTrue(events.contains("retyped persisted=3 jaguar::concept->jaguar::organization"));
        assertTrue(events.indexOf("retyped persisted=3 jaguar::concept->jaguar::organization")
                < events.lastIndexOf("entity jaguar::organization"));
    }

    @org.junit.jupiter.api.Test
    void typeFlipWithinUnitDoesNotPersistOrEmitStalePreviousIdentity() {
        Corpus corpus = new Corpus("c1", List.of(
                new UploadedDocument("a.txt", "Jaguar animal."),
                new UploadedDocument("b.txt", "Jaguar org. jaguar. org.")));
        LlmPort llmPort = unit -> unit.documents().getFirst().filename().equals("a.txt")
                ? new GraphExtraction(List.of(new Entity("Jaguar", "Animal")), List.of())
                : new GraphExtraction(List.of(new Entity("Jaguar", "Organization"),
                        new Entity("jaguar.", "Organization")), List.of());
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();
        List<Integer> persistedSizeAfterUnit = new ArrayList<>();
        List<String> entityEvents = new ArrayList<>();

        new ExtractEntitiesAndRelationships(llmPort, store).run(corpus,
                ignored -> {
                    persistedSizeAfterUnit.add(store.persistedEntities.size());
                    entityEvents.add("unit");
                },
                entity -> entityEvents.add(entity.normalizedIdentity()),
                relationship -> { },
                (previous, entity) -> { });

        assertEquals(List.of("jaguar::concept->jaguar::organization"), store.retyped);
        List<String> secondUnitPersisted = store.persistedEntities
                .subList(persistedSizeAfterUnit.getFirst(), store.persistedEntities.size()).stream()
                .map(Entity::normalizedIdentity).toList();
        assertEquals(List.of("jaguar::organization"), secondUnitPersisted);
        List<String> secondUnitEmitted = entityEvents.subList(entityEvents.lastIndexOf("unit") + 1, entityEvents.size());
        assertEquals(List.of("jaguar::organization"), secondUnitEmitted);
    }

    @org.junit.jupiter.api.Test
    void relationshipEndpointsResolveToKnownEntitiesAndEndpointOnlyNamesCanLaterFlipType() {
        Corpus corpus = new Corpus("c1", List.of(
                new UploadedDocument("a.txt", "OpenAI and Acme relationship."),
                new UploadedDocument("b.txt", "Acme entity.")));
        LlmPort llmPort = unit -> unit.documents().getFirst().filename().equals("a.txt")
                ? new GraphExtraction(
                        List.of(new Entity("OpenAI", "Organization")),
                        List.of(new Relationship("openai,", "Product", "founded_by", "Sam Altman", "Person"),
                                new Relationship("Acme", "Person", "partnered_with", "OpenAI", "Organization")))
                : new GraphExtraction(List.of(new Entity("Acme", "Organization")), List.of());
        PerUnitRecordingGraphStorePort store = new PerUnitRecordingGraphStorePort();

        new ExtractEntitiesAndRelationships(llmPort, store).run(corpus);

        Relationship foundedBy = store.persistedRelationships.stream()
                .filter(relationship -> relationship.type().equals("founded_by"))
                .findFirst().orElseThrow();
        Relationship acme = store.persistedRelationships.getLast();
        assertEquals("OpenAI", foundedBy.source());
        assertEquals("Organization", foundedBy.sourceType());
        assertEquals(List.of("acme::person->acme::organization"), store.retyped);
        assertEquals("Organization", acme.sourceType());
    }

    private static final class PerUnitRecordingGraphStorePort implements GraphStorePort {
        private final List<TextUnit> persistedUnits = new ArrayList<>();
        private final List<Entity> persistedEntities = new ArrayList<>();
        private final List<Relationship> persistedRelationships = new ArrayList<>();
        private final List<String> retyped = new ArrayList<>();
        private int persistCalls;

        @Override
        public void persistEntities(java.util.Collection<Entity> entities) {
            persistedEntities.addAll(entities);
        }

        @Override
        public void persistRelationships(java.util.Collection<Relationship> relationships) {
            persistedRelationships.addAll(relationships);
        }

        @Override
        public void persist(String corpusId, GraphExtraction extraction) {
            persistCalls++;
            persistedEntities.addAll(extraction.entities());
            persistedRelationships.addAll(extraction.relationships());
        }

        @Override
        public void persistTextUnits(String corpusId, java.util.Collection<TextUnit> textUnits) {
            persistedUnits.addAll(textUnits);
        }

        @Override
        public void retypeEntity(String corpusId, String previousIdentity, Entity resolved) {
            retyped.add(previousIdentity + "->" + resolved.normalizedIdentity());
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
