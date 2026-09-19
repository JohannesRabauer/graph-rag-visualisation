package com.graphraglens.core.domain;

/**
 * A first-class community node in the knowledge graph.
 */
public record Community(String id, String summary) {
    public Community {
        id = id == null || id.isBlank() ? "community-unknown" : id.trim();
        summary = summary == null || summary.isBlank() ? "Community cluster" : summary.trim();
    }
}
