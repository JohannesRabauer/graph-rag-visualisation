package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.GraphExtraction;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;

/**
 * Compatibility alias for the knowledge-graph construction use case.
 */
public class BuildKnowledgeGraph extends ExtractEntitiesAndRelationships {

    public BuildKnowledgeGraph(LlmPort llmPort, GraphStorePort graphStorePort) {
        super(llmPort, graphStorePort);
    }

    public GraphExtraction build(Corpus corpus) {
        return extract(corpus);
    }
}
