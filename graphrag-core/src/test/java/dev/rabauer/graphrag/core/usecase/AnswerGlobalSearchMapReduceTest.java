package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityPoint;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.usecase.SemanticTestFixtures.FakeEmbeddingPort;
import dev.rabauer.graphrag.core.usecase.SemanticTestFixtures.FakeGraphStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Map-reduce Global Search: every Community is read for scored points, the best ones answer. */
class AnswerGlobalSearchMapReduceTest {

    private static final String CORPUS_ID = "corpus-a";
    /** Shares no word with any summary, so the keyword order is the id order. */
    private static final String QUESTION = "Overall motifs?";

    /** Maps with a scripted function and answers with another, recording both. */
    private static final class MapReducePort implements LlmPort {
        private final Function<List<Community>, List<CommunityPoint>> map;
        private final Function<List<ContextItem>, SynthesizedAnswer> reduce;
        final List<List<String>> batches = new ArrayList<>();
        final List<List<ContextItem>> contexts = new ArrayList<>();

        MapReducePort(Function<List<Community>, List<CommunityPoint>> map,
                      Function<List<ContextItem>, SynthesizedAnswer> reduce) {
            this.map = map;
            this.reduce = reduce;
        }

        @Override
        public GraphExtraction extract(Corpus corpus) {
            return GraphExtraction.empty();
        }

        @Override
        public boolean synthesizesAnswers() {
            return true;
        }

        @Override
        public boolean mapsCommunities() {
            return true;
        }

        @Override
        public List<CommunityPoint> mapCommunities(String question, List<Community> communities) {
            batches.add(communities.stream().map(Community::id).toList());
            return map.apply(communities);
        }

        @Override
        public SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context) {
            contexts.add(context);
            return reduce.apply(context);
        }
    }

    private static TextUnit unit(String id) {
        return new TextUnit(id, CORPUS_ID, "story.txt", 0, "Passage " + id + ".");
    }

    /**
     * Seven Communities; only community-1 (Holmes, Irene: tu-3 then tu-2) and
     * community-2 (Watson, Holmes: tu-4 then tu-1) have members.
     */
    private static FakeGraphStore store() {
        return new FakeGraphStore()
                .communities(CORPUS_ID,
                        new Community("community-1", "Adler case", "Holmes and Irene Adler clash."),
                        new Community("community-2", "Watson keeps the records."),
                        new Community("community-3", "Lestrade at Scotland Yard."),
                        new Community("community-4", "Violin practice."),
                        new Community("community-5", "Mrs Hudson's house."),
                        new Community("community-6", "Moriarty's network."),
                        new Community("community-7", "The dog in the night."))
                .entities(CORPUS_ID,
                        new Entity("Holmes", "Person", "", List.of("tu-1", "tu-2")),
                        new Entity("Irene", "Person", "", List.of("tu-2", "tu-3")),
                        new Entity("Watson", "Person", "", List.of("tu-4")))
                .relationships(CORPUS_ID,
                        new Relationship("Holmes", "Person", "outwitted_by", "Irene", "Person", "",
                                List.of("tu-3"), 5))
                .memberships(CORPUS_ID, "community-1", "holmes::person", "irene::person")
                .memberships(CORPUS_ID, "community-2", "watson::person", "holmes::person")
                .textUnits(CORPUS_ID, unit("tu-1"), unit("tu-2"), unit("tu-3"), unit("tu-4"));
    }

    private static List<CommunityPoint> scripted(List<Community> batch) {
        List<CommunityPoint> points = new ArrayList<>();
        for (Community community : batch) {
            switch (community.id()) {
                case "community-1" -> {
                    points.add(new CommunityPoint("community-1", "Holmes is outwitted by Irene.", 90));
                    points.add(new CommunityPoint("community-9", "Not in this batch.", 99));
                }
                case "community-2" -> points.add(new CommunityPoint("community-2", "Watson records it.", 60));
                case "community-4" -> points.add(new CommunityPoint("community-4", "Violin.", 0));
                case "community-7" -> points.add(new CommunityPoint("community-7", "A minor clue.", 10));
                default -> {
                }
            }
        }
        return points;
    }

    private static List<String> stepIds(List<RetrievalStep> steps) {
        return steps.stream().map(step -> step.kind() + ":" + step.identifier()).toList();
    }

    @Test
    void readsEveryCommunityInBatchesAndReducesTheBestPointsWithTheirPassages() {
        MapReducePort llm = new MapReducePort(AnswerGlobalSearchMapReduceTest::scripted,
                context -> new SynthesizedAnswer(false, "Irene outwitted Holmes [4]. Watson wrote it up [6]."));

        GlobalSearchAnswer result = new AnswerGlobalSearch(store(), null, llm).answer(QUESTION, CORPUS_ID);

        assertEquals(List.of(
                List.of("community-1", "community-2", "community-3", "community-4", "community-5"),
                List.of("community-6", "community-7")), llm.batches);
        assertEquals(List.of(
                "COMMUNITY:community-1", "COMMUNITY:community-2", "COMMUNITY:community-3", "COMMUNITY:community-4",
                "COMMUNITY:community-5", "COMMUNITY:community-6", "COMMUNITY:community-7",
                "TEXT_UNIT:tu-3", "TEXT_UNIT:tu-2", "TEXT_UNIT:tu-4", "TEXT_UNIT:tu-1"), stepIds(result.steps()));

        List<ContextItem> context = llm.contexts.getFirst();
        assertEquals(List.of(
                "Adler case: Holmes is outwitted by Irene. (importance 90)",
                "community-2: Watson records it. (importance 60)",
                "community-7: A minor clue. (importance 10)",
                "Passage tu-3.", "Passage tu-2.", "Passage tu-4.", "Passage tu-1."),
                context.stream().map(ContextItem::text).toList());
        assertEquals(RetrievalStep.Kind.COMMUNITY, context.getFirst().kind());

        assertEquals("Irene outwitted Holmes [1]. Watson wrote it up [2].", result.answer());
        assertEquals(List.of("tu-3", "tu-4"), result.citations().stream().map(Citation::textUnitId).toList());
    }

    @Test
    void noPointAboveZeroMeansNotInContextWithoutAReduceCall() {
        MapReducePort llm = new MapReducePort(
                batch -> batch.stream().map(community -> new CommunityPoint(community.id(), "Nothing.", 0)).toList(),
                context -> new SynthesizedAnswer(false, "Unused."));

        GlobalSearchAnswer result = new AnswerGlobalSearch(store(), null, llm).answer(QUESTION, CORPUS_ID);

        assertTrue(result.noAnswer());
        assertEquals(AnswerGlobalSearch.NOT_IN_CONTEXT_REASON, result.reason());
        assertEquals(7, result.steps().size());
        assertTrue(llm.contexts.isEmpty());
    }

    @Test
    void readsAtMostThirtyCommunitiesAndKeepsTheTwentyBestPoints() {
        FakeGraphStore store = new FakeGraphStore();
        List<Community> communities = new ArrayList<>();
        for (int i = 10; i < 45; i++) {
            communities.add(new Community("community-" + i, "Summary " + i + "."));
        }
        store.communities(CORPUS_ID, communities.toArray(Community[]::new));
        MapReducePort llm = new MapReducePort(
                batch -> batch.stream().map(community -> new CommunityPoint(community.id(), "Point.",
                        Integer.parseInt(community.id().substring(10)))).toList(),
                context -> new SynthesizedAnswer(true, ""));

        GlobalSearchAnswer result = new AnswerGlobalSearch(store, null, llm).answer(QUESTION, CORPUS_ID);

        assertEquals(AnswerGlobalSearch.MAX_MAPPED_COMMUNITIES, result.steps().size());
        assertEquals(6, llm.batches.size());
        List<ContextItem> context = llm.contexts.getFirst();
        assertEquals(AnswerGlobalSearch.MAX_REDUCE_POINTS, context.size());
        assertEquals("community-39: Point. (importance 39)", context.getFirst().text());
    }

    @Test
    void readsTheMostSimilarCommunitiesFirstWithASemanticEmbeddingPort() {
        FakeGraphStore store = store();
        store.persistCommunityEmbeddings(CORPUS_ID, Map.of(
                "community-2", new float[] {1, 0, 0, 0},
                "community-6", new float[] {0.9f, 0.1f, 0, 0},
                "community-1", new float[] {0, 1, 0, 0}));
        FakeEmbeddingPort embeddings = new FakeEmbeddingPort().map(QUESTION, 1, 0, 0, 0);
        MapReducePort llm = new MapReducePort(AnswerGlobalSearchMapReduceTest::scripted,
                context -> new SynthesizedAnswer(false, "Answer."));

        new AnswerGlobalSearch(store, embeddings, llm).answer(QUESTION, CORPUS_ID);

        assertEquals(List.of(List.of("community-2", "community-6", "community-1")), llm.batches);
    }
}
