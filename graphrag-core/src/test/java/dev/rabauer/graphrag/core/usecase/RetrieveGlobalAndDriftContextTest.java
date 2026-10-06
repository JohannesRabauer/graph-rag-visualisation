package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.retrieval.DriftRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.GlobalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.IdentifierSeedMatcher;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CHARGE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CONTROLLER;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CORPUS;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CREATE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.JPA_REPOSITORY;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.PAYMENT;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.PLACE_ORDER;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.REPOSITORY;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.SAVE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.SERVICE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrieveGlobalAndDriftContextTest {

    private static TestGraphStore storeWithPackages() {
        TestGraphStore store = SmallCodeGraph.store();
        store.persistCommunities(CORPUS, List.of(
                new Community("pkg:com.shop.order", "com.shop.order", "Order placement and persistence."),
                new Community("pkg:com.shop.web", "com.shop.web", "HTTP endpoints."),
                new Community("pkg:com.shop.payment", "com.shop.payment", "Charging customers for payments.")));
        store.persistCommunityMemberships(CORPUS, List.of(
                new CommunityMembership("pkg:com.shop.order", id(SERVICE, "Class")),
                new CommunityMembership("pkg:com.shop.order", id(PLACE_ORDER, "Method")),
                new CommunityMembership("pkg:com.shop.order", id(REPOSITORY, "Interface")),
                new CommunityMembership("pkg:com.shop.order", id(SAVE, "Method")),
                new CommunityMembership("pkg:com.shop.order", id(JPA_REPOSITORY, "Class")),
                new CommunityMembership("pkg:com.shop.web", id(CONTROLLER, "Class")),
                new CommunityMembership("pkg:com.shop.web", id(CREATE, "Method")),
                new CommunityMembership("pkg:com.shop.payment", id(PAYMENT, "Class")),
                new CommunityMembership("pkg:com.shop.payment", id(CHARGE, "Method"))));
        return store;
    }

    private static List<String> identifiers(RetrievalResult result, RetrievalStep.Kind kind) {
        return result.items().stream().filter(item -> item.kind() == kind).map(RetrievedItem::identifier).toList();
    }

    private static RetrieveGlobalContext global(TestGraphStore store) {
        return new RetrieveGlobalContext(store, null, new IdentifierSeedMatcher(), GlobalRetrievalOptions.defaults());
    }

    @Test
    void globalFindsTheCommunityHoldingAnIdentifierWithItsMembersAndSnippets() {
        RetrievalResult result = global(storeWithPackages()).retrieve("Who calls placeOrder?", CORPUS);

        assertEquals(RetrievalResult.Mode.GLOBAL, result.mode());
        assertEquals(RetrievalResult.Status.MATCHED, result.status());
        RetrievedItem first = result.items().getFirst();
        assertEquals(RetrievalStep.Kind.COMMUNITY, first.kind());
        assertEquals("pkg:com.shop.order", first.identifier());
        assertEquals("com.shop.order: Order placement and persistence.", first.text());
        assertEquals(id(PLACE_ORDER, "Method"), result.items().get(1).identifier());
        assertEquals("src/main/java/com/shop/order/OrderService.java:20-35", result.items().get(1).locator().format());
        assertTrue(identifiers(result, RetrievalStep.Kind.TEXT_UNIT).contains("tu-placeOrder"));
        assertEquals(result.items().size(), result.trace().steps().size());
    }

    @Test
    void globalPicksCommunitiesBySummaryKeywordsToo() {
        RetrievalResult result = global(storeWithPackages()).retrieve("How are customers charged for payments?", CORPUS);

        assertEquals("pkg:com.shop.payment", result.items().getFirst().identifier());
    }

    @Test
    void globalCapsMembersAndSnippetsPerCommunityAndNeverRepeatsASnippet() {
        RetrievalResult result = global(storeWithPackages()).retrieve("placeOrder save charge create", CORPUS,
                GlobalRetrievalOptions.defaults().withMemberEntitiesPerCommunity(1).withTextUnitsPerCommunity(1));

        assertEquals(3, identifiers(result, RetrievalStep.Kind.COMMUNITY).size());
        assertEquals(3, identifiers(result, RetrievalStep.Kind.ENTITY).size());
        List<String> units = identifiers(result, RetrievalStep.Kind.TEXT_UNIT);
        assertEquals(units.size(), new HashSet<>(units).size());
        assertTrue(units.size() <= 3);
    }

    @Test
    void theAnswerContextOptionsHaveNoMemberEntities() {
        RetrievalResult result = global(storeWithPackages()).retrieve("Order placement", CORPUS,
                GlobalRetrievalOptions.answerContext());

        assertTrue(identifiers(result, RetrievalStep.Kind.ENTITY).isEmpty());
    }

    @Test
    void globalReportsMissingCommunitiesAndNoMatch() {
        RetrievalResult none = global(SmallCodeGraph.store()).retrieve("placeOrder", CORPUS);
        RetrievalResult unrelated = global(storeWithPackages()).retrieve("Tell me about the weather", CORPUS);

        assertEquals(RetrievalResult.Status.NO_COMMUNITIES, none.status());
        assertEquals(RetrieveGlobalContext.NO_COMMUNITIES_REASON, none.reason());
        assertEquals(RetrievalResult.Status.NO_MATCH, unrelated.status());
        assertTrue(unrelated.items().isEmpty());
    }

    @Test
    void driftBranchesPerSubQuestionAndDeduplicatesItems() {
        RetrievalResult result = new RetrieveDriftContext(storeWithPackages(), null, null, new IdentifierSeedMatcher(),
                DriftRetrievalOptions.defaults()).retrieve("Who calls placeOrder?", CORPUS);

        assertEquals(RetrievalResult.Mode.DRIFT, result.mode());
        List<RetrievalStep> steps = result.trace().steps();
        assertEquals(RetrievalStep.Kind.COMMUNITY, steps.getFirst().kind());
        RetrievalStep spawned = steps.stream().filter(step -> step.kind() == RetrievalStep.Kind.SUB_QUESTION_SPAWNED)
                .findFirst().orElseThrow();
        assertEquals("pkg:com.shop.order", spawned.identifier());
        assertTrue(spawned.label().startsWith("Who calls placeOrder?"));
        assertTrue(identifiers(result, RetrievalStep.Kind.ENTITY).contains(id(PLACE_ORDER, "Method")));
        Set<String> keys = new HashSet<>();
        for (RetrievedItem item : result.items()) {
            assertTrue(keys.add(item.kind() + ":" + item.identifier()), "duplicate " + item.identifier());
        }
        assertTrue(result.items().stream().noneMatch(item -> item.kind() == RetrievalStep.Kind.SUB_QUESTION_SPAWNED));
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void driftFallsBackToTheQuestionsSeedsWhenASubQuestionHasNone() {
        LlmPort vagueSubQuestions = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus corpus) {
                return GraphExtraction.empty();
            }

            @Override
            public List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
                return List.of("What happens here?");
            }
        };

        RetrievalResult result = new RetrieveDriftContext(storeWithPackages(), vagueSubQuestions, null,
                new IdentifierSeedMatcher(), DriftRetrievalOptions.defaults()).retrieve("placeOrder", CORPUS);

        assertTrue(identifiers(result, RetrievalStep.Kind.ENTITY).contains(id(PLACE_ORDER, "Method")));
    }

    @Test
    void aFailingSubQuestionDerivationIsIsolatedWithAWarningOrPropagates() {
        LlmPort failing = new LlmPort() {
            @Override
            public GraphExtraction extract(Corpus corpus) {
                return GraphExtraction.empty();
            }

            @Override
            public List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
                throw new IllegalStateException("model unreachable");
            }
        };
        TestGraphStore store = storeWithPackages();

        RetrievalResult isolated = new RetrieveDriftContext(store, failing, null, new IdentifierSeedMatcher(),
                DriftRetrievalOptions.defaults()).retrieve("placeOrder", CORPUS);

        assertEquals(RetrievalResult.Status.MATCHED, isolated.status());
        assertEquals(1, isolated.warnings().size());
        assertTrue(isolated.warnings().getFirst().contains("model unreachable"));
        assertThrows(IllegalStateException.class, () -> new RetrieveDriftContext(store, failing, null,
                new IdentifierSeedMatcher(), DriftRetrievalOptions.defaults().withFailurePolicy(FailurePolicy.FAIL_RUN))
                .retrieve("placeOrder", CORPUS));
    }

    @Test
    void driftReportsMissingCommunities() {
        RetrievalResult result = new RetrieveDriftContext(SmallCodeGraph.store()).retrieve("placeOrder", CORPUS);

        assertEquals(RetrievalResult.Status.NO_COMMUNITIES, result.status());
    }
}
