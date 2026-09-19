package com.graphraglens.core.domain;

/**
 * One extracted relationship candidate between two entities.
 *
 * @param source source entity
 * @param target target entity
 * @param type relationship type label
 */
public record ExtractedRelationship(ExtractedEntity source, ExtractedEntity target, String type) {
}
