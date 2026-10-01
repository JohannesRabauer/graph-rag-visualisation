package io.graphrag.core.domain;

import java.util.Locale;
import java.util.List;

/**
 * A single entity node in the knowledge graph, including its concise
 * description and the Text Units where it was mentioned.
 */
public record Entity(String name, String type, String description, List<String> sourceTextUnitIds) {

    public Entity(String name, String type) {
        this(name, type, "", List.of());
    }

    public Entity {
        name = name == null ? "" : name.trim();
        type = type == null ? "Unknown" : type.trim();
        description = description == null ? "" : description.trim();
        sourceTextUnitIds = sourceTextUnitIds == null ? List.of() : List.copyOf(sourceTextUnitIds);
    }

    public String normalizedIdentity() {
        return name.toLowerCase(Locale.ROOT) + "::" + type.toLowerCase(Locale.ROOT);
    }

    /**
     * Shorthand for {@code new Entity(name, type).normalizedIdentity()} —
     * used wherever only a Relationship's source/target name+type (not a
     * full Entity) is on hand and an identity string is needed to match it
     * against a rendered node (e.g. {@code CorpusController}'s
     * relationship payloads).
     */
    public static String identityOf(String name, String type) {
        return new Entity(name, type).normalizedIdentity();
    }
}
