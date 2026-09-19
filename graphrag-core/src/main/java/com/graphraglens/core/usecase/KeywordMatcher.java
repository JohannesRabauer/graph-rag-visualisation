package com.graphraglens.core.usecase;

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
        int score = 0;
        for (String token : tokens) {
            if (lowered.contains(token)) {
                score += TOKEN_MATCH_SCORE;
            }
        }
        return score;
    }
}
