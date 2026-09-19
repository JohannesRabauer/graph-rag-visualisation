package com.graphraglens.core.domain;

import java.util.List;

/**
 * Result of a single graph-construction pass over a corpus.
 */
public record GraphExtraction(List<Entity> entities, List<Relationship> relationships) {

    public GraphExtraction {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
    }
}
