package com.graphraglens.core.domain;

/**
 * One extracted entity candidate from corpus text.
 *
 * @param name entity surface name
 * @param type entity type/category
 */
public record ExtractedEntity(String name, String type) {

    public String normalizedKey() {
        return (name == null ? "" : name.trim().toLowerCase()) + "::" + (type == null ? "" : type.trim().toLowerCase());
    }
}
