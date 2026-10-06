package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Merges repeat sightings of graph elements within one extraction run.
 */
public final class GraphElementMerger {

    private static final int DESCRIPTION_LIMIT = 1_000;

    private GraphElementMerger() {
    }

    public static Entity merge(Entity first, Entity next) {
        if (first == null) {
            return next;
        }
        if (next == null) {
            return first;
        }
        return new Entity(first.name(), first.type(),
                mergeDescriptions(first.description(), next.description()),
                union(first.sourceTextUnitIds(), next.sourceTextUnitIds()));
    }

    public static Relationship merge(Relationship first, Relationship next) {
        if (first == null) {
            return next;
        }
        if (next == null) {
            return first;
        }
        List<String> ids = union(first.sourceTextUnitIds(), next.sourceTextUnitIds());
        return new Relationship(first.source(), first.sourceType(), first.type(), first.target(), first.targetType(),
                mergeDescriptions(first.description(), next.description()), ids, ids.size());
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
                union(first.sourceTextUnitIds(), next.sourceTextUnitIds()), first.weight() + next.weight());
    }

    public static String mergeDescriptions(String existing, String added) {
        String result = normalizeDescription(existing);
        if (result.length() >= DESCRIPTION_LIMIT || added == null || added.isBlank()) {
            return cap(result);
        }
        for (String sentence : splitSentences(added)) {
            if (sentence.isBlank() || containsSentence(result, sentence)) {
                continue;
            }
            String candidate = result.isBlank() ? sentence : result + " " + sentence;
            if (candidate.length() > DESCRIPTION_LIMIT) {
                if (result.isBlank()) {
                    return cap(sentence);
                }
                break;
            }
            result = candidate;
        }
        return result;
    }

    private static String normalizeDescription(String description) {
        return description == null ? "" : description.trim();
    }

    private static String cap(String description) {
        if (description == null) {
            return "";
        }
        String trimmed = description.trim();
        return trimmed.length() <= DESCRIPTION_LIMIT ? trimmed : trimmed.substring(0, DESCRIPTION_LIMIT);
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
