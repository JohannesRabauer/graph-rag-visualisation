package io.graphrag.core.domain;

/**
 * A typed relationship between two entity nodes.
 */
public record Relationship(String source, String sourceType, String type, String target, String targetType) {

    public Relationship {
        source = source == null ? "" : source.trim();
        sourceType = sourceType == null ? "Unknown" : sourceType.trim();
        type = type == null ? "related_to" : type.trim();
        target = target == null ? "" : target.trim();
        targetType = targetType == null ? "Unknown" : targetType.trim();
    }
}
