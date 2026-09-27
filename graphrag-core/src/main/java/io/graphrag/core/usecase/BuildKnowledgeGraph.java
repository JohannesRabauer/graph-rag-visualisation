package io.graphrag.core.usecase;

import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.port.GraphStorePort;
import io.graphrag.core.port.LlmPort;

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
