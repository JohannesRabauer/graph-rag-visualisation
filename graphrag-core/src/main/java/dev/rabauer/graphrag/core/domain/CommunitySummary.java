package dev.rabauer.graphrag.core.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * The generated title and summary of one Community. Null values become
 * empty strings; callers decide how to fall back.
 */
public record CommunitySummary(String title, String summary) {

    /** Maximum number of words kept in a Community title. */
    public static final int MAX_TITLE_WORDS = 6;

    public CommunitySummary {
        title = title == null ? "" : title.trim();
        summary = summary == null ? "" : summary.trim();
    }

    /**
     * Trims {@code title} to at most {@link #MAX_TITLE_WORDS} whitespace-separated
     * words; null or blank becomes {@code ""}.
     */
    public static String trimTitle(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String[] words = title.trim().split("\\s+");
        return String.join(" ", words.length <= MAX_TITLE_WORDS ? words : Arrays.copyOf(words, MAX_TITLE_WORDS));
    }

    /**
     * The deterministic title: the first two distinct non-blank member names
     * joined with " &amp; ", trimmed to six words, or "Related entities" when
     * there are no names.
     */
    public static String deterministicTitle(Collection<Entity> members) {
        if (members == null) {
            return "Related entities";
        }
        String joined = members.stream()
                .filter(Objects::nonNull)
                .map(Entity::name)
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .distinct()
                .limit(2)
                .reduce((left, right) -> left + " & " + right)
                .orElse("");
        String title = trimTitle(joined);
        return title.isEmpty() ? "Related entities" : title;
    }
}
