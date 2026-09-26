package com.graphraglens.core.usecase;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordMatcherTest {

    @Test
    void exactSubstringMatchScoresTwoPerToken() {
        Set<String> tokens = KeywordMatcher.tokenize("Who is Sherlock Holmes?");

        int score = KeywordMatcher.score("Sherlock Holmes", tokens);

        assertEquals(4, score); // "sherlock" + "holmes", 2 each
    }

    @Test
    void aSingleTypoInALongTokenStillMatchesViaFuzzyFallback() {
        // "shelock" (missing an 'r') is a one-edit typo for "sherlock" —
        // Story 9.2's own motivating example. A single-word candidate
        // isolates the fuzzy path from any substring hit on a second word.
        Set<String> tokens = KeywordMatcher.tokenize("Where is Shelock right now");

        int score = KeywordMatcher.score("Sherlock", tokens);

        assertTrue(score > 0, "expected a non-zero fuzzy-matched score, got " + score);
    }

    @Test
    void aDoubledLetterTypoStillMatches() {
        Set<String> tokens = KeywordMatcher.tokenize("What about Moriartyy?");

        int score = KeywordMatcher.score("Professor Moriarty", tokens);

        assertTrue(score > 0, "expected a non-zero fuzzy-matched score, got " + score);
    }

    @Test
    void shortTokensNeverFuzzyMatchToAvoidFalsePositives() {
        // "abc" (3 chars) is one edit from "abd" but short tokens stay
        // exact-only — a single-letter slip on a short word changes its
        // meaning too much to guess at.
        Set<String> tokens = KeywordMatcher.tokenize("abc");

        int score = KeywordMatcher.score("abd", tokens);

        assertEquals(0, score);
    }

    @Test
    void unrelatedGibberishNeverMatchesRegardlessOfLength() {
        Set<String> tokens = KeywordMatcher.tokenize("zzqqxx nonexistent gibberish");

        int score = KeywordMatcher.score("Irene Adler Person", tokens);

        assertEquals(0, score);
    }

    @Test
    void aCompletelyDifferentWordOfSimilarLengthDoesNotFalselyFuzzyMatch() {
        Set<String> tokens = KeywordMatcher.tokenize("Who is Moriarty?");

        int score = KeywordMatcher.score("Irene Adler", tokens);

        assertEquals(0, score);
    }
}
