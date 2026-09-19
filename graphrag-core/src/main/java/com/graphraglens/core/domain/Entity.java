package com.graphraglens.core.domain;

import java.util.Locale;

/**
 * A single entity node in the knowledge graph.
 */
public record Entity(String name, String type) {

    public Entity {
        name = name == null ? "" : name.trim();
        type = type == null ? "Unknown" : type.trim();
    }

    public String normalizedIdentity() {
        return name.toLowerCase(Locale.ROOT) + "::" + type.toLowerCase(Locale.ROOT);
    }
}
