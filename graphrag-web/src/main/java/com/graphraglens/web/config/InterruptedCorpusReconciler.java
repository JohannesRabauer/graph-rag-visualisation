package com.graphraglens.web.config;

import com.graphraglens.adapter.neo4j.Neo4jCorpusRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Story 12.5: on every startup, flips every {@code CorpusMeta} node still
 * {@code BUILDING} to {@code FAILED} -- a corpus left {@code BUILDING} by a
 * crash mid-ingestion would otherwise stay stuck {@code BUILDING} forever.
 * This gives it a correct, visible terminal status instead: the existing
 * {@link com.graphraglens.web.CorpusController} 409 gate treats {@code
 * FAILED} the same as {@code BUILDING}, so this does not by itself restore
 * any ability to retry that corpus by id.
 *
 * <p>Runs on {@link ApplicationReadyEvent}, explicitly ordered (via
 * {@link Order}) to run after {@link Neo4jConnectivityCheck} -- Spring gives
 * no default ordering guarantee across {@code ApplicationListener} beans
 * reacting to the same event. If {@link Neo4jConnectivityCheck} throws
 * (Neo4j unreachable), Spring's event multicaster does not invoke subsequent
 * listeners for that dispatch, so this listener never runs for that startup
 * attempt -- that falls out of Spring's own listener-exception propagation
 * given correct ordering, not from any guard code here.
 *
 * <p>Delegates the actual sweep to
 * {@link Neo4jCorpusRegistry#reconcileInterruptedCorpora()}, a single bulk
 * Cypher write covering every corpus at once -- never a per-corpus loop
 * over the existing single-id {@code markFailed}.
 */
@Component
@Order(Neo4jConnectivityCheck.ORDER + 1)
public class InterruptedCorpusReconciler implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(InterruptedCorpusReconciler.class);

    private final Neo4jCorpusRegistry corpusRegistry;

    public InterruptedCorpusReconciler(Neo4jCorpusRegistry corpusRegistry) {
        this.corpusRegistry = corpusRegistry;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        long reconciledCount = corpusRegistry.reconcileInterruptedCorpora();
        LOG.info("Reconciled {} interrupted corpus(es) to FAILED on startup.", reconciledCount);
    }
}
