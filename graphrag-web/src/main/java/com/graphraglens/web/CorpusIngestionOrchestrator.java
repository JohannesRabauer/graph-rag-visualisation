package com.graphraglens.web;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.IngestionProgressEvent;
import com.graphraglens.core.usecase.ConstructKnowledgeGraph;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronously runs corpus ingestion/extraction and publishes progress.
 */
@Component
public class CorpusIngestionOrchestrator {

    private final ConstructKnowledgeGraph constructKnowledgeGraph;
    private final IngestionProgressBroker progressBroker;
    private final ExecutorService executorService;

    public CorpusIngestionOrchestrator(
            ConstructKnowledgeGraph constructKnowledgeGraph,
            IngestionProgressBroker progressBroker) {
        this.constructKnowledgeGraph = constructKnowledgeGraph;
        this.progressBroker = progressBroker;
        this.executorService = Executors.newCachedThreadPool();
    }

    public void start(Corpus corpus) {
        executorService.submit(() -> run(corpus));
    }

    private void run(Corpus corpus) {
        try {
            constructKnowledgeGraph.construct(corpus, event -> progressBroker.publish(corpus.id(), event));
        } catch (RuntimeException ex) {
            progressBroker.publish(corpus.id(), new IngestionProgressEvent(
                    "ingestion-error",
                    Map.of(
                            "corpusId", corpus.id(),
                            "message", ex.getMessage() == null ? "Ingestion failed." : ex.getMessage())));
        }
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }
}
