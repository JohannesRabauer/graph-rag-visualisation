package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SourceLocator;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.retrieval.IdentifierSeedMatcher;
import dev.rabauer.graphrag.core.retrieval.KeywordSeedMatcher;
import dev.rabauer.graphrag.core.retrieval.LocalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.LocalRetrievalOptions.Direction;
import dev.rabauer.graphrag.core.retrieval.LocalRetrievalOptions.RelationshipOrdering;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CHARGE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CORPUS;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CREATE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.PLACE_ORDER;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.SAVE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.SERVICE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrieveLocalContextTest {

    private final TestGraphStore store = SmallCodeGraph.store();
    private final LocalRetrievalOptions oneSeed = LocalRetrievalOptions.defaults().withSeedLimit(1);

    private RetrievalResult retrieve(String question, LocalRetrievalOptions options) {
        return new RetrieveLocalContext(store, new IdentifierSeedMatcher(), options).retrieve(question, CORPUS);
    }

    private static List<String> identifiers(RetrievalResult result, RetrievalStep.Kind kind) {
        return result.items().stream().filter(item -> item.kind() == kind).map(RetrievedItem::identifier).toList();
    }

    @Test
    void returnsSeedNeighboursAndSnippetsWithLocatorsAndNoAnswer() {
        RetrievalResult result = retrieve("Who calls placeOrder?", oneSeed);

        assertEquals(RetrievalResult.Mode.LOCAL, result.mode());
        assertEquals(RetrievalResult.Status.MATCHED, result.status());
        RetrievedItem seed = result.items().getFirst();
        assertEquals(RetrievalStep.Kind.ENTITY, seed.kind());
        assertEquals(id(PLACE_ORDER, "Method"), seed.identifier());
        assertEquals("src/main/java/com/shop/order/OrderService.java:20-35", seed.locator().format());
        assertEquals("method", seed.attributes().get("kind"));
        assertEquals(0, seed.hop());
        assertTrue(seed.score() > 0);

        // Heaviest call first: OrderController#create calls it three times, at OrderController.java:22.
        RetrievedItem heaviest = result.items().get(1);
        assertEquals(RetrievalStep.Kind.RELATIONSHIP, heaviest.kind());
        assertEquals(id(CREATE, "Method") + "->CALLS->" + id(PLACE_ORDER, "Method"), heaviest.identifier());
        assertEquals(SourceLocator.of("src/main/java/com/shop/web/OrderController.java", 22), heaviest.locator());
        assertEquals(3.0, heaviest.score());
        assertEquals(1, heaviest.hop());
        assertEquals(id(CREATE, "Method"), result.items().get(2).identifier());

        assertEquals(List.of(id(PLACE_ORDER, "Method"), id(CREATE, "Method"), id(SAVE, "Method"),
                id(CHARGE, "Method"), id(SERVICE, "Class")), identifiers(result, RetrievalStep.Kind.ENTITY));
        assertEquals(List.of("tu-placeOrder", "tu-create", "tu-save", "tu-charge", "tu-service"),
                identifiers(result, RetrievalStep.Kind.TEXT_UNIT));
        RetrievedItem snippet = result.items().stream()
                .filter(item -> item.identifier().equals("tu-placeOrder")).findFirst().orElseThrow();
        assertTrue(snippet.text().contains("repository.save(order)"));
        assertEquals("src/main/java/com/shop/order/OrderService.java:20-35", snippet.locator().format());
        assertEquals(-1, snippet.hop());
    }

    @Test
    void itemsAreNumberedAndTheTraceFollowsTheItems() {
        RetrievalResult result = retrieve("placeOrder", oneSeed);

        for (int i = 0; i < result.items().size(); i++) {
            RetrievedItem item = result.items().get(i);
            RetrievalStep step = result.trace().steps().get(i);
            assertEquals(i + 1, item.number());
            assertEquals(item.kind(), step.kind());
            assertEquals(item.identifier(), step.identifier());
            assertEquals(item.locator(), step.locator());
        }
        assertEquals(result.items().size(), result.trace().steps().size());
    }

    @Test
    void followsOnlyIncomingCallsForCallerQuestions() {
        RetrievalResult result = retrieve("Who calls placeOrder?", oneSeed
                .withDirection(Direction.INCOMING).withIncludeRelationshipTypes(Set.of("calls")));

        assertEquals(List.of(id(CREATE, "Method") + "->CALLS->" + id(PLACE_ORDER, "Method")),
                identifiers(result, RetrievalStep.Kind.RELATIONSHIP));
    }

    @Test
    void expandsSeveralHopsAlongOutgoingCalls() {
        RetrievalResult result = retrieve("OrderController.create", oneSeed.withMaxHops(2)
                .withDirection(Direction.OUTGOING).withIncludeRelationshipTypes(Set.of("CALLS")));

        assertEquals(List.of(id(CREATE, "Method"), id(PLACE_ORDER, "Method"), id(SAVE, "Method"), id(CHARGE, "Method")),
                identifiers(result, RetrievalStep.Kind.ENTITY));
        assertEquals(List.of(0, 1, 2, 2), result.items().stream()
                .filter(item -> item.kind() == RetrievalStep.Kind.ENTITY).map(RetrievedItem::hop).toList());
    }

    @Test
    void zeroHopsReturnsTheSeedAndItsOwnSnippet() {
        RetrievalResult result = retrieve("placeOrder", oneSeed.withMaxHops(0));

        assertEquals(List.of(RetrievalStep.Kind.ENTITY, RetrievalStep.Kind.TEXT_UNIT),
                result.items().stream().map(RetrievedItem::kind).toList());
    }

    @Test
    void honoursTheNodeItemAndWeightCaps() {
        assertEquals(2, identifiers(retrieve("placeOrder", oneSeed.withMaxNodes(2)), RetrievalStep.Kind.ENTITY).size());
        assertEquals(3, retrieve("placeOrder", oneSeed.withMaxItems(3)).items().size());
        assertEquals(1, identifiers(retrieve("placeOrder", oneSeed.withMaxRelationships(1)),
                RetrievalStep.Kind.RELATIONSHIP).size());
        assertEquals(2, identifiers(retrieve("placeOrder", oneSeed.withMaxTextUnits(2)),
                RetrievalStep.Kind.TEXT_UNIT).size());
        assertEquals(List.of(id(CREATE, "Method") + "->CALLS->" + id(PLACE_ORDER, "Method"),
                        id(PLACE_ORDER, "Method") + "->CALLS->" + id(SAVE, "Method")),
                identifiers(retrieve("placeOrder", oneSeed.withMinWeight(2)), RetrievalStep.Kind.RELATIONSHIP));
    }

    @Test
    void excludesRelationshipTypesCaseInsensitively() {
        List<String> relationships = identifiers(retrieve("placeOrder", oneSeed
                .withExcludeRelationshipTypes(Set.of("declares"))), RetrievalStep.Kind.RELATIONSHIP);

        assertEquals(3, relationships.size());
        assertTrue(relationships.stream().noneMatch(edge -> edge.contains("DECLARES")));
    }

    @Test
    void ordersRelationshipsByWeightOrStoredOrder() {
        String lightestFirst = identifiers(retrieve("placeOrder", oneSeed.withOrdering(RelationshipOrdering.WEIGHT_ASC)),
                RetrievalStep.Kind.RELATIONSHIP).getFirst();
        String storedFirst = identifiers(retrieve("placeOrder", oneSeed.withOrdering(RelationshipOrdering.STORED)),
                RetrievalStep.Kind.RELATIONSHIP).getFirst();

        assertEquals(id(PLACE_ORDER, "Method") + "->CALLS->" + id(CHARGE, "Method"), lightestFirst);
        assertEquals(id(CREATE, "Method") + "->CALLS->" + id(PLACE_ORDER, "Method"), storedFirst);
    }

    @Test
    void withoutNeighbourEntitiesAndMemberSnippetsOnlyTheSeedsSnippetsCount() {
        RetrievalResult result = retrieve("placeOrder", LocalRetrievalOptions.answerContext().withSeedLimit(1));

        assertEquals(List.of(id(PLACE_ORDER, "Method")), identifiers(result, RetrievalStep.Kind.ENTITY));
        assertEquals(List.of("tu-placeOrder"), identifiers(result, RetrievalStep.Kind.TEXT_UNIT));
    }

    @Test
    void anUnknownQuestionIsANoMatchWithAReasonAndAnEmptyTrace() {
        RetrievalResult result = retrieve("Tell me about the weather", oneSeed);

        assertEquals(RetrievalResult.Status.NO_MATCH, result.status());
        assertTrue(result.items().isEmpty());
        assertTrue(result.trace().steps().isEmpty());
        assertEquals(RetrieveLocalContext.NO_MATCH_REASON, result.reason());
    }

    @Test
    void theAnswerContextOptionsReproduceLocalSearchsSynthesisContext() {
        SemanticTestFixtures.RecordingLlmPort llm =
                new SemanticTestFixtures.RecordingLlmPort(context -> new SynthesizedAnswer(false, "Answer [1]."));
        LocalSearchAnswer answer = new AnswerLocalSearch(store, null, llm).answer("placeOrder", CORPUS);

        RetrievalResult retrieved = new RetrieveLocalContext(store, new KeywordSeedMatcher(),
                LocalRetrievalOptions.answerContext().withSeedLimit(1)).retrieve("placeOrder", CORPUS);

        assertEquals(answer.steps(), retrieved.trace().steps());
        List<ContextItem> context = retrieved.toContextItems();
        assertEquals(llm.contexts.getFirst(), context);
    }

    @Test
    void contextItemsCarryTheTextUnitIdOnlyForTextUnits() {
        List<ContextItem> context = retrieve("placeOrder", oneSeed).toContextItems();

        assertNull(context.getFirst().textUnitId());
        assertEquals("tu-placeOrder", context.stream().filter(ContextItem::isTextUnit).findFirst().orElseThrow()
                .textUnitId());
    }

    @Test
    void validatesItsOptions() {
        LocalRetrievalOptions defaults = LocalRetrievalOptions.defaults();
        assertThrows(IllegalArgumentException.class, () -> defaults.withSeedLimit(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxHops(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxItems(0));
        assertTrue(defaults.withIncludeRelationshipTypes(Set.of("CALLS")).accepts("calls", 1));
        assertTrue(!defaults.withMinWeight(2).accepts("CALLS", 1));
    }
}
