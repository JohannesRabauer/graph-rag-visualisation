package dev.rabauer.graphrag.core.domain;

import java.util.Map;

/**
 * A first-class community node in the knowledge graph, with a short title
 * (may be empty for older corpora) and a natural-language summary.
 *
 * @param attributes free-form, string-valued facts, for example
 *                   {@code contentHash} (see {@code DetectCommunities}) or a
 *                   caller's {@code module}; never null, sorted by key
 */
public record Community(String id, String title, String summary, Map<String, String> attributes) {

    public Community(String id, String summary) {
        this(id, "", summary);
    }

    /** A Community without attributes. */
    public Community(String id, String title, String summary) {
        this(id, title, summary, Map.of());
    }

    public Community {
        id = id == null || id.isBlank() ? "community-unknown" : id.trim();
        title = title == null ? "" : title.trim();
        summary = summary == null || summary.isBlank() ? "Community cluster" : summary.trim();
        attributes = Attributes.normalize(attributes);
    }

    /** This Community with {@code attributes} instead of its own. */
    public Community withAttributes(Map<String, String> attributes) {
        return new Community(id, title, summary, attributes);
    }
}
