package io.graphrag.core.domain;

import java.util.List;

/**
 * A typed relationship between two entity nodes, including its concise
 * description, source Text Units, and provenance-derived weight.
 */
public record Relationship(String source, String sourceType, String type, String target, String targetType,
                           String description, List<String> sourceTextUnitIds, int weight) {

    public Relationship(String source, String sourceType, String type, String target, String targetType) {
        this(source, sourceType, type, target, targetType, "", List.of(), 1);
    }

    public Relationship {
        source = source == null ? "" : source.trim();
        sourceType = sourceType == null ? "Unknown" : sourceType.trim();
        type = type == null ? "related_to" : type.trim();
        target = target == null ? "" : target.trim();
        targetType = targetType == null ? "Unknown" : targetType.trim();
        description = description == null ? "" : description.trim();
        sourceTextUnitIds = sourceTextUnitIds == null ? List.of() : List.copyOf(sourceTextUnitIds);
        weight = Math.max(1, weight);
    }
}
