package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.SourceLocator;
import dev.rabauer.graphrag.core.domain.TextUnit;

import java.util.List;
import java.util.Map;

/**
 * A small order-handling code graph for retrieval tests: classes, an
 * interface and methods with locators and snippets, and CALLS / IMPLEMENTS /
 * DECLARES edges with call counts as weights.
 *
 * <pre>
 * OrderController#create --CALLS(3)--> OrderService#placeOrder
 * OrderService#placeOrder --CALLS(2)--> OrderRepository#save
 * OrderService#placeOrder --CALLS(1)--> PaymentService#charge
 * JpaOrderRepository --IMPLEMENTS--> OrderRepository
 * OrderService --DECLARES--> OrderService#placeOrder (and so on for each method)
 * </pre>
 */
final class SmallCodeGraph {

    static final String CORPUS = "shop";
    static final String SERVICE = "com.shop.order.OrderService";
    static final String PLACE_ORDER = "com.shop.order.OrderService#placeOrder(Order)";
    static final String REPOSITORY = "com.shop.order.OrderRepository";
    static final String SAVE = "com.shop.order.OrderRepository#save(Order)";
    static final String JPA_REPOSITORY = "com.shop.order.JpaOrderRepository";
    static final String CONTROLLER = "com.shop.web.OrderController";
    static final String CREATE = "com.shop.web.OrderController#create(OrderRequest)";
    static final String PAYMENT = "com.shop.payment.PaymentService";
    static final String CHARGE = "com.shop.payment.PaymentService#charge(Order)";

    private SmallCodeGraph() {
    }

    static SourceLocator at(String file, int start, int end) {
        return SourceLocator.of("src/main/java/" + file, start, end);
    }

    static Entity type(String name, String kind, SourceLocator locator, String unit) {
        return new Entity(name, kind.equals("interface") ? "Interface" : "Class", "", List.of(unit),
                Map.of("kind", kind), locator);
    }

    static Entity method(String name, SourceLocator locator, String unit) {
        return new Entity(name, "Method", "", List.of(unit), Map.of("kind", "method"), locator);
    }

    static Relationship edge(String source, String sourceType, String type, String target, String targetType,
                             int weight, SourceLocator locator) {
        return new Relationship(source, sourceType, type, target, targetType, "", List.of(), weight,
                Map.of(), locator);
    }

    static TextUnit snippet(String id, String text, SourceLocator locator) {
        return new TextUnit(id, CORPUS, locator.path(), 0, text, Map.of(), locator);
    }

    static TestGraphStore store() {
        TestGraphStore store = new TestGraphStore();
        SourceLocator serviceAt = at("com/shop/order/OrderService.java", 10, 60);
        SourceLocator placeOrderAt = at("com/shop/order/OrderService.java", 20, 35);
        SourceLocator repositoryAt = at("com/shop/order/OrderRepository.java", 5, 12);
        SourceLocator saveAt = at("com/shop/order/OrderRepository.java", 8, 8);
        SourceLocator jpaAt = at("com/shop/order/JpaOrderRepository.java", 7, 40);
        SourceLocator controllerAt = at("com/shop/web/OrderController.java", 12, 50);
        SourceLocator createAt = at("com/shop/web/OrderController.java", 20, 28);
        SourceLocator paymentAt = at("com/shop/payment/PaymentService.java", 9, 44);
        SourceLocator chargeAt = at("com/shop/payment/PaymentService.java", 15, 30);

        store.persistTextUnits(CORPUS, List.of(
                snippet("tu-service", "public class OrderService { ... }", serviceAt),
                snippet("tu-placeOrder", "public Order placeOrder(Order order) { payments.charge(order); "
                        + "return repository.save(order); }", placeOrderAt),
                snippet("tu-repository", "public interface OrderRepository { Order save(Order order); }",
                        repositoryAt),
                snippet("tu-save", "Order save(Order order);", saveAt),
                snippet("tu-jpa", "class JpaOrderRepository implements OrderRepository { ... }", jpaAt),
                snippet("tu-controller", "public class OrderController { ... }", controllerAt),
                snippet("tu-create", "public Order create(OrderRequest request) { return service.placeOrder("
                        + "request.toOrder()); }", createAt),
                snippet("tu-payment", "public class PaymentService { ... }", paymentAt),
                snippet("tu-charge", "public void charge(Order order) { ... }", chargeAt)));
        store.persistEntities(CORPUS, List.of(
                type(SERVICE, "class", serviceAt, "tu-service"),
                method(PLACE_ORDER, placeOrderAt, "tu-placeOrder"),
                type(REPOSITORY, "interface", repositoryAt, "tu-repository"),
                method(SAVE, saveAt, "tu-save"),
                type(JPA_REPOSITORY, "class", jpaAt, "tu-jpa"),
                type(CONTROLLER, "class", controllerAt, "tu-controller"),
                method(CREATE, createAt, "tu-create"),
                type(PAYMENT, "class", paymentAt, "tu-payment"),
                method(CHARGE, chargeAt, "tu-charge")));
        store.persistRelationships(CORPUS, List.of(
                edge(CREATE, "Method", "CALLS", PLACE_ORDER, "Method", 3, at("com/shop/web/OrderController.java", 22, 22)),
                edge(PLACE_ORDER, "Method", "CALLS", SAVE, "Method", 2, at("com/shop/order/OrderService.java", 30, 30)),
                edge(PLACE_ORDER, "Method", "CALLS", CHARGE, "Method", 1, at("com/shop/order/OrderService.java", 25, 25)),
                edge(JPA_REPOSITORY, "Class", "IMPLEMENTS", REPOSITORY, "Interface", 1, jpaAt),
                edge(SERVICE, "Class", "DECLARES", PLACE_ORDER, "Method", 1, placeOrderAt),
                edge(REPOSITORY, "Interface", "DECLARES", SAVE, "Method", 1, saveAt),
                edge(CONTROLLER, "Class", "DECLARES", CREATE, "Method", 1, createAt),
                edge(PAYMENT, "Class", "DECLARES", CHARGE, "Method", 1, chargeAt)));
        return store;
    }

    static String id(String name, String type) {
        return Entity.identityOf(name, type);
    }
}
