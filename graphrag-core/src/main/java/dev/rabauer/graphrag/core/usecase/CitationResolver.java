package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.ContextItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the inline {@code [n]} citations of a synthesized Local Search
 * answer against the numbered context it was written from (Story 15.2).
 * Kept in core so every LLM adapter gets the same behaviour.
 *
 * <ul>
 *   <li>A marker number that is not a {@code TEXT_UNIT} item of the context
 *       (an Entity, a Relationship, or out of range) is removed.</li>
 *   <li>The kept Text Unit numbers are renumbered {@code 1..k} in order of
 *       first appearance, so {@code [i]} in the text is {@code citations[i-1]}.</li>
 *   <li>Grouped markers ({@code [n, m]}, {@code [n; m]}) and ranges
 *       ({@code [n-m]}, {@code [n–m]}, expanded) become one comma-separated
 *       marker; adjacent ones ({@code [n][m]}) stay adjacent.</li>
 * </ul>
 */
final class CitationResolver {

    /** One number or a {@code -}/{@code –} range, e.g. {@code 4} or {@code 4–6}. */
    private static final String ENTRY = "\\s*\\d+(?:\\s*[-–]\\s*\\d+)?\\s*";

    /**
     * {@code [3]}, {@code [3, 4]}, {@code [3; 4]}, {@code [4-6]}, {@code [4–6]};
     * any horizontal whitespace before it.
     */
    private static final Pattern MARKER = Pattern.compile("[ \\t]*\\[(" + ENTRY + "(?:[,;]" + ENTRY + ")*)\\]");

    /** Ranges wider than this are not expanded (no context is that large). */
    private static final int MAX_RANGE = 100;

    private CitationResolver() {
    }

    /** The resolved answer text and its citations, {@code citations[i-1]} for marker {@code [i]}. */
    record Resolution(String text, List<Citation> citations) {
        Resolution {
            text = text == null ? "" : text;
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    /**
     * @param text            the raw answer text; may be null
     * @param context         the numbered context the answer was written from
     * @param citationsByUnit the citation to report for each Text Unit id of
     *                        the context
     */
    static Resolution resolve(String text, List<ContextItem> context, Map<String, Citation> citationsByUnit) {
        if (text == null || text.isBlank()) {
            return new Resolution("", List.of());
        }
        Map<Integer, String> textUnitByNumber = new HashMap<>();
        if (context != null) {
            for (ContextItem item : context) {
                if (item != null && item.isTextUnit() && item.textUnitId() != null
                        && citationsByUnit.containsKey(item.textUnitId())) {
                    textUnitByNumber.put(item.number(), item.textUnitId());
                }
            }
        }

        Map<String, Integer> newNumberByUnit = new LinkedHashMap<>();
        Matcher matcher = MARKER.matcher(text);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            Set<Integer> kept = new LinkedHashSet<>();
            for (int number : numbers(matcher.group(1))) {
                String unitId = textUnitByNumber.get(number);
                if (unitId != null) {
                    kept.add(newNumberByUnit.computeIfAbsent(unitId, ignored -> newNumberByUnit.size() + 1));
                }
            }
            String replacement = "";
            if (!kept.isEmpty()) {
                String leading = matcher.group().substring(0, matcher.group().indexOf('['));
                replacement = leading + "[" + String.join(", ", kept.stream().map(String::valueOf).toList()) + "]";
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);

        List<Citation> citations = new ArrayList<>();
        for (String unitId : newNumberByUnit.keySet()) {
            citations.add(citationsByUnit.get(unitId));
        }
        return new Resolution(resolved.toString().trim(), citations);
    }

    /** The marker's numbers in order, with ranges expanded ({@code 4-6} is 4, 5, 6). */
    private static List<Integer> numbers(String body) {
        List<Integer> numbers = new ArrayList<>();
        for (String part : body.split("[,;]")) {
            String[] bounds = part.trim().split("\\s*[-–]\\s*");
            int from = parseNumber(bounds[0]);
            int to = bounds.length > 1 ? parseNumber(bounds[1]) : from;
            if (from < 0 || to < from || to - from > MAX_RANGE) {
                numbers.add(from);
                continue;
            }
            for (int number = from; number <= to; number++) {
                numbers.add(number);
            }
        }
        return numbers;
    }

    private static int parseNumber(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
