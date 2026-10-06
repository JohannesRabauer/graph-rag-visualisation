package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.SourceLocator;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.usecase.KnowledgeGraphImport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A deterministic, code-like knowledge graph of 50 classes in 5 packages
 * ({@code com.shop.order}, {@code payment}, {@code inventory},
 * {@code shipping}, {@code web}), each class with two methods — what a
 * bytecode scan of a small service would produce, with no LLM involved.
 *
 * <ul>
 *   <li>Entities: every class or interface ({@code Class}/{@code Interface},
 *       attributes {@code kind}, {@code package}, {@code visibility}) and every
 *       method ({@code Method}, named {@code Owner#method(Params)}), each with a
 *       {@link SourceLocator} and its own snippet Text Unit.</li>
 *   <li>Relationships: {@code DECLARES} (type → method), {@code IMPLEMENTS}
 *       (implementation → interface), and {@code CALLS} between methods,
 *       weighted by call count, with the call site as locator. Calls are dense
 *       inside a package and sparse across packages, so each package is a
 *       clear community.</li>
 * </ul>
 *
 * <p>Known facts the scenario checks: {@link #CREATE_ORDER} calls
 * {@link #PLACE_ORDER} three times at {@link #CREATE_ORDER_CALL_SITE};
 * {@link #PLACE_ORDER} calls the payment, inventory and shipping services.
 */
public final class CodeGraphFixture {

    public static final String CORPUS = "code-graph";

    public static final String ORDER_SERVICE = "com.shop.order.OrderService";
    public static final String PLACE_ORDER = "com.shop.order.OrderService#placeOrder(Order)";
    public static final String CANCEL_ORDER = "com.shop.order.OrderService#cancelOrder(OrderId)";
    public static final String ORDER_CONTROLLER = "com.shop.web.OrderController";
    public static final String CREATE_ORDER = "com.shop.web.OrderController#createOrder(OrderRequest)";
    public static final String CHARGE_PAYMENT = "com.shop.payment.PaymentService#chargePayment(Order)";
    public static final String RESERVE_STOCK = "com.shop.inventory.InventoryService#reserveStock(Order)";
    public static final String CREATE_SHIPMENT = "com.shop.shipping.ShippingService#createShipment(Order)";
    public static final SourceLocator CREATE_ORDER_CALL_SITE =
            SourceLocator.of("src/main/java/com/shop/web/OrderController.java", 19);

    /** Package → its 10 types; names ending in {@code *} are interfaces. */
    private static final Map<String, List<String>> PACKAGES = new LinkedHashMap<>();

    static {
        PACKAGES.put("order", List.of("OrderService", "OrderRepository*", "JpaOrderRepository", "OrderValidator",
                "OrderMapper", "OrderFactory", "OrderEvents", "OrderPolicy", "OrderQueries", "OrderCache"));
        PACKAGES.put("payment", List.of("PaymentService", "PaymentGateway*", "StripePaymentGateway",
                "PaymentValidator", "InvoiceService", "InvoiceRepository*", "RefundService", "CurrencyConverter",
                "PaymentEvents", "FraudCheck"));
        PACKAGES.put("inventory", List.of("InventoryService", "StockRepository*", "JpaStockRepository",
                "StockReservation", "WarehouseLocator", "ReorderPolicy", "InventoryEvents", "SkuMapper", "StockCache",
                "InventoryReport"));
        PACKAGES.put("shipping", List.of("ShippingService", "CarrierClient*", "DhlCarrierClient",
                "ShipmentRepository*", "LabelPrinter", "TrackingService", "RateCalculator", "ShippingEvents",
                "AddressValidator", "DeliveryWindow"));
        PACKAGES.put("web", List.of("OrderController", "PaymentController", "InventoryController",
                "ShippingController", "RequestMapper", "ErrorHandler", "AuthFilter", "SessionStore", "HealthController",
                "ApiDocs"));
    }

    /** Methods with fixed names; every other type gets {@code handle<Type>()} and {@code describe<Type>()}. */
    private static final Map<String, List<String>> METHODS = Map.of(
            "OrderService", List.of("placeOrder(Order)", "cancelOrder(OrderId)"),
            "OrderController", List.of("createOrder(OrderRequest)", "listOrders()"),
            "PaymentService", List.of("chargePayment(Order)", "refundPayment(PaymentId)"),
            "InventoryService", List.of("reserveStock(Order)", "releaseStock(Order)"),
            "ShippingService", List.of("createShipment(Order)", "cancelShipment(ShipmentId)"));

    private final List<TextUnit> textUnits = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();
    private final List<Relationship> relationships = new ArrayList<>();

    private CodeGraphFixture() {
        build();
    }

    /** The fixture (built fresh, deterministic). */
    public static CodeGraphFixture create() {
        return new CodeGraphFixture();
    }

    /** The whole graph, ready for {@code ImportKnowledgeGraph}. */
    public KnowledgeGraphImport graph() {
        return KnowledgeGraphImport.of(textUnits, entities, relationships);
    }

    public List<Entity> entities() {
        return List.copyOf(entities);
    }

    public List<Relationship> relationships() {
        return List.copyOf(relationships);
    }

    public List<TextUnit> textUnits() {
        return List.copyOf(textUnits);
    }

    /** The number of classes and interfaces. */
    public int typeCount() {
        return (int) entities.stream().filter(entity -> !entity.type().equals("Method")).count();
    }

    /** The package ({@code com.shop.order}, …) an Entity name belongs to. */
    public static String packageOf(String name) {
        String type = name.contains("#") ? name.substring(0, name.indexOf('#')) : name;
        return type.substring(0, type.lastIndexOf('.'));
    }

    /** The identity of a method Entity. */
    public static String methodIdentity(String name) {
        return Entity.identityOf(name, "Method");
    }

    private void build() {
        Map<String, List<String>> methodsByType = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> pkg : PACKAGES.entrySet()) {
            String packageName = "com.shop." + pkg.getKey();
            for (String declared : pkg.getValue()) {
                boolean isInterface = declared.endsWith("*");
                String simple = isInterface ? declared.substring(0, declared.length() - 1) : declared;
                String type = packageName + "." + simple;
                String path = "src/main/java/" + packageName.replace('.', '/') + "/" + simple + ".java";
                String typeUnit = "tu:" + type;
                SourceLocator typeAt = SourceLocator.of(path, 1, 60);
                textUnits.add(new TextUnit(typeUnit, CORPUS, path, 0,
                        "public " + (isInterface ? "interface " : "class ") + simple + " { ... }",
                        Map.of("language", "java"), typeAt));
                entities.add(new Entity(type, isInterface ? "Interface" : "Class", simple + " in " + packageName + ".",
                        List.of(typeUnit), Map.of("kind", isInterface ? "interface" : "class",
                        "package", packageName, "visibility", "public"), typeAt));

                List<String> methods = METHODS.getOrDefault(simple,
                        List.of("handle" + simple + "()", "describe" + simple + "()"));
                List<String> names = new ArrayList<>();
                for (int m = 0; m < methods.size(); m++) {
                    String method = type + "#" + methods.get(m);
                    String methodUnit = "tu:" + method;
                    SourceLocator methodAt = SourceLocator.of(path, 10 + 20 * m, 20 + 20 * m);
                    textUnits.add(new TextUnit(methodUnit, CORPUS, path, m + 1,
                            "public Object " + methods.get(m) + " { /* " + simple + " logic */ }",
                            Map.of("language", "java"), methodAt));
                    entities.add(new Entity(method, "Method", "", List.of(methodUnit),
                            Map.of("kind", "method", "package", packageName, "visibility", "public"), methodAt));
                    relationships.add(new Relationship(type, isInterface ? "Interface" : "Class", "DECLARES", method,
                            "Method", "", List.of(typeUnit), 1, Map.of(), methodAt));
                    names.add(method);
                }
                methodsByType.put(type, names);
            }
        }

        // Dense calls inside each package (a cohesive package): type i's first method calls the first
        // methods of types i+1, i+2 and i+4 and the second method of type i+3; its second method calls
        // the first method of type i+5.
        for (Map.Entry<String, List<String>> pkg : PACKAGES.entrySet()) {
            List<String> types = pkg.getValue().stream()
                    .map(declared -> "com.shop." + pkg.getKey() + "." + declared.replace("*", "")).toList();
            int n = types.size();
            for (int i = 0; i < n; i++) {
                List<String> own = methodsByType.get(types.get(i));
                call(own.get(0), methodsByType.get(types.get((i + 1) % n)).get(0), (i % 3) + 1, 15);
                call(own.get(0), methodsByType.get(types.get((i + 2) % n)).get(0), 2, 16);
                call(own.get(0), methodsByType.get(types.get((i + 4) % n)).get(0), 2, 17);
                call(own.get(0), methodsByType.get(types.get((i + 3) % n)).get(1), 1, 18);
                call(own.get(1), methodsByType.get(types.get((i + 5) % n)).get(0), 1, 35);
                implement(types.get(i), types, pkg.getValue());
            }
        }

        // Sparse calls across packages.
        call(CREATE_ORDER, PLACE_ORDER, 3, 19);
        call(PLACE_ORDER, CHARGE_PAYMENT, 1, 19);
        call(PLACE_ORDER, RESERVE_STOCK, 1, 20);
        call(PLACE_ORDER, CREATE_SHIPMENT, 1, 20);
        call("com.shop.web.PaymentController#handlePaymentController()", CHARGE_PAYMENT, 1, 19);
        call("com.shop.web.InventoryController#handleInventoryController()", RESERVE_STOCK, 1, 19);
        call("com.shop.web.ShippingController#handleShippingController()", CREATE_SHIPMENT, 1, 19);
    }

    private void call(String caller, String callee, int count, int line) {
        if (caller.equals(callee)) {
            return;
        }
        String path = "src/main/java/" + caller.substring(0, caller.indexOf('#')).replace('.', '/') + ".java";
        relationships.add(new Relationship(caller, "Method", "CALLS", callee, "Method", "",
                List.of("tu:" + caller), count, Map.of("callCount", String.valueOf(count)),
                SourceLocator.of(path, line)));
    }

    /** {@code JpaOrderRepository} implements {@code OrderRepository}, and so on: Jpa/Stripe/Dhl prefixes. */
    private void implement(String type, List<String> types, List<String> declared) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        for (String candidate : declared) {
            if (!candidate.endsWith("*")) {
                continue;
            }
            String interfaceName = candidate.substring(0, candidate.length() - 1);
            String lower = simple.toLowerCase(Locale.ROOT);
            if (!simple.equals(interfaceName) && lower.endsWith(interfaceName.toLowerCase(Locale.ROOT))) {
                String interfaceType = type.substring(0, type.lastIndexOf('.') + 1) + interfaceName;
                relationships.add(new Relationship(type, "Class", "IMPLEMENTS", interfaceType, "Interface", "",
                        List.of(), 1, Map.of(), SourceLocator.of("src/main/java/" + type.replace('.', '/') + ".java", 1)));
            }
        }
        if (!types.contains(type)) {
            throw new IllegalStateException("unknown type " + type);
        }
    }
}
