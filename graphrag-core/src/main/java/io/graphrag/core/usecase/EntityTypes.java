package io.graphrag.core.usecase;

import java.util.List;
import java.util.Locale;

/**
 * The fixed Entity type list every extraction runs against — one source of
 * truth shared by the use case and the LLM adapters' prompts.
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
}
