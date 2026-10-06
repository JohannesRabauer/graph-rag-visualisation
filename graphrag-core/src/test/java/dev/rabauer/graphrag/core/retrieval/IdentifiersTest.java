package dev.rabauer.graphrag.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentifiersTest {

    @Test
    void normalizesSeparatorsCaseAndParameterLists() {
        assertEquals("com.acme.orderservice.placeorder", Identifiers.normalize("com.acme.OrderService#placeOrder(Order)"));
        assertEquals("orderservice.placeorder", Identifiers.normalize("OrderService::placeOrder"));
        assertEquals("outer.inner", Identifiers.normalize("Outer$Inner"));
        assertEquals("com.acme.orderservice", Identifiers.normalize("com/acme/OrderService"));
        assertEquals("", Identifiers.normalize(null));
    }

    @Test
    void splitsCamelSnakeAcronymsAndDigits() {
        assertEquals(List.of("get", "http", "response", "code"), Identifiers.parts("getHTTPResponseCode"));
        assertEquals(List.of("place", "order"), Identifiers.parts("place_order"));
        assertEquals(List.of("order", "service", "place"), Identifiers.parts("OrderService.placeOrder(Order)"));
        // Letters and digits split; one-character words are dropped.
        assertEquals(List.of("utf", "base", "64"), Identifiers.parts("utf8 base64 v2"));
        assertEquals(List.of(), Identifiers.parts(" "));
        assertEquals(List.of("com", "acme", "order", "service"), Identifiers.parts("com.acme.OrderService"));
    }

    @Test
    void segmentsAreTheNormalisedDotSegments() {
        assertEquals(List.of("com", "acme", "orderservice", "placeorder"),
                Identifiers.segments("com.acme.OrderService#placeOrder(Order)"));
    }

    @Test
    void findsIdentifiersInTextKeepingQualifiedOnesWhole() {
        assertEquals(List.of("Who", "calls", "OrderService.placeOrder", "and", "Repo::save"),
                Identifiers.identifiers("Who calls OrderService.placeOrder and Repo::save?"));
        assertEquals(List.of("placeOrder(Order)"), Identifiers.identifiers("placeOrder(Order)"));
    }

    @Test
    void tellsCodeLikeTokensFromWords() {
        assertTrue(Identifiers.isCodeLike("placeOrder"));
        assertTrue(Identifiers.isCodeLike("OrderService"));
        assertTrue(Identifiers.isCodeLike("order_service"));
        assertTrue(Identifiers.isCodeLike("a.b"));
        assertFalse(Identifiers.isCodeLike("Where"));
        assertFalse(Identifiers.isCodeLike("orders"));
    }

    @Test
    void computesEditDistance() {
        assertEquals(1, Identifiers.editDistance("placeorder", "placeordr"));
        assertEquals(0, Identifiers.editDistance("save", "save"));
        assertEquals(3, Identifiers.editDistance("kitten", "sitting"));
    }
}
