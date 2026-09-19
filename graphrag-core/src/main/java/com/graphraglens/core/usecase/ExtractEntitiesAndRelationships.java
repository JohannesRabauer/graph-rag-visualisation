package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.GraphExtraction;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;

/**
 * Executes the knowledge-graph extraction pass for a corpus.
 */
public class ExtractEntitiesAndRelationships {

    private final LlmPort llmPort;
    private final GraphStorePort graphStorePort;

    public ExtractEntitiesAndRelationships(LlmPort llmPort, GraphStorePort graphStorePort) {
        this.llmPort = llmPort;
        this.graphStorePort = graphStorePort;
    }

    public GraphExtraction extract(Corpus corpus) {
        return llmPort.extract(corpus);
    }

    public void run(Corpus corpus) {
        GraphExtraction extraction = extract(corpus);
        graphStorePort.persist(extraction);
    }
}
