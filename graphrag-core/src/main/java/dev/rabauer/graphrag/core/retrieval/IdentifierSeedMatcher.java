package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.port.GraphReadPort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Seeds for questions about code: matches the identifiers in a question
 * ({@code placeOrder}, {@code OrderService}, {@code OrderService.placeOrder},
 * {@code order_service}, {@code OrderService::placeOrder}) against Entity
 * names such as {@code com.acme.order.OrderService#placeOrder(Order)}.
 *
 * <p>Names are compared in {@link Identifiers#normalize(String) normalised}
 * form (case-insensitive, {@code ::}/{@code #}/{@code $} as {@code .}, no
 * parameter list). Per code-like token of the question, an Entity name scores:
 * <ul>
 *   <li>{@value #EXACT}: the whole qualified name matches;</li>
 *   <li>{@value #SUFFIX} (+{@value #CASE_BONUS} when the case matches too):
 *       the name ends with the token on a segment boundary — the simple name
 *       ({@code placeOrder}), or a qualified suffix
 *       ({@code OrderService.placeOrder});</li>
 *   <li>{@value #SEGMENT}: the token is another segment of the name (the owner
 *       class of a method, or a package);</li>
 *   <li>up to {@value #FUZZY}: the simple name is within a small edit distance
 *       (one edit from 5 characters, two from 10);</li>
 *   <li>up to {@value #PARTS}: at least half of the token's camel-case words
 *       ({@link Identifiers#parts(String)}) appear in the name.</li>
 * </ul>
 * The best token counts, plus {@value #WORD} per plain question word (not a
 * stop word; a trailing plural "s" is ignored) found among the name's words.
 * Entities scoring at least {@value #MIN_SCORE} match, best first, ties in
 * stored order. Besides {@link Entity#name()}, the values of the configured
 * attribute keys (by default {@code qualifiedName}, {@code simpleName},
 * {@code signature}) are matched too.
 */
public final class IdentifierSeedMatcher implements SeedMatcher {

    static final int EXACT = 100;
    static final int SUFFIX = 90;
    static final int CASE_BONUS = 5;
    static final int SEGMENT = 50;
    static final int FUZZY = 40;
    static final int PARTS = 25;
    static final int WORD = 6;
    static final int MAX_WORD_SCORE = 24;
    static final int MIN_SCORE = 6;

    /** Attribute keys matched besides the Entity name by the no-argument constructor. */
    public static final List<String> DEFAULT_NAME_ATTRIBUTES = List.of("qualifiedName", "simpleName", "signature");

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "from", "with", "what", "which", "where", "who", "whom", "whose", "how", "why",
            "when", "does", "did", "doe", "are", "was", "were", "been", "being", "has", "have", "had", "can",
            "could", "should", "would", "will", "call", "calls", "called", "calling", "caller", "callers", "use",
            "uses", "used", "using", "method", "methods", "class", "classes", "function", "functions", "type",
            "types", "this", "that", "these", "those", "there", "here", "into", "onto", "about", "all", "any",
            "show", "find", "list", "code", "implement", "implements", "implemented", "implementation", "not",
            "its", "it's", "our", "your", "their", "them", "they", "then", "than", "also", "via", "per", "each");

    private final List<String> nameAttributes;

    public IdentifierSeedMatcher() {
        this(DEFAULT_NAME_ATTRIBUTES);
    }

    /** @param nameAttributes attribute keys whose values are matched like the Entity name */
    public IdentifierSeedMatcher(List<String> nameAttributes) {
        this.nameAttributes = nameAttributes == null ? List.of() : List.copyOf(nameAttributes);
    }

    @Override
    public List<SeedMatch> match(String question, String corpusId, GraphReadPort graph, int limit) {
        Query query = Query.of(question);
        Collection<Entity> entities = graph.entities(corpusId);
        if (query.isEmpty() || entities == null || limit < 1) {
            return List.of();
        }
        List<SeedMatch> matches = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity == null) {
                continue;
            }
            double score = score(entity, query);
            if (score >= MIN_SCORE) {
                matches.add(new SeedMatch(entity, score, "identifier"));
            }
        }
        // List.sort is stable, so ties keep stored order.
        matches.sort(Comparator.comparingDouble(SeedMatch::score).reversed());
        return List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
    }

    /** The score of {@code entity} for {@code question}; 0 when nothing matches. */
    public double score(Entity entity, String question) {
        return score(entity, Query.of(question));
    }

    private double score(Entity entity, Query query) {
        double best = 0;
        for (String name : names(entity)) {
            best = Math.max(best, nameScore(name, query));
        }
        return best;
    }

    private List<String> names(Entity entity) {
        List<String> names = new ArrayList<>();
        names.add(entity.name());
        for (String key : nameAttributes) {
            entity.attribute(key).filter(value -> !value.isBlank()).ifPresent(names::add);
        }
        return names;
    }

    private static double nameScore(String name, Query query) {
        String normalized = Identifiers.normalize(name);
        if (normalized.isEmpty()) {
            return 0;
        }
        List<String> segments = Identifiers.segments(name);
        String simple = segments.getLast();
        Set<String> nameParts = new HashSet<>(Identifiers.parts(name));
        String caseKept = caseKept(name);

        double best = 0;
        for (Token token : query.tokens()) {
            double score = 0;
            if (normalized.equals(token.normalized())) {
                score = EXACT;
            } else if (normalized.endsWith("." + token.normalized())) {
                score = SUFFIX + (caseKept.endsWith(token.caseKept()) ? CASE_BONUS : 0);
            } else if (token.segments().size() == 1 && segments.contains(token.normalized())) {
                score = SEGMENT;
            } else {
                score = Math.max(fuzzyScore(simple, token), partsScore(nameParts, token));
            }
            best = Math.max(best, score);
        }
        int words = 0;
        for (String word : query.words()) {
            if (nameParts.contains(word) || (word.endsWith("s") && nameParts.contains(word.substring(0, word.length() - 1)))) {
                words++;
            }
        }
        return best + Math.min(MAX_WORD_SCORE, words * WORD);
    }

    private static double fuzzyScore(String simple, Token token) {
        if (token.segments().size() != 1 || token.normalized().length() < 5) {
            return 0;
        }
        int maxDistance = token.normalized().length() >= 10 ? 2 : 1;
        if (Math.abs(simple.length() - token.normalized().length()) > maxDistance) {
            return 0;
        }
        int distance = Identifiers.editDistance(simple, token.normalized());
        return distance <= maxDistance ? FUZZY - 10.0 * distance : 0;
    }

    private static double partsScore(Set<String> nameParts, Token token) {
        if (token.parts().size() < 2) {
            return 0;
        }
        long shared = token.parts().stream().filter(nameParts::contains).count();
        double overlap = (double) shared / token.parts().size();
        return overlap >= 0.5 ? PARTS * overlap : 0;
    }

    /** The name with unified separators and without parameters, case kept. */
    private static String caseKept(String identifier) {
        int open = identifier.indexOf('(');
        String withoutParameters = open >= 0 ? identifier.substring(0, open) : identifier;
        return withoutParameters.trim().replace("::", ".").replace('#', '.').replace('$', '.').replace('/', '.');
    }

    /** One code-like token of the question. */
    private record Token(String normalized, String caseKept, List<String> segments, List<String> parts) {
    }

    /** The code-like tokens and plain words of a question. */
    private record Query(List<Token> tokens, Set<String> words) {

        static Query of(String question) {
            List<Token> tokens = new ArrayList<>();
            Set<String> words = new LinkedHashSet<>();
            for (String raw : Identifiers.identifiers(question)) {
                if (Identifiers.isCodeLike(raw)) {
                    String normalized = Identifiers.normalize(raw);
                    if (!normalized.isEmpty()) {
                        tokens.add(new Token(normalized, caseKept(raw), Identifiers.segments(raw),
                                Identifiers.parts(raw)));
                    }
                } else {
                    String word = raw.toLowerCase(Locale.ROOT);
                    if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
                        words.add(word);
                    }
                }
            }
            return new Query(List.copyOf(tokens), words);
        }

        boolean isEmpty() {
            return tokens.isEmpty() && words.isEmpty();
        }
    }
}
