package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.CommunityMembership;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.GraphExtraction;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.domain.RetrievalStep;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerDriftSearchTest {

    @Test
    void reportsNoAnswerWhenNoCommunitiesArePersistedYet() {
        DriftSearchAnswer result = new AnswerDriftSearch(new StubGraphStore(List.of(), List.of(), List.of()), stubLlmPort())
                .answer("What happened?");

        assertTrue(result.noAnswer());
        assertNull(result.answer());
        assertEquals(
                "DRIFT Search cannot run yet because this corpus has no Community summaries. Wait for the Community "
                        + "pass to finish, then try again.",
                result.reason());
        assertTrue(result.steps().isEmpty());
    }

    @Test
    void reportsTheDistinctNoAnswerShapeWhenTheCommunityPassYieldsNoViableSubQuestions() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Community("community-1", "This community centers on Baker Street and violin practice.")),
                List.of(),
                List.of());

        DriftSearchAnswer result = new AnswerDriftSearch(graphStore, stubLlmPort())
                .answer("zzqqxx nonsense gibberish flimflam");

        assertTrue(result.noAnswer());
        assertNull(result.answer());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("no sub-questions could be generated"));
        assertEquals(1, result.steps().size());
        assertEquals(RetrievalStep.Kind.COMMUNITY, result.steps().getFirst().kind());
    }

    @Test
    void synthesizesAGraphGroundedAnswerFromTheFirstMatchingSubQuestion() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Community("community-1", "This community centers on Sherlock Holmes and Irene Adler.")),
                List.of(
                        new Entity("Sherlock Holmes", "Person"),
                        new Entity("Irene Adler", "Person")),
                List.of(new Relationship("Sherlock Holmes", "Person", "INVESTIGATES", "Irene Adler", "Person")));

        DriftSearchAnswer result = new AnswerDriftSearch(graphStore, stubLlmPort())
                .answer("What connects Sherlock Holmes to Irene Adler?");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        assertTrue(result.answer().contains("DRIFT matched a relevant Community"));
        assertTrue(result.answer().contains("Sherlock Holmes investigates Irene Adler."));
        assertNull(result.reason());
        assertEquals(6, result.steps().size());
        assertEquals(RetrievalStep.Kind.COMMUNITY, result.steps().get(0).kind());
        assertEquals(RetrievalStep.Kind.SUB_QUESTION_SPAWNED, result.steps().get(1).kind());
        assertEquals(RetrievalStep.Kind.ENTITY, result.steps().get(2).kind());
        assertEquals(RetrievalStep.Kind.RELATIONSHIP, result.steps().get(3).kind());
        assertEquals(RetrievalStep.Kind.ENTITY, result.steps().get(4).kind());
        assertEquals(RetrievalStep.Kind.SYNTHESIS, result.steps().get(5).kind());
    }

    @Test
    void explainsWhenAMatchingCommunitySpawnsOnlyNoMatchSubQuestions() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Community("community-1", "This community centers on Professor Moriarty and networks.")),
                List.of(new Entity("Sherlock Holmes", "Person")),
                List.of());

        DriftSearchAnswer result = new AnswerDriftSearch(graphStore, stubLlmPort())
                .answer("What is Moriarty's network?");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        assertTrue(result.answer().contains("did not find a graph-grounded local match yet"));
        assertNull(result.reason());
        assertEquals(List.of(
                        RetrievalStep.Kind.COMMUNITY,
                        RetrievalStep.Kind.SUB_QUESTION_SPAWNED,
                        RetrievalStep.Kind.SYNTHESIS),
                result.steps().stream().map(RetrievalStep::kind).toList());
        assertEquals(result.answer(), result.steps().getLast().label());
    }

    @Test
    void treatsEntityOnlyLocalMatchesAsNoGraphGroundedHop() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Community("community-1", "This community centers on Sherlock Holmes.")),
                List.of(new Entity("Sherlock Holmes", "Person")),
                List.of());

        DriftSearchAnswer result = new AnswerDriftSearch(graphStore, stubLlmPort())
                .answer("Tell me about Sherlock Holmes");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        assertTrue(result.answer().contains("did not find a graph-grounded local match yet"));
        assertEquals(List.of(
                        RetrievalStep.Kind.COMMUNITY,
                        RetrievalStep.Kind.SUB_QUESTION_SPAWNED,
                        RetrievalStep.Kind.ENTITY,
                        RetrievalStep.Kind.SYNTHESIS),
                result.steps().stream().map(RetrievalStep::kind).toList());
    }

    @Test
    void keepsCommunityStepsBeforeEverySpawnedLocalSearchStep() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(
                        new Community("community-1", "This community centers on Sherlock Holmes and Irene Adler."),
                        new Community("community-2", "This community centers on Sherlock Holmes and Irene Adler.")),
                List.of(
                        new Entity("Sherlock Holmes", "Person"),
                        new Entity("Irene Adler", "Person")),
                List.of(new Relationship("Sherlock Holmes", "Person", "INVESTIGATES", "Irene Adler", "Person")));
        LlmPort llmPort = new LlmPort() {
            @Override
            public GraphExtraction extract(com.graphraglens.core.domain.Corpus corpus) {
                return new GraphExtraction(List.of(), List.of());
            }

            @Override
            public List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
                return List.of(
                        "Unmatched sub-question about Moriarty",
                        "What connects Sherlock Holmes to Irene Adler?");
            }
        };

        DriftSearchAnswer result = new AnswerDriftSearch(graphStore, llmPort)
                .answer("Tell me about Sherlock Holmes and Irene Adler");

        assertEquals(List.of(
                        RetrievalStep.Kind.COMMUNITY,
                        RetrievalStep.Kind.COMMUNITY,
                        RetrievalStep.Kind.SUB_QUESTION_SPAWNED,
                        RetrievalStep.Kind.SUB_QUESTION_SPAWNED,
                        RetrievalStep.Kind.ENTITY,
                        RetrievalStep.Kind.RELATIONSHIP,
                        RetrievalStep.Kind.ENTITY,
                        RetrievalStep.Kind.SYNTHESIS),
                result.steps().stream().map(RetrievalStep::kind).toList());
    }

    @Test
    void tracesEveryBranchButSynthesizesFromTheFirstGroundedHop() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(
                        new Community("community-b", "This community centers on Sherlock Holmes and Irene Adler."),
                        new Community("community-a", "This community centers on Dr Watson and Mary Morstan.")),
                List.of(
                        new Entity("Dr Watson", "Person"),
                        new Entity("Mary Morstan", "Person"),
                        new Entity("Sherlock Holmes", "Person"),
                        new Entity("Irene Adler", "Person")),
                List.of(
                        new Relationship("Dr Watson", "Person", "MARRIED", "Mary Morstan", "Person"),
                        new Relationship("Sherlock Holmes", "Person", "INVESTIGATES", "Irene Adler", "Person")));
        String[] firstSpawnedCandidateId = new String[1];
        LlmPort llmPort = new LlmPort() {
            @Override
            public GraphExtraction extract(com.graphraglens.core.domain.Corpus corpus) {
                return new GraphExtraction(List.of(), List.of());
            }

            @Override
            public List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
                firstSpawnedCandidateId[0] = communities.iterator().next().id();
                return List.of(
                        "What did Dr Watson do with Mary Morstan?",
                        "Unmatched sub-question about Moriarty");
            }
        };

        DriftSearchAnswer result = new AnswerDriftSearch(graphStore, llmPort)
                .answer("Tell me about Sherlock Holmes and Irene Adler");

        assertTrue(result.answer().contains("Dr Watson married Mary Morstan."));
        assertEquals(List.of(
                        RetrievalStep.Kind.COMMUNITY,
                        RetrievalStep.Kind.COMMUNITY,
                        RetrievalStep.Kind.SUB_QUESTION_SPAWNED,
                        RetrievalStep.Kind.ENTITY,
                        RetrievalStep.Kind.RELATIONSHIP,
                        RetrievalStep.Kind.ENTITY,
                        RetrievalStep.Kind.SUB_QUESTION_SPAWNED,
                        RetrievalStep.Kind.SYNTHESIS),
                result.steps().stream().map(RetrievalStep::kind).toList());
        assertEquals(firstSpawnedCandidateId[0], result.steps().getLast().identifier());
        assertEquals(result.answer(), result.steps().getLast().label());
    }

    @Test
    void recordsSpawnedSubQuestionWithItsParentCommunityIdAndExactLabel() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Community("community-1", "This community centers on Sherlock Holmes and Irene Adler.")),
                List.of(
                        new Entity("Sherlock Holmes", "Person"),
                        new Entity("Irene Adler", "Person")),
                List.of(new Relationship("Sherlock Holmes", "Person", "INVESTIGATES", "Irene Adler", "Person")));
        String subQuestion = "What connects Sherlock Holmes to Irene Adler? Community summary: This community centers on Sherlock Holmes and Irene Adler.";

        DriftSearchAnswer result = new AnswerDriftSearch(graphStore, stubLlmPort())
                .answer("What connects Sherlock Holmes to Irene Adler?");

        RetrievalStep spawnedStep = result.steps().get(1);
        assertEquals(RetrievalStep.Kind.SUB_QUESTION_SPAWNED, spawnedStep.kind());
        assertEquals("community-1", spawnedStep.identifier());
        assertEquals(subQuestion, spawnedStep.label());
        assertEquals("community-1", result.steps().getLast().identifier());
        assertEquals(result.answer(), result.steps().getLast().label());
    }

    @Test
    void usesCommunitySummaryTokensToDeriveAnswerableSubQuestions() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(new Community("community-1", "This community centers on Irene Adler and disguises.")),
                List.of(
                        new Entity("Irene Adler", "Person"),
                        new Entity("Sherlock Holmes", "Person")),
                List.of(new Relationship("Irene Adler", "Person", "OUTWITS", "Sherlock Holmes", "Person")));

        DriftSearchAnswer result = new AnswerDriftSearch(graphStore, stubLlmPort())
                .answer("Tell me about disguises");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        assertTrue(result.answer().contains("Irene Adler outwits Sherlock Holmes."));
    }

    @Test
    void corpusScopedAnswerReadsOnlyTheRequestedCorpusGraph() {
        ScopedStubGraphStore graphStore = new ScopedStubGraphStore(
                Map.of(
                        "corpus-a", List.of(new Community("community-a", "This community centers on Irene Adler.")),
                        "corpus-b", List.of(new Community("community-b", "This community centers on Moriarty."))),
                Map.of(
                        "corpus-a", List.of(new Entity("Irene Adler", "Person")),
                        "corpus-b", List.of(new Entity("Professor Moriarty", "Person"))),
                Map.of(
                        "corpus-a", List.of(),
                        "corpus-b", List.of()));

        DriftSearchAnswer result = new AnswerDriftSearch(graphStore, stubLlmPort())
                .answer("Tell me about Irene Adler", "corpus-a");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        assertFalse(result.answer().contains("Moriarty"));
    }

    @Test
    void keepsLocalTraversalScopedToTheRequestedCorpus() {
        ScopedStubGraphStore graphStore = new ScopedStubGraphStore(
                Map.of(
                        "corpus-a", List.of(new Community("community-a", "This community centers on Irene Adler and disguises.")),
                        "corpus-b", List.of(new Community("community-b", "This community centers on Irene Adler and disguises."))),
                Map.of(
                        "corpus-a", List.of(),
                        "corpus-b", List.of(
                                new Entity("Irene Adler", "Person"),
                                new Entity("Sherlock Holmes", "Person"))),
                Map.of(
                        "corpus-a", List.of(),
                        "corpus-b", List.of(new Relationship(
                                "Irene Adler", "Person", "OUTWITS", "Sherlock Holmes", "Person"))));

        DriftSearchAnswer result = new AnswerDriftSearch(graphStore, stubLlmPort())
                .answer("Tell me about disguises", "corpus-a");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        assertTrue(result.answer().contains("did not find a graph-grounded local match yet"));
    }

    @Test
    void ordersTiedCandidatesLexicographicallyByCommunityIdRegardlessOfStoreOrder() {
        StubGraphStore graphStore = new StubGraphStore(
                List.of(
                        new Community("community-b", "This community centers on Dr Watson, a detectives companion."),
                        new Community("community-a", "This community centers on Sherlock Holmes, a detectives icon.")),
                List.of(
                        new Entity("Dr Watson", "Person"),
                        new Entity("Mary Morstan", "Person"),
                        new Entity("Sherlock Holmes", "Person"),
                        new Entity("Irene Adler", "Person")),
                List.of(
                        new Relationship("Dr Watson", "Person", "MARRIED", "Mary Morstan", "Person"),
                        new Relationship("Sherlock Holmes", "Person", "INVESTIGATES", "Irene Adler", "Person")));

        DriftSearchAnswer result = new AnswerDriftSearch(graphStore, stubLlmPort())
                .answer("Tell me about detectives");

        assertFalse(result.noAnswer());
        assertNotNull(result.answer());
        // Both communities tie on the "detectives" token, but the default
        // LlmPort receives tied candidates already sorted lexicographically
        // by community id ("community-a" before "community-b") regardless of
        // the store's own iteration order, so the Sherlock Holmes hop wins.
        assertTrue(result.answer().contains("Sherlock Holmes investigates Irene Adler."));
    }

    private static LlmPort stubLlmPort() {
        return corpus -> new GraphExtraction(List.of(), List.of());
    }

    private static class StubGraphStore implements GraphStorePort {
        private final List<Community> storedCommunities;
        private final List<Entity> storedEntities;
        private final List<Relationship> storedRelationships;

        private StubGraphStore(
                List<Community> storedCommunities,
                List<Entity> storedEntities,
                List<Relationship> storedRelationships) {
            this.storedCommunities = storedCommunities;
            this.storedEntities = storedEntities;
            this.storedRelationships = storedRelationships;
        }

        @Override
        public Collection<Community> communities() {
            return storedCommunities;
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
        }

        @Override
        public void persistRelationships(Collection<Relationship> relationships) {
        }

        @Override
        public void persistCommunities(Collection<Community> communities) {
        }

        @Override
        public void persistCommunityMemberships(Collection<CommunityMembership> memberships) {
        }
    }

    private static final class ScopedStubGraphStore extends StubGraphStore {
        private final Map<String, List<Community>> communitiesByCorpus;
        private final Map<String, List<Entity>> entitiesByCorpus;
        private final Map<String, List<Relationship>> relationshipsByCorpus;

        private ScopedStubGraphStore(
                Map<String, List<Community>> communitiesByCorpus,
                Map<String, List<Entity>> entitiesByCorpus,
                Map<String, List<Relationship>> relationshipsByCorpus) {
            super(List.of(), List.of(), List.of());
            this.communitiesByCorpus = new LinkedHashMap<>(communitiesByCorpus);
            this.entitiesByCorpus = new LinkedHashMap<>(entitiesByCorpus);
            this.relationshipsByCorpus = new LinkedHashMap<>(relationshipsByCorpus);
        }

        @Override
        public Collection<Community> communities(String corpusId) {
            return communitiesByCorpus.getOrDefault(corpusId, List.of());
        }

        @Override
        public Collection<Entity> entities(String corpusId) {
            return entitiesByCorpus.getOrDefault(corpusId, List.of());
        }

        @Override
        public Collection<Relationship> relationships(String corpusId) {
            return relationshipsByCorpus.getOrDefault(corpusId, List.of());
        }
    }
}
