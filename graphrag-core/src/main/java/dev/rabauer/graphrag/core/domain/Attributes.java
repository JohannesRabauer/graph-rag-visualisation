package dev.rabauer.graphrag.core.domain;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Normalises the free-form {@code attributes} of the domain records: null
 * becomes empty, entries with a null or blank key or a null value are
 * dropped, keys are trimmed, and the result is an unmodifiable map sorted by
 * key, so equality and serialized JSON are stable.
 */
public final class Attributes {

    private Attributes() {
    }

    /** The normalised, unmodifiable, key-sorted copy of {@code attributes}. */
    public static Map<String, String> normalize(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            sorted.put(entry.getKey().trim(), entry.getValue());
        }
        return sorted.isEmpty() ? Map.of() : Collections.unmodifiableMap(sorted);
    }

    /**
     * {@code first}'s attributes plus every key of {@code second} that
     * {@code first} lacks (first wins on conflicts).
     */
    public static Map<String, String> union(Map<String, String> first, Map<String, String> second) {
        if (second == null || second.isEmpty()) {
            return normalize(first);
        }
        if (first == null || first.isEmpty()) {
            return normalize(second);
        }
        TreeMap<String, String> merged = new TreeMap<>(normalize(second));
        merged.putAll(normalize(first));
        return Collections.unmodifiableMap(merged);
    }
}
