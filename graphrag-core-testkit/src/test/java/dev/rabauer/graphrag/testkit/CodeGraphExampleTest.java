package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.community.ModularityCommunityDetector;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SourceLocator;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.retrieval.GlobalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.LocalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;
import dev.rabauer.graphrag.core.retrieval.SeedMatchers;
import dev.rabauer.graphrag.core.usecase.CommunityDetectionResult;
import dev.rabauer.graphrag.core.usecase.DetectCommunities;
import dev.rabauer.graphrag.core.usecase.ImportKnowledgeGraph;
import dev.rabauer.graphrag.core.usecase.ImportResult;
import dev.rabauer.graphrag.core.usecase.RetrieveGlobalContext;
import dev.rabauer.graphrag.core.usecase.RetrieveLocalContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code code-graph} example: the whole non-text path, offline, step by
 * step, as an application such as a codebase indexer would run it — import an
 * exact graph from a code scan (no LLM extraction), detect Communities with
 * the core detector (no GDS), then answer an agent's camelCase question with
 * retrieval only (no synthesis).
 */
class CodeGraphExampleTest {

    @Test
    void importDetectAndRetrieveACodeGraphWithoutAnyModel() {
        // 1. The scan's output: 50 classes, their methods, DECLARES/IMPLEMENTS/CALLS with locators.
        CodeGraphFixture scan = CodeGraphFixture.create();
        InMemoryGraphStore store = new InMemoryGraphStore();

        // 2. Import it as-is. LlmPort.none(): no extraction, deterministic Community summaries.
        ImportResult imported = new ImportKnowledgeGraph(store, LlmPort.none(), null).run("shop", scan.graph(),
                ImportKnowledgeGraph.Options.defaults().withCommunities(DetectCommunities.Options.defaults()
                        .withDetector(new ModularityCommunityDetector(
                                ModularityCommunityDetector.Options.defaults().withSeed(7)))));

        assertEquals(150, imported.entities());
        CommunityDetectionResult detection = imported.detection();
        assertEquals(CommunityDetectionResult.Status.COMPLETE, detection.status());
        assertEquals(detection.communities().size(), detection.count(CommunityDetectionResult.SummaryStatus.DETERMINISTIC));
        assertTrue(detection.communities().size() >= 4);

        // 3. "Who calls placeOrder?" — callers only, along CALLS, with code snippets.
        RetrievalResult callers = new RetrieveLocalContext(store, SeedMatchers.forCode(null),
                LocalRetrievalOptions.defaults().withSeedLimit(1)
                        .withDirection(LocalRetrievalOptions.Direction.INCOMING)
                        .withIncludeRelationshipTypes(Set.of("CALLS")))
                .retrieve("Who calls placeOrder?", "shop");

        List<String> trace = callers.trace().steps().stream()
                .map(step -> step.kind() + " " + step.identifier() + " @ " + format(step.locator()))
                .toList();
        assertEquals("ENTITY com.shop.order.orderservice#placeorder(order)::method"
                + " @ src/main/java/com/shop/order/OrderService.java:10-20", trace.get(0));
        assertEquals("RELATIONSHIP com.shop.web.ordercontroller#createorder(orderrequest)::method"
                + "->CALLS->com.shop.order.orderservice#placeorder(order)::method"
                + " @ src/main/java/com/shop/web/OrderController.java:19", trace.get(1));
        assertEquals("ENTITY com.shop.web.ordercontroller#createorder(orderrequest)::method"
                + " @ src/main/java/com/shop/web/OrderController.java:10-20", trace.get(2));
        RetrievedItem callerSnippet = callers.items().stream()
                .filter(item -> item.kind() == RetrievalStep.Kind.TEXT_UNIT).findFirst().orElseThrow();
        assertTrue(callerSnippet.text().contains("createOrder(OrderRequest)"), callerSnippet::text);

        // 4. A broader question: which part of the system is placeOrder in, and what else is there?
        RetrievalResult overview = new RetrieveGlobalContext(store, null, SeedMatchers.forCode(null),
                GlobalRetrievalOptions.defaults()).retrieve("Explain the placeOrder flow", "shop");

        RetrievedItem community = overview.items().getFirst();
        assertEquals(RetrievalStep.Kind.COMMUNITY, community.kind());
        Community detected = store.communities("shop").stream()
                .filter(candidate -> candidate.id().equals(community.identifier())).findFirst().orElseThrow();
        assertTrue(detected.summary().contains("com.shop.order."), detected::summary);
    }

    private static String format(SourceLocator locator) {
        return locator == null ? "-" : locator.format();
    }
}
