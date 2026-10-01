package dev.rabauer.graphrag.core.domain;

/**
 * A first-class community node in the knowledge graph, with a short title
 * (may be empty for older corpora) and a natural-language summary.
 */
public record Community(String id, String title, String summary) {

    public Community(String id, String summary) {
        this(id, "", summary);
    }

    public Community {
        id = id == null || id.isBlank() ? "community-unknown" : id.trim();
        title = title == null ? "" : title.trim();
        summary = summary == null || summary.isBlank() ? "Community cluster" : summary.trim();
    }
}
