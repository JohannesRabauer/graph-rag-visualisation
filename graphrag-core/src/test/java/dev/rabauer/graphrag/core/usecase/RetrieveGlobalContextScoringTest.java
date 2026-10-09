package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.retrieval.GlobalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.HybridSeedMatcher;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;
import dev.rabauer.graphrag.core.retrieval.SeedMatch;
import dev.rabauer.graphrag.core.retrieval.SeedMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CHARGE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CONTROLLER;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CORPUS;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.PAYMENT;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.PLACE_ORDER;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How Global retrieval combines the keyword overlap of a summary with the member seed scores. */
class RetrieveGlobalContextScoringTest {

    /** a-order holds the member the question names; b/c only share words with the question. */
    private static TestGraphStore store() {
        TestGraphStore store = SmallCodeGraph.store();
        store.persistCommunities(CORPUS, List.of(
                new Community("a-order", "order-pkg", "Persistence layer."),
                new Community("b-two", "two", "alpha beta"),
                new Community("c-three", "three", "alpha beta gamma")));
        store.persistCommunityMemberships(CORPUS, List.of(
                new CommunityMembership("a-order", id(PLACE_ORDER, "Method")),
                new CommunityMembership("b-two", id(CONTROLLER, "Class")),
                new CommunityMembership("c-three", id(PAYMENT, "Class")),
                new CommunityMembership("c-three", id(CHARGE, "Method"))));
        return store;
    }

    private static final String QUESTION = "placeOrder alpha beta gamma";

    /** The reciprocal-rank-fused scores are about 0.03: a different scale than the integer keyword counts. */
    private static SeedMatcher fused() {
        SeedMatcher pinned = (question, corpusId, graph, limit) -> graph.entities(corpusId).stream()
                .filter(entity -> entity.name().equals(PLACE_ORDER)).map(entity -> new SeedMatch(entity, 7, "x"))
                .toList();
        return new HybridSeedMatcher(List.of(pinned, pinned));
    }

    private static List<String> communities(RetrievalResult result) {
        return result.items().stream().filter(item -> item.kind() == RetrievalStep.Kind.COMMUNITY)
                .map(RetrievedItem::identifier).toList();
    }

    @Test
    void aFusedMemberMatchStillCountsNextToKeywordCounts() {
        RetrieveGlobalContext global = new RetrieveGlobalContext(store(), null, fused(),
                GlobalRetrievalOptions.defaults().withMaxCommunities(2));

        List<String> picked = communities(global.retrieve(QUESTION, CORPUS));

        // The member matched by name is as strong a signal as the best summary: both rank above the weaker summary.
        assertEquals(List.of("a-order", "c-three"), picked);
    }

    @Test
    void theRankingDoesNotDependOnTheScaleOfTheMatcher() {
        SeedMatcher small = fused();
        SeedMatcher thousandTimes = (question, corpusId, graph, limit) -> small.match(question, corpusId, graph, limit)
                .stream().map(match -> new SeedMatch(match.entity(), match.score() * 1000, match.matchedBy())).toList();
        GlobalRetrievalOptions options = GlobalRetrievalOptions.defaults().withMaxCommunities(3);

        List<String> one = communities(new RetrieveGlobalContext(store(), null, small, options)
                .retrieve(QUESTION, CORPUS));
        List<String> many = communities(new RetrieveGlobalContext(store(), null, thousandTimes, options)
                .retrieve(QUESTION, CORPUS));

        assertEquals(one, many);
        assertEquals(3, one.size());
    }

    @Test
    void communityScoresStayOnASharedScale() {
        RetrievalResult result = new RetrieveGlobalContext(store(), null, fused(), GlobalRetrievalOptions.defaults())
                .retrieve(QUESTION, CORPUS);

        for (RetrievedItem item : result.items()) {
            if (item.kind() == RetrievalStep.Kind.COMMUNITY) {
                assertTrue(item.score() > 0 && item.score() <= 2.0, item.identifier() + ": " + item.score());
            }
        }
    }
}
