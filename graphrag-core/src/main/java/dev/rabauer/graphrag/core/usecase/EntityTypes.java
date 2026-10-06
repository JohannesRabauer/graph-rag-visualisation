package dev.rabauer.graphrag.core.usecase;

import java.util.List;
import java.util.Locale;

/**
 * The default Entity type list text extraction runs against — one source of
 * truth shared by the use case and the LLM adapters' prompts.
 *
 * <p>Entity types are free-form strings in the domain model. This list only
 * constrains LLM extraction from prose; {@code ExtractEntitiesAndRelationships}
 * accepts another list, and imported graphs (see {@code ImportKnowledgeGraph})
 * are never normalised against it.
 */
public final class EntityTypes {

    /** The fallback type for anything off-list or blank. */
    public static final String CONCEPT = "Concept";

    /** All allowed Entity types, in their canonical spelling and order. */
    public static final List<String> ALL = List.of(
            "Person", "Organization", "Product", "Technology", "Version", "Event", "Location", CONCEPT);

    private EntityTypes() {
    }

    /**
     * Maps a raw type to its canonical spelling, matching case-insensitively;
     * any off-list or blank type becomes {@link #CONCEPT}.
     */
    public static String normalize(String type) {
        if (type == null || type.isBlank()) {
            return CONCEPT;
        }
        String candidate = type.trim().toLowerCase(Locale.ROOT);
        for (String allowed : ALL) {
            if (allowed.toLowerCase(Locale.ROOT).equals(candidate)) {
                return allowed;
            }
        }
        return CONCEPT;
    }

    /**
     * Maps a raw type to its spelling in {@code allowed}, matching
     * case-insensitively; any off-list or blank type becomes the last entry
     * of {@code allowed} (the catch-all, like {@link #CONCEPT} in {@link #ALL}).
     * A null or empty {@code allowed} means {@link #ALL}.
     */
    public static String normalize(String type, List<String> allowed) {
        if (allowed == null || allowed.isEmpty() || allowed.equals(ALL)) {
            return normalize(type);
        }
        String fallback = allowed.getLast().trim();
        if (type == null || type.isBlank()) {
            return fallback;
        }
        String candidate = type.trim().toLowerCase(Locale.ROOT);
        for (String entry : allowed) {
            if (entry != null && entry.trim().toLowerCase(Locale.ROOT).equals(candidate)) {
                return entry.trim();
            }
        }
        return fallback;
    }
}
