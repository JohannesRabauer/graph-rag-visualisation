package dev.rabauer.graphrag.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.RetrievalTrace;
import dev.rabauer.graphrag.core.domain.SourceLocator;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retrieval-only results are plain records: Jackson 2 (Spring Boot 3)
 * and Jackson 3 (Spring Boot 4) write the documented trace schema and read it
 * back unchanged, without any annotation or custom module.
 */
class RetrievalResultJsonTest {

    private static final SourceLocator CALL_SITE = SourceLocator.of("src/main/java/com/shop/web/OrderController.java", 22);

    private static RetrievalResult sample() {
        RetrievedItem entity = new RetrievedItem(1, RetrievalStep.Kind.ENTITY, "com.shop.orderservice#placeorder::method",
                "com.shop.OrderService#placeOrder", "com.shop.OrderService#placeOrder (Method)",
                SourceLocator.of("src/main/java/com/shop/OrderService.java", 20, 35), Map.of("kind", "method"), 96, 0);
        RetrievedItem relationship = new RetrievedItem(2, RetrievalStep.Kind.RELATIONSHIP,
                "a::method->CALLS->b::method", "a —CALLS→ b", "a -[CALLS]-> b", CALL_SITE, Map.of(), 3, 1);
        RetrievalTrace trace = new RetrievalTrace("", List.of(
                new RetrievalStep(RetrievalStep.Kind.ENTITY, entity.identifier(), entity.label(), entity.locator(),
                        entity.attributes()),
                new RetrievalStep(RetrievalStep.Kind.RELATIONSHIP, relationship.identifier(), relationship.label(),
                        CALL_SITE, Map.of())));
        return new RetrievalResult(RetrievalResult.Mode.LOCAL, "Who calls placeOrder?", "shop",
                RetrievalResult.Status.MATCHED, "", List.of(entity, relationship), trace, List.of());
    }

    @Test
    void jackson2WritesTheDocumentedSchemaAndReadsItBack() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RetrievalResult result = sample();

        String json = mapper.writeValueAsString(result);
        JsonNode tree = mapper.readTree(json);

        assertThat(tree.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "mode", "question", "corpusId", "status", "reason", "items", "trace", "warnings");
        JsonNode item = tree.path("items").path(0);
        assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("number", "kind", "identifier", "label",
                "text", "locator", "attributes", "score", "hop");
        assertThat(item.path("locator").path("path").asText()).isEqualTo("src/main/java/com/shop/OrderService.java");
        assertThat(item.path("locator").path("startLine").asInt()).isEqualTo(20);
        assertThat(item.path("locator").path("endLine").asInt()).isEqualTo(35);
        assertThat(tree.path("trace").path("steps").path(1).path("locator").path("startLine").asInt()).isEqualTo(22);
        assertThat(mapper.readValue(json, RetrievalResult.class)).isEqualTo(result);
    }

    @Test
    void jackson3ReadsBackWhatItWrites() {
        JsonMapper mapper = JsonMapper.builder().build();
        RetrievalResult result = sample();

        assertThat(mapper.readValue(mapper.writeValueAsString(result), RetrievalResult.class)).isEqualTo(result);
    }
}
