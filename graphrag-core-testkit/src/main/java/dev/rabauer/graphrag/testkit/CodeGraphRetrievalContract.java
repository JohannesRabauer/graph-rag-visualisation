package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.community.GraphCommunities;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.retrieval.GlobalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.IdentifierSeedMatcher;
import dev.rabauer.graphrag.core.retrieval.LocalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;
import dev.rabauer.graphrag.core.usecase.DetectCommunities;
import dev.rabauer.graphrag.core.usecase.ImportKnowledgeGraph;
import dev.rabauer.graphrag.core.usecase.ImportResult;
import dev.rabauer.graphrag.core.usecase.RetrieveGlobalContext;
import dev.rabauer.graphrag.core.usecase.RetrieveLocalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.rabauer.graphrag.testkit.CodeGraphFixture.CORPUS;
import static dev.rabauer.graphrag.testkit.CodeGraphFixture.CREATE_ORDER;
import static dev.rabauer.graphrag.testkit.CodeGraphFixture.CREATE_ORDER_CALL_SITE;
import static dev.rabauer.graphrag.testkit.CodeGraphFixture.PLACE_ORDER;
import static dev.rabauer.graphrag.testkit.CodeGraphFixture.methodIdentity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole non-text path against any {@link GraphStorePort}, offline: import
 * the {@link CodeGraphFixture} (no LLM extraction), detect Communities with the
 * core detector, then run retrieval-only Local and Global Search for a
 * camelCase-identifier question and check the trace's Entities,
 * Relationships and locators. Extend it with your store in {@link #newStore()}.
 */
public abstract class CodeGraphRetrievalContract {

    /** The store under test; it may be shared, the corpus id is unique per test. */
    protected GraphStorePort store;
    protected String corpusId;
    protected ImportResult imported;

    protected abstract GraphStorePort newStore();

    @BeforeEach
    void importTheCodeGraph() {
        store = newStore();
        corpusId = CORPUS + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        imported = new ImportKnowledgeGraph(store, LlmPort.none(), null).run(corpusId,
                CodeGraphFixture.create().graph(),
                ImportKnowledgeGraph.Options.defaults().withCommunities(DetectCommunities.Options.defaults()
                        .withDetector(GraphCommunities.defaultDetector())));
    }

    @Test
    void importsTheGraphWithoutExtractionAndDetectsPackageCommunities() {
        CodeGraphFixture fixture = CodeGraphFixture.create();

        assertEquals(50, fixture.typeCount());
        assertEquals(fixture.entities().size(), imported.entities());
        assertEquals(0, imported.placeholderEntities());
        assertEquals(ImportResult.CommunitySource.DETECTED, imported.communitySource());
        assertTrue(imported.communities().size() >= 4, () -> "communities: " + imported.communities().size());

        String placeOrderCommunity = communityOf(methodIdentity(PLACE_ORDER));
        List<String> members = store.communityMemberships(corpusId).stream()
                .filter(membership -> membership.communityId().equals(placeOrderCommunity))
                .map(CommunityMembership::entityIdentity).toList();
        long inOrderPackage = members.stream().filter(identity -> identity.startsWith("com.shop.order.")).count();
        assertTrue(inOrderPackage >= 0.8 * members.size(),
                () -> "placeOrder's Community should be the order package: " + members);
    }

    @Test
    void localRetrievalFindsTheCallersOfACamelCaseMethodWithTheirLocators() {
        RetrievalResult result = new RetrieveLocalContext(store, new IdentifierSeedMatcher(),
                LocalRetrievalOptions.defaults().withSeedLimit(1)
                        .withDirection(LocalRetrievalOptions.Direction.INCOMING)
                        .withIncludeRelationshipTypes(Set.of("CALLS")))
                .retrieve("Who calls OrderService.placeOrder?", corpusId);

        assertEquals(RetrievalResult.Status.MATCHED, result.status());
        RetrievedItem seed = result.items().getFirst();
        assertEquals(methodIdentity(PLACE_ORDER), seed.identifier());
        assertEquals("src/main/java/com/shop/order/OrderService.java:10-20", seed.locator().format());

        RetrievedItem heaviest = items(result, RetrievalStep.Kind.RELATIONSHIP).getFirst();
        assertEquals(methodIdentity(CREATE_ORDER) + "->CALLS->" + methodIdentity(PLACE_ORDER), heaviest.identifier());
        assertEquals(CREATE_ORDER_CALL_SITE, heaviest.locator());
        assertEquals(3.0, heaviest.score());
        assertEquals("3", heaviest.attributes().get("callCount"));

        Map<String, RetrievedItem> entities = items(result, RetrievalStep.Kind.ENTITY).stream()
                .collect(Collectors.toMap(RetrievedItem::identifier, item -> item));
        RetrievedItem caller = entities.get(methodIdentity(CREATE_ORDER));
        assertNotNull(caller, () -> "createOrder should be retrieved: " + entities.keySet());
        assertEquals("src/main/java/com/shop/web/OrderController.java:10-20", caller.locator().format());
        assertTrue(entities.containsKey(methodIdentity("com.shop.order.OrderCache#handleOrderCache()")));
        assertTrue(items(result, RetrievalStep.Kind.RELATIONSHIP).stream()
                .allMatch(item -> item.identifier().endsWith("->CALLS->" + methodIdentity(PLACE_ORDER))));

        RetrievedItem snippet = items(result, RetrievalStep.Kind.TEXT_UNIT).stream()
                .filter(item -> item.identifier().equals("tu:" + CREATE_ORDER)).findFirst().orElseThrow();
        assertEquals("src/main/java/com/shop/web/OrderController.java:10-20", snippet.locator().format());
        assertEquals(result.items().size(), result.trace().steps().size());
    }

    @Test
    void globalRetrievalPicksThePackageCommunityOfACamelCaseIdentifier() {
        RetrievalResult result = new RetrieveGlobalContext(store, null, new IdentifierSeedMatcher(),
                GlobalRetrievalOptions.defaults()).retrieve("How does placeOrder work?", corpusId);

        assertEquals(RetrievalResult.Status.MATCHED, result.status());
        RetrievedItem community = result.items().getFirst();
        assertEquals(RetrievalStep.Kind.COMMUNITY, community.kind());
        assertEquals(communityOf(methodIdentity(PLACE_ORDER)), community.identifier());

        List<RetrievedItem> members = items(result, RetrievalStep.Kind.ENTITY);
        assertEquals(methodIdentity(PLACE_ORDER), members.getFirst().identifier());
        assertEquals("src/main/java/com/shop/order/OrderService.java:10-20", members.getFirst().locator().format());
        assertTrue(items(result, RetrievalStep.Kind.TEXT_UNIT).stream().allMatch(item -> item.locator() != null));
        assertEquals(result.items().stream().map(RetrievedItem::identifier).toList(),
                result.trace().steps().stream().map(RetrievalStep::identifier).toList());
    }

    private String communityOf(String identity) {
        return store.communityMemberships(corpusId).stream()
                .filter(membership -> membership.entityIdentity().equals(identity))
                .map(CommunityMembership::communityId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(identity + " is in no Community; communities: "
                        + store.communities(corpusId).stream().map(Community::id).toList()));
    }

    private static List<RetrievedItem> items(RetrievalResult result, RetrievalStep.Kind kind) {
        return result.items().stream().filter(item -> item.kind() == kind).toList();
    }
}
