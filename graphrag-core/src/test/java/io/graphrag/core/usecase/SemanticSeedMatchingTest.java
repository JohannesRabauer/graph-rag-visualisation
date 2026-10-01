package io.graphrag.core.usecase;

import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.port.LlmPort;
import io.graphrag.core.usecase.SemanticTestFixtures.FakeEmbeddingPort;
import io.graphrag.core.usecase.SemanticTestFixtures.FakeGraphStore;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 15.1: Local, Global and DRIFT Search seed by meaning when a semantic
 * embedding model is configured, and keep today's keyword behavior otherwise.
 */
class SemanticSeedMatchingTest {

    private static final String CORPUS_ID = "corpus-a";
    private static final String OTHER_CORPUS_ID = "corpus-b";
    private static final Corpus CORPUS = new Corpus(CORPUS_ID, List.of());
    private static final float[] QUESTION_VECTOR = {1, 0, 0, 0};

    /** Five texts with strictly decreasing cosine similarity to {@link #QUESTION_VECTOR}. */
    private static final float[][] DESCENDING = {
            {1, 0.1f, 0, 0},
            {1, 0.5f, 0, 0},
            {1, 1, 0, 0},
            {0.2f, 1, 0, 0},
            {0, 1, 0, 0},
    };

    private static List<RetrievalStep.Kind> kinds(List<RetrievalStep> steps) {
        return steps.stream().map(RetrievalStep::kind).toList();
    }

    private static List<String> identifiers(List<RetrievalStep> steps) {
        return steps.stream().map(RetrievalStep::identifier).toList();
    }

    @Nested
    class Local {

        @Test
        void findsAnEntityWhoseDescriptionMatchesAQuestionWithNoWordOverlap() {
            FakeGraphStore store = new FakeGraphStore().entities(CORPUS_ID,
                    new Entity("Ada Lovelace", "Person", "wrote the first computer program", List.of()),
                    new Entity("Charles Babbage", "Person", "designed the difference engine", List.of()));
            FakeEmbeddingPort port = new FakeEmbeddingPort()
                    .map("Ada Lovelace: wrote the first computer program", 0.9f, 0.1f, 0, 0)
                    .map("Charles Babbage: designed the difference engine", 0, 0, 1, 0)
                    .map("who invented software?", QUESTION_VECTOR);
            new EmbedGraphElements(store, port).run(CORPUS);

            LocalSearchAnswer keyword = new AnswerLocalSearch(store).answer("who invented software?", CORPUS_ID);
            LocalSearchAnswer semantic = new AnswerLocalSearch(store, port).answer("who invented software?", CORPUS_ID);

            assertEquals(LocalSearchAnswer.noMatch(), keyword);
            assertEquals(new RetrievalStep(RetrievalStep.Kind.ENTITY, "ada lovelace::person", "Ada Lovelace"),
                    semantic.steps().getFirst());
            assertTrue(semantic.answer().contains("Ada Lovelace"));
        }

        @Test
        void recordsTheTopThreeEntitiesInDescendingSimilarity() {
            FakeGraphStore store = new FakeGraphStore();
            FakeEmbeddingPort port = new FakeEmbeddingPort().map("question", QUESTION_VECTOR);
            // Inserted out of similarity order, to prove the ordering comes from similarity.
            for (int i : new int[] {3, 0, 4, 2, 1}) {
                store.entities(CORPUS_ID, new Entity("Entity " + i, "Thing", "d" + i, List.of()));
                port.map("Entity " + i + ": d" + i, DESCENDING[i]);
            }
            new EmbedGraphElements(store, port).run(CORPUS);

            LocalSearchAnswer result = new AnswerLocalSearch(store, port).answer("question", CORPUS_ID);

            assertEquals(List.of("entity 0::thing", "entity 1::thing", "entity 2::thing"), identifiers(result.steps()));
            assertEquals(List.of(RetrievalStep.Kind.ENTITY, RetrievalStep.Kind.ENTITY, RetrievalStep.Kind.ENTITY),
                    kinds(result.steps()));
            assertEquals("In this corpus graph, the closest local match is Entity 0 (Thing).", result.answer());
        }

        @Test
        void hopsOutwardFromTheFirstSeedAfterTheSeedSteps() {
            FakeGraphStore store = new FakeGraphStore()
                    .entities(CORPUS_ID,
                            new Entity("Ada Lovelace", "Person", "wrote the first computer program", List.of()),
                            new Entity("Charles Babbage", "Person", "designed the difference engine", List.of()))
                    .relationships(CORPUS_ID,
                            new Relationship("Ada Lovelace", "Person", "COLLABORATED_WITH", "Charles Babbage", "Person"));
            FakeEmbeddingPort port = new FakeEmbeddingPort()
                    .map("Ada Lovelace: wrote the first computer program", 1, 0, 0, 0)
                    .map("Charles Babbage: designed the difference engine", 0, 1, 0, 0)
                    .map("who collaborated on early software?", QUESTION_VECTOR);
            new EmbedGraphElements(store, port).run(CORPUS);

            LocalSearchAnswer result = new AnswerLocalSearch(store, port)
                    .answer("who collaborated on early software?", CORPUS_ID);

            assertEquals(List.of(RetrievalStep.Kind.ENTITY, RetrievalStep.Kind.ENTITY,
                    RetrievalStep.Kind.RELATIONSHIP, RetrievalStep.Kind.ENTITY), kinds(result.steps()));
            assertEquals(List.of("ada lovelace::person", "charles babbage::person",
                    "ada lovelace::person->COLLABORATED_WITH->charles babbage::person", "charles babbage::person"),
                    identifiers(result.steps()));
            assertEquals("In this corpus graph, Ada Lovelace collaborated with Charles Babbage.", result.answer());
        }

        @Test
        void neverReturnsEntitiesOfAnotherCorpus() {
            FakeGraphStore store = new FakeGraphStore()
                    .entities(CORPUS_ID, new Entity("Ada Lovelace", "Person", "far", List.of()))
                    .entities(OTHER_CORPUS_ID, new Entity("Closer", "Person", "near", List.of()));
            FakeEmbeddingPort port = new FakeEmbeddingPort()
                    .map("Ada Lovelace: far", 0, 1, 0, 0)
                    .map("Closer: near", QUESTION_VECTOR)
                    .map("question", QUESTION_VECTOR);
            new EmbedGraphElements(store, port).run(CORPUS);
            new EmbedGraphElements(store, port).run(new Corpus(OTHER_CORPUS_ID, List.of()));

            LocalSearchAnswer result = new AnswerLocalSearch(store, port).answer("question", CORPUS_ID);

            assertEquals(List.of("ada lovelace::person"), identifiers(result.steps()));
        }

        @Test
        void fallsBackToKeywordsForACorpusWithoutEmbeddings() {
            FakeGraphStore store = keywordStore();
            FakeEmbeddingPort port = new FakeEmbeddingPort();

            assertEquals(new AnswerLocalSearch(store).answer("What does Sherlock investigate?", CORPUS_ID),
                    new AnswerLocalSearch(store, port).answer("What does Sherlock investigate?", CORPUS_ID));
        }

        @Test
        void aNonSemanticPortBehavesExactlyLikeNoPortAndEmbedsNothing() {
            FakeGraphStore store = keywordStore();
            FakeEmbeddingPort stub = new FakeEmbeddingPort(false);
            new EmbedGraphElements(store, stub).run(CORPUS);

            LocalSearchAnswer expected = new AnswerLocalSearch(store).answer("What does Sherlock investigate?", CORPUS_ID);
            assertEquals(expected, new AnswerLocalSearch(store, stub).answer("What does Sherlock investigate?", CORPUS_ID));
            assertEquals(expected, new AnswerLocalSearch(store, null).answer("What does Sherlock investigate?", CORPUS_ID));
            assertTrue(stub.embedded.isEmpty());
            assertTrue(store.entityEmbeddingsByCorpus.isEmpty());
        }

        @Test
        void aQueryTimeEmbeddingFailureSurfacesInsteadOfFallingBackToKeywords() {
            FakeGraphStore store = keywordStore();
            io.graphrag.core.port.EmbeddingPort failing = text -> {
                throw new IllegalStateException("outage");
            };

            org.junit.jupiter.api.Assertions.assertThrows(SemanticMatchingException.class,
                    () -> new AnswerLocalSearch(store, failing).answer("What does Sherlock investigate?", CORPUS_ID));
            org.junit.jupiter.api.Assertions.assertThrows(SemanticMatchingException.class,
                    () -> new AnswerGlobalSearch(new FakeGraphStore().communities(CORPUS_ID,
                            new Community("community-1", "Sherlock.")), failing).answer("Sherlock", CORPUS_ID));
        }

        private FakeGraphStore keywordStore() {
            return new FakeGraphStore()
                    .entities(CORPUS_ID, new Entity("Sherlock Holmes", "Person"), new Entity("Irene Adler", "Person"))
                    .relationships(CORPUS_ID,
                            new Relationship("Sherlock Holmes", "Person", "INVESTIGATES", "Irene Adler", "Person"));
        }
    }

    @Nested
    class Global {

        @Test
        void recordsExactlyTheTopThreeCommunitiesAndAnswersFromTheFirst() {
            FakeGraphStore store = fiveCommunities();
            FakeEmbeddingPort port = fiveCommunityPort();
            new EmbedGraphElements(store, port).run(CORPUS);

            GlobalSearchAnswer result = new AnswerGlobalSearch(store, port).answer("question", CORPUS_ID);

            assertFalse(result.noAnswer());
            assertEquals(List.of("community-0", "community-1", "community-2"), identifiers(result.steps()));
            assertEquals(List.of(RetrievalStep.Kind.COMMUNITY, RetrievalStep.Kind.COMMUNITY,
                    RetrievalStep.Kind.COMMUNITY), kinds(result.steps()));
            assertEquals("Across the corpus, the strongest signal is that Summary 0.", result.answer());
        }

        @Test
        void neverReturnsCommunitiesOfAnotherCorpus() {
            FakeGraphStore store = fiveCommunities()
                    .communities(OTHER_CORPUS_ID, new Community("community-9", "Closer elsewhere."));
            FakeEmbeddingPort port = fiveCommunityPort().map("Closer elsewhere.", QUESTION_VECTOR);
            new EmbedGraphElements(store, port).run(CORPUS);
            new EmbedGraphElements(store, port).run(new Corpus(OTHER_CORPUS_ID, List.of()));

            GlobalSearchAnswer result = new AnswerGlobalSearch(store, port).answer("question", CORPUS_ID);

            assertFalse(identifiers(result.steps()).contains("community-9"));
            assertEquals("community-0", result.steps().getFirst().identifier());
        }

        @Test
        void fallsBackToKeywordsForACorpusWithoutEmbeddingsAndForANonSemanticPort() {
            FakeGraphStore store = fiveCommunities();
            GlobalSearchAnswer expected = new AnswerGlobalSearch(store).answer("Summary 3", CORPUS_ID);

            assertEquals(5, expected.steps().size());
            assertEquals(expected, new AnswerGlobalSearch(store, fiveCommunityPort()).answer("Summary 3", CORPUS_ID));
            FakeEmbeddingPort stub = new FakeEmbeddingPort(false);
            assertEquals(expected, new AnswerGlobalSearch(store, stub).answer("Summary 3", CORPUS_ID));
            assertTrue(stub.embedded.isEmpty());
        }
    }

    @Nested
    class Drift {

        @Test
        void usesTheTopThreeCommunitiesInOrderAsCandidatesAndSpawnsOneSubQuestionEach() {
            FakeGraphStore store = fiveCommunities();
            FakeEmbeddingPort port = fiveCommunityPort();
            new EmbedGraphElements(store, port).run(CORPUS);
            List<List<String>> candidateIds = new ArrayList<>();
            LlmPort llmPort = new LlmPort() {
                @Override
                public GraphExtraction extract(Corpus corpus) {
                    return new GraphExtraction(List.of(), List.of());
                }

                @Override
                public List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
                    candidateIds.add(communities.stream().map(Community::id).toList());
                    return LlmPort.super.deriveDriftSubQuestions(question, communities);
                }
            };

            DriftSearchAnswer result = new AnswerDriftSearch(store, llmPort, port).answer("question", CORPUS_ID);

            assertEquals(List.of(List.of("community-0", "community-1", "community-2")), candidateIds);
            assertEquals(List.of(RetrievalStep.Kind.COMMUNITY, RetrievalStep.Kind.COMMUNITY,
                    RetrievalStep.Kind.COMMUNITY), kinds(result.steps().subList(0, 3)));
            assertEquals(List.of("community-0", "community-1", "community-2"),
                    identifiers(result.steps().subList(0, 3)));
            List<String> spawnedParents = result.steps().stream()
                    .filter(step -> step.kind() == RetrievalStep.Kind.SUB_QUESTION_SPAWNED)
                    .map(RetrievalStep::identifier)
                    .toList();
            assertEquals(List.of("community-0", "community-1", "community-2"), spawnedParents);
            assertEquals(RetrievalStep.Kind.SYNTHESIS, result.steps().getLast().kind());
        }

        @Test
        void theNestedLocalSearchesSeedByMeaningToo() {
            FakeGraphStore store = fiveCommunities().entities(CORPUS_ID,
                    new Entity("Ada Lovelace", "Person", "wrote the first computer program", List.of()),
                    new Entity("Charles Babbage", "Person", "designed the difference engine", List.of()));
            // The first sub-question shares no words with either Entity, but is closest to Ada.
            String firstSubQuestion = "question Community summary: Summary 0.";
            FakeEmbeddingPort port = fiveCommunityPort()
                    .map(firstSubQuestion, 0, 0, 1, 0)
                    .map("Ada Lovelace: wrote the first computer program", 0, 0, 1, 0.1f)
                    .map("Charles Babbage: designed the difference engine", 0, 0, 0, 1);
            new EmbedGraphElements(store, port).run(CORPUS);
            LlmPort llmPort = corpus -> new GraphExtraction(List.of(), List.of());

            assertEquals(LocalSearchAnswer.noMatch(), new AnswerLocalSearch(store).answer(firstSubQuestion, CORPUS_ID));
            DriftSearchAnswer result = new AnswerDriftSearch(store, llmPort, port).answer("question", CORPUS_ID);

            List<RetrievalStep> steps = result.steps();
            int firstBranch = steps.indexOf(steps.stream()
                    .filter(step -> step.kind() == RetrievalStep.Kind.SUB_QUESTION_SPAWNED)
                    .findFirst().orElseThrow());
            assertEquals(firstSubQuestion, steps.get(firstBranch).label());
            assertEquals(new RetrievalStep(RetrievalStep.Kind.ENTITY, "ada lovelace::person", "Ada Lovelace"),
                    steps.get(firstBranch + 1));
        }

        @Test
        void fallsBackToKeywordsForACorpusWithoutEmbeddingsAndForANonSemanticPort() {
            FakeGraphStore store = fiveCommunities();
            LlmPort llmPort = corpus -> new GraphExtraction(List.of(), List.of());
            DriftSearchAnswer expected = new AnswerDriftSearch(store, llmPort).answer("Summary 3", CORPUS_ID);

            assertEquals(expected,
                    new AnswerDriftSearch(store, llmPort, fiveCommunityPort()).answer("Summary 3", CORPUS_ID));
            assertEquals(expected,
                    new AnswerDriftSearch(store, llmPort, new FakeEmbeddingPort(false)).answer("Summary 3", CORPUS_ID));
            assertEquals(expected, new AnswerDriftSearch(store, llmPort, null).answer("Summary 3", CORPUS_ID));
        }
    }

    private static FakeGraphStore fiveCommunities() {
        FakeGraphStore store = new FakeGraphStore();
        for (int i : new int[] {4, 2, 0, 3, 1}) {
            store.communities(CORPUS_ID, new Community("community-" + i, "Summary " + i + "."));
        }
        return store;
    }

    private static FakeEmbeddingPort fiveCommunityPort() {
        FakeEmbeddingPort port = new FakeEmbeddingPort().map("question", QUESTION_VECTOR);
        for (int i = 0; i < DESCENDING.length; i++) {
            port.map("Summary " + i + ".", DESCENDING[i]);
        }
        return port;
    }
}
