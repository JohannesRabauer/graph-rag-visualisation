package com.graphraglens.web.config;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the fail-fast guarantee itself, not just that
 * {@link Neo4jConnectivityCheck} compiles: starting a (narrow, non-web)
 * Spring context whose only bean is a {@link Driver} pointed at an
 * unreachable address must abort startup rather than silently continuing.
 *
 * <p>Deliberately does not use the full {@code GraphRagLensApplication}
 * context (that would need a reachable Neo4j itself, per
 * {@code CorpusControllerTest}/{@code UiTestSupport}'s shared Testcontainers
 * setup) — this test only needs {@link Neo4jConnectivityCheck} wired against
 * a broken {@link Driver} to prove the "never a silent fallback" boundary.
 */
class Neo4jConnectivityCheckTest {

    @Test
    void anUnreachableNeo4jAbortsStartupInsteadOfSilentlyContinuing() {
        // Relies on a Spring Boot behavior, not just this class's own logic:
        // an ApplicationListener exception thrown while handling
        // ApplicationReadyEvent propagates out of SpringApplication.run(...)
        // and aborts startup. A future Spring Boot upgrade that swallowed or
        // rerouted that exception instead would make this assumption visible
        // by failing this test, rather than silently.
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(UnreachableNeo4jConfig.class)
                    .web(WebApplicationType.NONE)
                    .run()) {
                // Startup is expected to fail before this line is ever reached.
            }
        }).isNotNull();
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
        Neo4jConnectivityCheck neo4jConnectivityCheck(Driver driver) {
            return new Neo4jConnectivityCheck(driver);
        }
    }
}
