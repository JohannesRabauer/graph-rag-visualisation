package dev.rabauer.graphrag.web.config;

import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.web.SharedNeo4jTestContainer;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the Story 12.5 ordering guarantee itself, not just that
 * {@link InterruptedCorpusReconciler} compiles: mirrors
 * {@link Neo4jConnectivityCheckTest}'s narrow, non-web Spring-context
 * pattern.
 *
 * <p>Reuses {@link SharedNeo4jTestContainer}'s JVM-wide singleton container
 * (rather than its own {@code @Testcontainers}/{@code @Container} pair) for
 * the reachable-Neo4j scenario, exactly as {@code CorpusControllerTest} and
 * this module's other Spring-context tests do -- this module's tests are
 * confirmed to run against a real Docker daemon in this dev environment
 * (unlike {@code graphrag-adapter-neo4j}'s {@code @Testcontainers}-annotated
 * tests, tracked separately in {@code deferred-work.md}).
 *
 * <p>Two behaviors are asserted, each against its own fresh context: with a
 * reachable Neo4j, both listeners run, {@link Neo4jConnectivityCheck}
 * observably before the reconciliation listener; with an unreachable Neo4j,
 * startup aborts and the reconciliation listener never runs at all.
 */
class InterruptedCorpusReconcilerTest {

    private static final List<String> INVOCATION_ORDER = new CopyOnWriteArrayList<>();

    @Test
    void bothListenersRunTogetherWithConnectivityCheckObservablyFirst() {
        INVOCATION_ORDER.clear();

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ReachableNeo4jConfig.class)
                .web(WebApplicationType.NONE)
                .run()) {
            // Startup completing normally means neither listener threw.
        }

        assertThat(INVOCATION_ORDER).containsExactly("connectivity-check", "reconciliation");
    }

    @Test
    void anUnreachableNeo4jAbortsStartupAndTheReconciliationListenerNeverRuns() {
        INVOCATION_ORDER.clear();

        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(UnreachableNeo4jConfig.class)
                    .web(WebApplicationType.NONE)
                    .run()) {
                // Startup is expected to fail before this line is ever reached.
            }
        }).isNotNull();

        assertThat(INVOCATION_ORDER).containsExactly("connectivity-check");
    }

    @Configuration
    static class ReachableNeo4jConfig {

        // destroyMethod = "" is essential: Driver implements AutoCloseable,
        // so without this Spring would infer close() as this bean's destroy
        // method and call it when this test's context shuts down -- but this
        // returns SharedNeo4jTestContainer's JVM-wide singleton Driver, not
        // a context-owned instance, so that would break every other test in
        // this module sharing it for the rest of the JVM's lifetime.
        @Bean(destroyMethod = "")
        Driver driver() {
            return SharedNeo4jTestContainer.driver();
        }

        @Bean
        Neo4jCorpusRegistry neo4jCorpusRegistry(Driver driver) {
            return new Neo4jCorpusRegistry(driver);
        }

        @Bean
        OrderRecordingConnectivityCheck neo4jConnectivityCheck(Driver driver) {
            return new OrderRecordingConnectivityCheck(driver);
        }

        @Bean
        OrderRecordingReconciler interruptedCorpusReconciler(Neo4jCorpusRegistry registry) {
            return new OrderRecordingReconciler(registry);
        }
    }

    @Configuration
    static class UnreachableNeo4jConfig {

        @Bean
        Driver driver() {
            // Nothing listens on this port; connection attempts fail fast
            // (connection refused) rather than hanging.
            return GraphDatabase.driver("bolt://127.0.0.1:1", AuthTokens.basic("neo4j", "wrong-password"));
        }

        @Bean
        OrderRecordingConnectivityCheck neo4jConnectivityCheck(Driver driver) {
            return new OrderRecordingConnectivityCheck(driver);
        }

        // Deliberately no Neo4jCorpusRegistry/reconciler bean here: with a
        // driver that never connects, constructing a real
        // Neo4jCorpusRegistry would itself attempt a constraint-creation
        // call. This recorder stands in for the reconciliation listener's
        // presence/order without needing one, which is enough to prove it
        // never fires when ordered after a throwing connectivity check.
        @Bean
        RecordingOnlyReconciler interruptedCorpusReconciler() {
            return new RecordingOnlyReconciler();
        }
    }

    /**
     * Delegates to the real {@link Neo4jConnectivityCheck} logic while
     * recording that it ran, so this test observes actual ordering rather
     * than merely asserting a copy of the production {@code @Order} value.
     * Carries the same {@code @Order} as the production class ({@code
     * @Order} is not inherited by subclasses in Spring's ordering).
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class OrderRecordingConnectivityCheck extends Neo4jConnectivityCheck {

        OrderRecordingConnectivityCheck(Driver driver) {
            super(driver);
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            INVOCATION_ORDER.add("connectivity-check");
            super.onApplicationEvent(event);
        }
    }

    /**
     * Delegates to the real {@link InterruptedCorpusReconciler} logic while
     * recording that it ran. Carries the same {@code @Order} as the
     * production class.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    static class OrderRecordingReconciler extends InterruptedCorpusReconciler {

        OrderRecordingReconciler(Neo4jCorpusRegistry corpusRegistry) {
            super(corpusRegistry);
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            INVOCATION_ORDER.add("reconciliation");
            super.onApplicationEvent(event);
        }
    }

    /**
     * A stand-in used only in the unreachable-Neo4j scenario, where a real
     * {@link Neo4jCorpusRegistry} cannot safely be constructed. Same
     * {@code @Order} as the production reconciler; records if it ever
     * fires.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    static class RecordingOnlyReconciler implements org.springframework.context.ApplicationListener<ApplicationReadyEvent> {

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            INVOCATION_ORDER.add("reconciliation");
        }
    }
}
