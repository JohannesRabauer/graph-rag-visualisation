package com.graphraglens.core.domain;

import java.util.List;

/**
 * LLM extraction result for one document.
 *
 * @param entities extracted entities
 * @param relationships extracted relationships
 */
public record ExtractionResult(List<ExtractedEntity> entities, List<ExtractedRelationship> relationships) {
}
