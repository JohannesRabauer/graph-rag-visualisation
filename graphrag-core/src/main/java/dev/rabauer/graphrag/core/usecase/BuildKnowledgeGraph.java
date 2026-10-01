package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;

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
