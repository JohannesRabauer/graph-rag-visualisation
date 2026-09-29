package com.graphraglens.web.config;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Fails startup loudly if Neo4j is unreachable, rather than letting the app
 * silently fall back to any in-memory behavior.
 *
 * <p>Neither {@link com.graphraglens.adapter.neo4j.Neo4jGraphStoreAdapter}
 * nor {@link com.graphraglens.adapter.neo4j.Neo4jVectorStoreAdapter} can be
 * relied on to catch this themselves: their constructors declare Neo4j
 * constraints but swallow {@code Neo4jException} (including connectivity
 * failures) as a mere warning log, by design, so that missing/degraded
 * constraint support never blocks startup. So this listener runs
 * {@link Driver#verifyConnectivity()} explicitly, once the application is
 * otherwise ready, and aborts startup if it fails.
 *
 * <p>Runs on {@link ApplicationReadyEvent} (there is no existing precedent
 * for this kind of check in this codebase — unlike {@code OPENAI_API_KEY},
 * which has no startup check and instead fails lazily on first use). Any
 * failure here is thrown back out of the listener; Spring's application
 * event multicaster propagates that exception out of
 * {@code SpringApplication.run(...)}, so the JVM exits with a non-zero
 * status shortly after startup.
 *
 * <p>This is a best-effort fail-fast, not a hard pre-listen guarantee: the
 * embedded servlet container starts accepting connections during context
 * refresh, which happens before {@link ApplicationReadyEvent} fires. So a
 * narrow window exists, between the port opening and this check running,
 * during which HTTP traffic could in principle reach the app before a
 * broken Neo4j connection is detected. In practice that window is small and
 * this still aborts startup well before the app would otherwise appear
 * healthy.
 */
@Component
public class Neo4jConnectivityCheck implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(Neo4jConnectivityCheck.class);

    private final Driver driver;

    public Neo4jConnectivityCheck(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            driver.verifyConnectivity();
        } catch (RuntimeException e) {
            LOG.error("Cannot connect to Neo4j at startup -- check NEO4J_URI/NEO4J_USERNAME/NEO4J_PASSWORD "
                    + "and that Neo4j is reachable. Aborting startup: {}", e.getMessage(), e);
            throw new IllegalStateException("Cannot connect to Neo4j at startup; aborting.", e);
        }
        LOG.info("Neo4j connectivity verified.");
    }
}
