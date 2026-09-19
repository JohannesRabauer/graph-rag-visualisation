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
     * Runs extraction and persistence exactly as {@link #run(Corpus)} does, additionally
     * invoking the given optional callbacks once per persisted Entity/Relationship. Callbacks
     * are invoked after the whole batch has been persisted, in extraction order. Either or both
     * callbacks may be {@code null}, in which case they are simply skipped — this keeps the
     * web layer free to observe progress without core coupling to any transport.
     */
    public void run(Corpus corpus, Consumer<Entity> onEntityPersisted, Consumer<Relationship> onRelationshipPersisted) {
        GraphExtraction extraction = extract(corpus);
        graphStorePort.persist(extraction);

        if (onEntityPersisted != null && extraction.entities() != null) {
            for (Entity entity : extraction.entities()) {
                onEntityPersisted.accept(entity);
            }
        }

        if (onRelationshipPersisted != null && extraction.relationships() != null) {
            for (Relationship relationship : extraction.relationships()) {
                onRelationshipPersisted.accept(relationship);
            }
        }
    }
}
