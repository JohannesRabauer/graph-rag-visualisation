package io.graphrag.core.usecase;

import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.port.GraphStorePort;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerLocalSearchTest {

    @Test
    void returnsAPlainLanguageNoMatchAnswerWithAZeroStepTraceWhenNoEntityScores() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Entity("Irene Adler", "Person")),
                List.of());

        LocalSearchAnswer result = new AnswerLocalSearch(graphStore).answer("zzqqxx nonexistent gibberish", null);

        assertNotNull(result.answer());
        assertFalse(result.answer().isBlank());
        assertTrue(result.steps().isEmpty());
    }

    @Test
    void reportsJustTheEntityWhenNoneOfItsRelationshipsMatchTheQuestion() {
        // The question matches the seed only via its *type* ("person"),
        // which the relationship text (source/type/target names, not
        // entity types) never contains — so the seed is found but its one
        // relationship correctly scores zero and isn't traversed.
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Entity("Irene Adler", "Person"), new Entity("Professor Moriarty", "Person")),
                List.of(new Relationship("Irene Adler", "Person", "employs", "Professor Moriarty", "Person")));

        LocalSearchAnswer result = new AnswerLocalSearch(graphStore).answer("Tell me about any person here", null);

        assertTrue(result.answer().contains("Irene Adler"));
        assertEquals(1, result.steps().size());
        assertEquals(RetrievalStep.Kind.ENTITY, result.steps().get(0).kind());
    }

    @Test
    void walksOneHopFromTheSeedEntityWhenARelationshipMatches() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Entity("Irene Adler", "Person"), new Entity("Sherlock Holmes", "Person")),
                List.of(new Relationship("Irene Adler", "Person", "outwitted", "Sherlock Holmes", "Person")));

        LocalSearchAnswer result = new AnswerLocalSearch(graphStore)
                .answer("How did Irene Adler outwit Sherlock Holmes?", null);

        assertEquals(3, result.steps().size());
        assertEquals(RetrievalStep.Kind.ENTITY, result.steps().get(0).kind());
        assertEquals("irene adler::person", result.steps().get(0).identifier());
        assertEquals(RetrievalStep.Kind.RELATIONSHIP, result.steps().get(1).kind());
        assertEquals(RetrievalStep.Kind.ENTITY, result.steps().get(2).kind());
        assertEquals("sherlock holmes::person", result.steps().get(2).identifier());
        assertTrue(result.answer().contains("Irene Adler"));
        assertTrue(result.answer().contains("Sherlock Holmes"));
    }

    @Test
    void relationshipStepIdentifierMatchesTheRenderedEdgeIdConventionForReplay() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Entity("Irene Adler", "Person"), new Entity("Sherlock Holmes", "Person")),
                List.of(new Relationship("Irene Adler", "Person", "outwitted", "Sherlock Holmes", "Person")));

        LocalSearchAnswer result = new AnswerLocalSearch(graphStore)
                .answer("How did Irene Adler outwit Sherlock Holmes?", null);

        // Must match graph-canvas.js's addRelationship() edge-id convention
        // exactly: sourceIdentity -> type -> targetIdentity — otherwise
        // Replay can never resolve and highlight the real rendered edge.
        assertEquals("irene adler::person->outwitted->sherlock holmes::person",
                result.steps().get(1).identifier());
    }

    @Test
    void onlyWalksRelationshipsThatActuallyTouchTheSeedEntity() {
        // "Professor Moriarty" scores highest as the seed. The higher-
        // scoring "outwitted" relationship belongs to Irene Adler/Holmes,
        // not Moriarty — it must never be picked just because it scores
        // well against the question; only Moriarty's own relationship
        // (to Holmes) is eligible, even though it scores lower.
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Entity("Professor Moriarty", "Person"),
                        new Entity("Irene Adler", "Person"),
                        new Entity("Sherlock Holmes", "Person")),
                List.of(
                        new Relationship("Irene Adler", "Person", "outwitted", "Sherlock Holmes", "Person"),
                        new Relationship("Professor Moriarty", "Person", "rival_of", "Sherlock Holmes", "Person")));

        LocalSearchAnswer result = new AnswerLocalSearch(graphStore)
                .answer("How did Professor Moriarty outwit anyone?", null);

        assertEquals(3, result.steps().size());
        assertEquals("professor moriarty::person", result.steps().get(0).identifier());
        assertEquals(RetrievalStep.Kind.RELATIONSHIP, result.steps().get(1).kind());
        assertTrue(result.steps().get(1).identifier().startsWith("professor moriarty::person->rival_of->"));
        assertFalse(result.answer().contains("Irene Adler"));
        assertTrue(result.answer().contains("Professor Moriarty"));
    }

    @Test
    void aMinorTypoInTheQuestionStillFindsTheEntityInsteadOfFallingThroughToNoMatch() {
        // Story 9.2: "Shelock" (missing an 'r') must still resolve to the
        // "Sherlock Holmes" Entity via KeywordMatcher's fuzzy fallback,
        // rather than a presenter hitting a "no answer found" on a minor
        // phrasing slip mid-demo.
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Entity("Sherlock Holmes", "Person")),
                List.of());

        LocalSearchAnswer result = new AnswerLocalSearch(graphStore).answer("Where is Shelock right now?", null);

        assertEquals(1, result.steps().size());
        assertTrue(result.answer().contains("Sherlock Holmes"));
    }

    @Test
    void corpusScopedAnswerReadsOnlyTheRequestedCorpusGraph() {
        ScopedStubGraphStore graphStore = new ScopedStubGraphStore(Map.of(
                "corpus-a", List.of(new Entity("Irene Adler", "Person")),
                "corpus-b", List.of(new Entity("Professor Moriarty", "Person"))));

        LocalSearchAnswer result = new AnswerLocalSearch(graphStore).answer("Who is Moriarty?", "corpus-a");

        assertFalse(result.answer().contains("Moriarty"));
    }

    private static class StubGraphStore implements GraphStorePort {
        private final List<Entity> storedEntities;
        private final List<Relationship> storedRelationships;

        private StubGraphStore(List<Entity> storedEntities, List<Relationship> storedRelationships) {
            this.storedEntities = storedEntities;
            this.storedRelationships = storedRelationships;
        }

        @Override
        public Collection<Entity> entities() {
            return storedEntities;
        }

        @Override
        public Collection<Relationship> relationships() {
            return storedRelationships;
        }

        @Override
        public void persistEntities(Collection<Entity> entities) {
            // not exercised by this use case
        }

        @Override
        public void persistRelationships(Collection<Relationship> relationships) {
            // not exercised by this use case
        }
    }

    private static final class ScopedStubGraphStore extends StubGraphStore {
        private final Map<String, List<Entity>> entitiesByCorpus;

        private ScopedStubGraphStore(Map<String, List<Entity>> entitiesByCorpus) {
            super(List.of(), List.of());
            this.entitiesByCorpus = new LinkedHashMap<>(entitiesByCorpus);
        }

        @Override
        public Collection<Entity> entities(String corpusId) {
            return entitiesByCorpus.getOrDefault(corpusId, List.of());
        }
    }
}
