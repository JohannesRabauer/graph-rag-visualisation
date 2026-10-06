package dev.rabauer.graphrag.core.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A typed relationship between two entity nodes, including its concise
 * description, source Text Units, and weight.
 *
 * <p>{@code type} is free-form (for example {@code knows} from text, or
 * {@code CALLS}, {@code IMPLEMENTS}, {@code DEPENDS_ON} from a code scan).
 * {@code weight} is at least 1: the number of Text Units citing it for
 * extracted graphs, or any count the source provides (for example call
 * counts) for imported ones.
 *
 * @param attributes free-form, string-valued facts; never null, sorted by key
 * @param locator    where the Relationship is evidenced (for example the
 *                   first call site); null when unknown
 */
public record Relationship(String source, String sourceType, String type, String target, String targetType,
                           String description, List<String> sourceTextUnitIds, int weight,
                           Map<String, String> attributes, SourceLocator locator) {

    public Relationship(String source, String sourceType, String type, String target, String targetType) {
        this(source, sourceType, type, target, targetType, "", List.of(), 1);
    }

    /** A Relationship without attributes and locator. */
    public Relationship(String source, String sourceType, String type, String target, String targetType,
                        String description, List<String> sourceTextUnitIds, int weight) {
        this(source, sourceType, type, target, targetType, description, sourceTextUnitIds, weight, Map.of(), null);
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
        attributes = Attributes.normalize(attributes);
    }

    /** {@link Entity#identityOf(String, String)} of the source endpoint. */
    public String sourceIdentity() {
        return Entity.identityOf(source, sourceType);
    }

    /** {@link Entity#identityOf(String, String)} of the target endpoint. */
    public String targetIdentity() {
        return Entity.identityOf(target, targetType);
    }

    /** The value of one attribute, if present. */
    public Optional<String> attribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    /** This Relationship with {@code attributes} instead of its own. */
    public Relationship withAttributes(Map<String, String> attributes) {
        return new Relationship(source, sourceType, type, target, targetType, description, sourceTextUnitIds, weight,
                attributes, locator);
    }

    /** This Relationship with {@code locator} instead of its own. */
    public Relationship withLocator(SourceLocator locator) {
        return new Relationship(source, sourceType, type, target, targetType, description, sourceTextUnitIds, weight,
                attributes, locator);
    }

    /**
     * This Relationship with other endpoints, description, Text Units and
     * weight, keeping its type, attributes and locator.
     */
    public Relationship with(String source, String sourceType, String target, String targetType,
                             String description, List<String> sourceTextUnitIds, int weight) {
        return new Relationship(source, sourceType, type, target, targetType, description, sourceTextUnitIds, weight,
                attributes, locator);
    }
}
