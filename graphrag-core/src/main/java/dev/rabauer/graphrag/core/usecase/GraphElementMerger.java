package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Attributes;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Merges repeat sightings of graph elements within one extraction run.
 */
public final class GraphElementMerger {

    /** The longest description an Entity or Relationship keeps. */
    public static final int DESCRIPTION_LIMIT = 1_000;

    private GraphElementMerger() {
    }

    public static Entity merge(Entity first, Entity next) {
        return merge(first, next, DESCRIPTION_LIMIT);
    }

    /**
     * {@link #merge(Entity, Entity)} keeping up to {@code descriptionLimit}
     * characters of description, for example to summarise them later.
     */
    public static Entity merge(Entity first, Entity next, int descriptionLimit) {
        if (first == null) {
            return next;
        }
        if (next == null) {
            return first;
        }
        return new Entity(first.name(), first.type(),
                mergeDescriptions(first.description(), next.description(), descriptionLimit),
                union(first.sourceTextUnitIds(), next.sourceTextUnitIds()),
                Attributes.union(first.attributes(), next.attributes()),
                first.locator() != null ? first.locator() : next.locator());
    }

    public static Relationship merge(Relationship first, Relationship next) {
        return merge(first, next, DESCRIPTION_LIMIT);
    }

    /**
     * {@link #merge(Relationship, Relationship)} keeping up to
     * {@code descriptionLimit} characters of description.
     */
    public static Relationship merge(Relationship first, Relationship next, int descriptionLimit) {
        if (first == null) {
            return next;
        }
        if (next == null) {
            return first;
        }
        List<String> ids = union(first.sourceTextUnitIds(), next.sourceTextUnitIds());
        return new Relationship(first.source(), first.sourceType(), first.type(), first.target(), first.targetType(),
                mergeDescriptions(first.description(), next.description(), descriptionLimit), ids, ids.size(),
                Attributes.union(first.attributes(), next.attributes()),
                first.locator() != null ? first.locator() : next.locator());
    }

    /**
     * Merges two sightings of the same Relationship from an exact source
     * (for example two call sites of one method): descriptions and source
     * Text Units are joined like {@link #merge(Relationship, Relationship)},
     * but the weights are summed instead of recounted from the Text Units.
     */
    public static Relationship mergeSummingWeights(Relationship first, Relationship next) {
        if (first == null) {
            return next;
        }
        if (next == null) {
            return first;
        }
        return new Relationship(first.source(), first.sourceType(), first.type(), first.target(), first.targetType(),
                mergeDescriptions(first.description(), next.description()),
                union(first.sourceTextUnitIds(), next.sourceTextUnitIds()), first.weight() + next.weight(),
                Attributes.union(first.attributes(), next.attributes()),
                first.locator() != null ? first.locator() : next.locator());
    }

    public static String mergeDescriptions(String existing, String added) {
        return mergeDescriptions(existing, added, DESCRIPTION_LIMIT);
    }

    /**
     * Appends the sentences of {@code added} not already in
     * {@code existing} while the result stays within {@code limit}
     * characters; later sentences that do not fit are dropped.
     */
    public static String mergeDescriptions(String existing, String added, int limit) {
        String result = normalizeDescription(existing);
        if (result.length() >= limit || added == null || added.isBlank()) {
            return cap(result, limit);
        }
        for (String sentence : splitSentences(added)) {
            if (sentence.isBlank() || containsSentence(result, sentence)) {
                continue;
            }
            String candidate = result.isBlank() ? sentence : result + " " + sentence;
            if (candidate.length() > limit) {
                if (result.isBlank()) {
                    return cap(sentence, limit);
                }
                break;
            }
            result = candidate;
        }
        return result;
    }

    /** {@code description} trimmed and cut to {@link #DESCRIPTION_LIMIT} characters. */
    public static String capDescription(String description) {
        return cap(description, DESCRIPTION_LIMIT);
    }

    private static String normalizeDescription(String description) {
        return description == null ? "" : description.trim();
    }

    private static String cap(String description, int limit) {
        if (description == null) {
            return "";
        }
        String trimmed = description.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private static List<String> splitSentences(String description) {
        String trimmed = normalizeDescription(description);
        if (trimmed.isBlank()) {
            return List.of();
        }
        return List.of(trimmed.split("(?<=[.!?])\\s+")).stream()
                .map(String::trim)
                .filter(sentence -> !sentence.isBlank())
                .toList();
    }

    private static boolean containsSentence(String existing, String sentence) {
        String normalizedSentence = sentence.trim().toLowerCase(Locale.ROOT);
        return splitSentences(existing).stream()
                .map(existingSentence -> existingSentence.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalizedSentence::equals);
    }

    private static List<String> union(List<String> first, List<String> second) {
        List<String> ids = new ArrayList<>();
        addDistinct(ids, first);
        addDistinct(ids, second);
        return ids;
    }

    private static void addDistinct(List<String> ids, List<String> candidates) {
        if (candidates == null) {
            return;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && !ids.contains(candidate)) {
                ids.add(candidate);
            }
        }
    }
}
