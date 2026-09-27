package io.graphrag.core.usecase;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Shared keyword-overlap tokenizing/scoring used by both {@link AnswerGlobalSearch}
 * (Community summaries) and the web layer's Local Search sentence matching, so the
 * two heuristics stay identical and deterministic.
 *
 * <p>{@link #tokenize(String)} deduplicates tokens (a {@link Set}) so a keyword
 * repeated in the question contributes to a candidate's score only once, rather
 * than once per occurrence.
 */
public final class KeywordMatcher {

    private static final int TOKEN_MATCH_SCORE = 2;

    /**
     * Awarded when a token doesn't literally appear in the candidate but is
     * within a small edit distance of one of the candidate's own words —
     * tolerates a minor typo or phrasing slip (Story 9.2) without ever
     * outscoring — or being mistaken for — a real substring match.
     */
    private static final int FUZZY_MATCH_SCORE = 1;

    private KeywordMatcher() {
    }

    public static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String token : lower.split("[^a-z0-9]+")) {
            if (!token.isBlank() && token.length() > 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    public static int score(String candidate, Set<String> tokens) {
        if (candidate == null) {
            return 0;
        }
        String lowered = candidate.toLowerCase(Locale.ROOT);
        Set<String> candidateWords = tokenize(candidate);
        int score = 0;
        for (String token : tokens) {
            if (lowered.contains(token)) {
                score += TOKEN_MATCH_SCORE;
            } else if (fuzzyMatches(token, candidateWords)) {
                score += FUZZY_MATCH_SCORE;
            }
        }
        return score;
    }

    /**
     * True when {@code token} is a small edit distance away from one of
     * {@code candidateWords} — the tolerance grows with word length so a
     * short token (where one edit changes its meaning entirely) stays
     * exact-only, while a longer one can absorb a single typo, a doubled
     * letter, or a simple pluralization the substring check alone would
     * miss (e.g. a question saying "Shelock" for an Entity named
     * "Sherlock Holmes").
     */
    private static boolean fuzzyMatches(String token, Set<String> candidateWords) {
        int maxDistance = maxDistanceFor(token.length());
        if (maxDistance == 0) {
            return false;
        }
        for (String word : candidateWords) {
            if (Math.abs(word.length() - token.length()) > maxDistance) {
                continue;
            }
            if (levenshteinDistance(token, word) <= maxDistance) {
                return true;
            }
        }
        return false;
    }

    private static int maxDistanceFor(int tokenLength) {
        if (tokenLength >= 7) {
            return 2;
        }
        if (tokenLength >= 4) {
            return 1;
        }
        return 0;
    }

    private static int levenshteinDistance(String a, String b) {
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
}
