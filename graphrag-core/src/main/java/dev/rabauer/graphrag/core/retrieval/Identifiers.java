package dev.rabauer.graphrag.core.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splitting and normalising code identifiers, shared by
 * {@link IdentifierSeedMatcher} and callers that want the same rules.
 *
 * <ul>
 *   <li>{@link #normalize(String)}: lower-cases, drops a parameter list
 *       ({@code placeOrder(Order)} → {@code placeorder}) and turns
 *       {@code ::}, {@code #}, {@code $} and {@code /} into {@code .}, so
 *       {@code OrderService::placeOrder}, {@code OrderService#placeOrder} and
 *       {@code OrderService.placeOrder} are the same.</li>
 *   <li>{@link #parts(String)}: splits on separators, {@code _}, {@code -}
 *       and camel-case humps ({@code getHTTPResponseCode} → {@code get},
 *       {@code http}, {@code response}, {@code code}).</li>
 *   <li>{@link #identifiers(String)}: the identifier-like tokens of free
 *       text, qualified ones kept whole.</li>
 * </ul>
 */
public final class Identifiers {

    /** An identifier, optionally qualified by {@code .}, {@code ::}, {@code #} or {@code $}, with an optional parameter list. */
    private static final Pattern IDENTIFIER = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:(?:\\.|::|#)[A-Za-z_$][A-Za-z0-9_$]*)*(?:\\([^()]*\\))?");

    private static final Pattern CAMEL_BOUNDARY = Pattern.compile(
            "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])");

    private Identifiers() {
    }

    /**
     * The identifier's canonical form: lower case, without a parameter list,
     * with {@code ::}, {@code #}, {@code $} and {@code /} as {@code .}.
     */
    public static String normalize(String identifier) {
        if (identifier == null) {
            return "";
        }
        String withoutParameters = stripParameters(identifier.trim());
        return withoutParameters.replace("::", ".").replace('#', '.').replace('$', '.').replace('/', '.')
                .replaceAll("\\.+", ".").replaceAll("^\\.|\\.$", "").toLowerCase(Locale.ROOT);
    }

    /** The dot-separated segments of {@link #normalize(String)}, e.g. {@code [com, acme, orderservice, placeorder]}. */
    public static List<String> segments(String identifier) {
        String normalized = normalize(identifier);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return List.of(normalized.split("\\."));
    }

    /**
     * The lower-case words of an identifier: split on separators,
     * {@code _}, {@code -}, whitespace and camel-case humps; words shorter
     * than 2 characters are dropped.
     */
    public static List<String> parts(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return List.of();
        }
        Set<String> parts = new LinkedHashSet<>();
        for (String chunk : stripParameters(identifier).split("[^A-Za-z0-9]+")) {
            for (String word : CAMEL_BOUNDARY.split(chunk)) {
                String lower = word.toLowerCase(Locale.ROOT);
                if (lower.length() >= 2) {
                    parts.add(lower);
                }
            }
        }
        return List.copyOf(parts);
    }

    /** The identifier-like tokens of {@code text}, in order of appearance, qualified ones kept whole. */
    public static List<String> identifiers(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * Whether {@code token} looks like code rather than a plain word: it is
     * qualified, has a parameter list, an underscore or {@code $}, or an
     * upper-case letter after its first character ({@code placeOrder},
     * {@code OrderService}).
     */
    public static boolean isCodeLike(String token) {
        if (token == null || token.length() < 2) {
            return false;
        }
        if (token.contains(".") || token.contains("::") || token.contains("#") || token.contains("(")
                || token.contains("_") || token.contains("$")) {
            return true;
        }
        for (int i = 1; i < token.length(); i++) {
            if (Character.isUpperCase(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Levenshtein edit distance. */
    public static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static String stripParameters(String identifier) {
        int open = identifier.indexOf('(');
        return open >= 0 ? identifier.substring(0, open) : identifier;
    }
}
