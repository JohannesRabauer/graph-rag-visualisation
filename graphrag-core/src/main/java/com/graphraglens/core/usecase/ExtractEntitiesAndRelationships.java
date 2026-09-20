package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.GraphExtraction;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;

import java.util.function.Consumer;

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
        run(corpus, null, null);
    }

    /**
     * Persists the extraction, then reports the persisted Entities/Relationships to the
     * optional callbacks, one invocation per item, after persistence has completed —
     * a callback that throws can no longer prevent already-persisted data from landing
     * in the graph store.
     */
    public void run(Corpus corpus, Consumer<Entity> onEntityPersisted, Consumer<Relationship> onRelationshipPersisted) {
        GraphExtraction extraction = extract(corpus);
        graphStorePort.persist(corpus.id(), extraction);

        if (onEntityPersisted != null && extraction != null && extraction.entities() != null) {
            for (Entity entity : extraction.entities()) {
                onEntityPersisted.accept(entity);
            }
        }

        if (onRelationshipPersisted != null && extraction != null && extraction.relationships() != null) {
            for (Relationship relationship : extraction.relationships()) {
                onRelationshipPersisted.accept(relationship);
            }
        }
    }
}
