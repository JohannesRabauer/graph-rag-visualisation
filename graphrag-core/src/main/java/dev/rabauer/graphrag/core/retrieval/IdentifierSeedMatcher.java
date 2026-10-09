package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.port.GraphReadPort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 *       class of a method);</li>
 *   <li>{@value #PREFIX}: a qualified token ({@code com.acme.order}) is the
 *       start of the name, i.e. the name lives in the package or class asked
 *       for;</li>
 *   <li>{@value #PACKAGE_SEGMENT}: the token is a package segment of the name
 *       (below {@value #MIN_SCORE}, so alone it never matches);</li>
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
 *
 * <p><b>Package segments do not count.</b> The lower-case segments in front of
 * the first segment that contains an upper-case letter are a package
 * ({@code org.pulsar.broker} of {@code org.pulsar.broker.service.BrokerService}).
 * They are not among the name's words, and a token equal to one scores only
 * {@value #PACKAGE_SEGMENT}, so {@code BrokerService} no longer half-matches
 * every class in a package called {@code broker}. A name without an
 * upper-case segment (Python or Go modules) has no package: nothing is
 * demoted.
 *
 * <p><b>Names are indexed once per corpus.</b> Normalising every name is the
 * expensive part of a question, so the matcher keeps, per corpus id, the
 * normalised forms of the Entities it saw last. They are reused as long as
 * {@link GraphReadPort#entities(String)} returns equal Entities in the same
 * order; any change rebuilds that corpus's index. Reuse one matcher instance
 * across questions (it is thread-safe).
 */
public final class IdentifierSeedMatcher implements SeedMatcher {

    static final int EXACT = 100;
    static final int SUFFIX = 90;
    static final int CASE_BONUS = 5;
    static final int SEGMENT = 50;
    static final int PREFIX = 30;
    static final int PACKAGE_SEGMENT = 3;
    static final int FUZZY = 40;
    static final int PARTS = 25;
    static final int WORD = 6;
    static final int MAX_WORD_SCORE = 24;
    static final int MIN_SCORE = 6;

    /** Attribute keys matched besides the Entity name by the no-argument constructor. */
    public static final List<String> DEFAULT_NAME_ATTRIBUTES = List.of("qualifiedName", "simpleName", "signature");

    /** Corpora whose index is kept; beyond this the cache is cleared. */
    private static final int MAX_INDEXED_CORPORA = 16;

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "from", "with", "what", "which", "where", "who", "whom", "whose", "how", "why",
            "when", "does", "did", "doe", "are", "was", "were", "been", "being", "has", "have", "had", "can",
            "could", "should", "would", "will", "call", "calls", "called", "calling", "caller", "callers", "use",
            "uses", "used", "using", "method", "methods", "class", "classes", "function", "functions", "type",
            "types", "this", "that", "these", "those", "there", "here", "into", "onto", "about", "all", "any",
            "show", "find", "list", "code", "implement", "implements", "implemented", "implementation", "not",
            "its", "it's", "our", "your", "their", "them", "they", "then", "than", "also", "via", "per", "each");

    private final List<String> nameAttributes;
    private final Map<String, Index> indexes = new ConcurrentHashMap<>();
    private final AtomicInteger indexBuilds = new AtomicInteger();

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
        for (Indexed indexed : indexFor(corpusId, entities).rows()) {
            double score = score(indexed.names(), query);
            if (score >= MIN_SCORE) {
                matches.add(new SeedMatch(indexed.entity(), score, "identifier"));
            }
        }
        // List.sort is stable, so ties keep stored order.
        matches.sort(Comparator.comparingDouble(SeedMatch::score).reversed());
        return List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
    }

    /** The score of {@code entity} for {@code question}; 0 when nothing matches. */
    public double score(Entity entity, String question) {
        return score(forms(entity), Query.of(question));
    }

    /** How many times an index was built (for tests and diagnostics). */
    int indexBuilds() {
        return indexBuilds.get();
    }

    /** The corpus's index: the cached one while the Entities are unchanged, else a new one. */
    private Index indexFor(String corpusId, Collection<Entity> entities) {
        String key = corpusId == null ? "" : corpusId;
        List<Entity> current = entities.stream().filter(entity -> entity != null).toList();
        Index cached = indexes.get(key);
        if (cached != null && cached.isFor(current)) {
            return cached;
        }
        List<Indexed> rows = new ArrayList<>(current.size());
        for (Entity entity : current) {
            rows.add(new Indexed(entity, forms(entity)));
        }
        Index built = new Index(current, List.copyOf(rows));
        indexBuilds.incrementAndGet();
        if (cached == null && indexes.size() >= MAX_INDEXED_CORPORA) {
            indexes.clear();
        }
        indexes.put(key, built);
        return built;
    }

    private List<NameForms> forms(Entity entity) {
        List<NameForms> forms = new ArrayList<>();
        addForms(forms, entity.name());
        for (String key : nameAttributes) {
            entity.attribute(key).filter(value -> !value.isBlank()).ifPresent(value -> addForms(forms, value));
        }
        return List.copyOf(forms);
    }

    private static void addForms(List<NameForms> forms, String name) {
        NameForms form = NameForms.of(name);
        if (form != null) {
            forms.add(form);
        }
    }

    private static double score(List<NameForms> names, Query query) {
        double best = 0;
        for (NameForms name : names) {
            best = Math.max(best, nameScore(name, query));
        }
        return best;
    }

    private static double nameScore(NameForms name, Query query) {
        double best = 0;
        for (Token token : query.tokens()) {
            double score;
            if (name.normalized().equals(token.normalized())) {
                score = EXACT;
            } else if (name.normalized().endsWith("." + token.normalized())) {
                score = SUFFIX + (name.caseKept().endsWith(token.caseKept()) ? CASE_BONUS : 0);
            } else if (token.segments().size() == 1 && name.memberSegments().contains(token.normalized())) {
                score = SEGMENT;
            } else if (token.segments().size() > 1 && name.normalized().startsWith(token.normalized() + ".")) {
                score = PREFIX;
            } else if (token.segments().size() == 1 && name.packageSegments().contains(token.normalized())) {
                score = PACKAGE_SEGMENT;
            } else {
                score = Math.max(fuzzyScore(name.simple(), token), partsScore(name.parts(), token));
            }
            best = Math.max(best, score);
        }
        int words = 0;
        for (String word : query.words()) {
            if (name.parts().contains(word)
                    || (word.endsWith("s") && name.parts().contains(word.substring(0, word.length() - 1)))) {
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

    /** The Entities of one corpus as last read, with their precomputed name forms. */
    private record Index(List<Entity> entities, List<Indexed> rows) {

        /** Whether {@code current} holds the same Entities in the same order. */
        boolean isFor(List<Entity> current) {
            if (current.size() != entities.size()) {
                return false;
            }
            Iterator<Entity> known = entities.iterator();
            for (Entity entity : current) {
                Entity before = known.next();
                if (entity != before && !entity.equals(before)) {
                    return false;
                }
            }
            return true;
        }
    }

    private record Indexed(Entity entity, List<NameForms> names) {
    }

    /**
     * One name in the forms scoring compares: normalised, its simple name, its
     * segments split into package and member segments, its words without the
     * package's, and the case-kept spelling.
     */
    private record NameForms(String normalized, String simple, List<String> memberSegments,
                             Set<String> packageSegments, Set<String> parts, String caseKept) {

        /** The forms of {@code name}; null when it normalises to nothing. */
        static NameForms of(String name) {
            String normalized = Identifiers.normalize(name);
            if (normalized.isEmpty()) {
                return null;
            }
            List<String> segments = Identifiers.segments(name);
            String caseKept = IdentifierSeedMatcher.caseKept(name);
            List<String> caseSegments = Arrays.stream(caseKept.split("\\.")).filter(part -> !part.isEmpty()).toList();
            int packageCount = packageSegmentCount(caseSegments, segments);
            Set<String> parts = new HashSet<>(Identifiers.parts(
                    String.join(".", caseSegments.subList(packageCount, caseSegments.size()))));
            return new NameForms(normalized, segments.getLast(), segments.subList(packageCount, segments.size()),
                    Set.copyOf(segments.subList(0, packageCount)), parts, caseKept);
        }

        /** How many lower-case segments stand in front of the first one with an upper-case letter. */
        private static int packageSegmentCount(List<String> caseSegments, List<String> segments) {
            if (caseSegments.size() != segments.size()) {
                return 0;
            }
            for (int i = 0; i < caseSegments.size(); i++) {
                if (caseSegments.get(i).chars().anyMatch(Character::isUpperCase)) {
                    return i;
                }
            }
            return 0;
        }
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
