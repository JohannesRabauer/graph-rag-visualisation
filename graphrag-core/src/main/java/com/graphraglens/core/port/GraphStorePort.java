package com.graphraglens.core.port;

import com.graphraglens.core.domain.ExtractedEntity;
import com.graphraglens.core.domain.ExtractedRelationship;

/**
 * Port for persisting and querying the knowledge graph.
 */
public interface GraphStorePort {

    /**
     * Merge one entity node.
     *
     * @param entity extracted entity
     */
    void mergeEntity(ExtractedEntity entity);

    /**
     * Merge one relationship edge.
     *
     * @param relationship extracted relationship
     */
    void mergeRelationship(ExtractedRelationship relationship);
}
