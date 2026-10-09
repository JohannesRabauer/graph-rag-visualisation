package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.Entity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scores are only comparable within one matcher; composing matchers goes by rank or by normalised scores. */
class SeedScoreContractTest {

    private static Entity entity(String name) {
        return new Entity(name, "Class");
    }

    private static SeedMatch match(String name, double score) {
        return new SeedMatch(entity(name), score, "test");
    }

    private static SeedMatcher returning(SeedMatch... matches) {
        return (question, corpusId, graph, limit) -> Stream.of(matches).limit(limit).toList();
    }

    private static List<String> names(List<SeedMatch> matches) {
        return matches.stream().map(match -> match.entity().name()).toList();
    }

    /** An identifier-like matcher (scores up to 100) and a keyword-like one (small counts), with different top picks. */
    private static SeedMatcher identifierLike() {
        return returning(match("X", 100), match("Z", 50), match("Y", 10));
    }

    private static SeedMatcher keywordLike() {
        return returning(match("Z", 3), match("W", 2), match("X", 1));
    }

    // -- Fusing by rank ---------------------------------------------------------------------

    @Test
    void fusingByRankDoesNotDependOnTheScaleOfTheMatchers() {
        SeedMatcher huge = (question, corpusId, graph, limit) -> identifierLike().match(question, corpusId, graph, limit)
                .stream().map(match -> match.withScore(match.score() * 1000)).toList();
        SeedMatcher tiny = (question, corpusId, graph, limit) -> keywordLike().match(question, corpusId, graph, limit)
                .stream().map(match -> match.withScore(match.score() / 1000)).toList();

        List<SeedMatch> plain = new HybridSeedMatcher(List.of(identifierLike(), keywordLike())).match("q", "c", null, 10);
        List<SeedMatch> rescaled = new HybridSeedMatcher(List.of(huge, tiny)).match("q", "c", null, 10);

        assertEquals(names(plain), names(rescaled));
        assertEquals(List.of("Z", "X", "W", "Y"), names(plain));
        assertEquals(1.0 / 62 + 1.0 / 61, plain.getFirst().score(), 1e-12);
    }

    @Test
    void theRankFusionHelperIsWhatTheHybridMatcherUses() {
        List<SeedMatch> byHelper = SeedMatchers.fuseByRank(List.of(
                List.of(match("X", 100), match("Z", 50)), List.of(match("Z", 3), match("W", 2))),
                HybridSeedMatcher.DEFAULT_K, 10);

        assertEquals(List.of("Z", "X", "W"), names(byHelper));
        assertEquals("hybrid", byHelper.getFirst().matchedBy());
        assertEquals(1.0 / 62 + 1.0 / 61, byHelper.getFirst().score(), 1e-12);
        assertEquals(2, SeedMatchers.fuseByRank(List.of(List.of(match("X", 1), match("Y", 1))), 60, 2).size());
        assertEquals(List.of(), SeedMatchers.fuseByRank(List.of(), 60, 5));
        assertThrows(IllegalArgumentException.class, () -> SeedMatchers.fuseByRank(List.of(), 0, 5));
    }

    @Test
    void aMatcherThatReturnsItsMatchesOutOfOrderIsRankedByScoreWhenFused() {
        SeedMatcher unordered = returning(match("low", 1), match("high", 9));

        List<SeedMatch> fused = new HybridSeedMatcher(List.of(unordered)).match("q", "c", null, 5);

        assertEquals(List.of("high", "low"), names(fused));
    }

    // -- Normalising ------------------------------------------------------------------------

    @Test
    void normalisingScalesTheBestToOneAndKeepsTheRatios() {
        List<SeedMatch> normalized = SeedMatchers.normalized(identifierLike()).match("q", "c", null, 10);

        assertEquals(List.of("X", "Z", "Y"), names(normalized));
        assertEquals(List.of(1.0, 0.5, 0.1), normalized.stream().map(SeedMatch::score).toList());
        assertEquals("test", normalized.getFirst().matchedBy());
    }

    @Test
    void normalisingScoresThatCanBeNegativeMapsThemOntoZeroToOne() {
        SeedMatcher cosine = returning(match("a", 0.9), match("b", 0.1), match("c", -0.3));

        List<Double> scores = SeedMatchers.normalized(cosine).match("q", "c", null, 10).stream()
                .map(SeedMatch::score).toList();

        assertEquals(1.0, scores.get(0), 1e-12);
        assertEquals(0.4 / 1.2, scores.get(1), 1e-12);
        assertEquals(0.0, scores.get(2), 1e-12);
    }

    @Test
    void normalisingEqualOrEmptyResultsIsSafe() {
        assertEquals(List.of(1.0, 1.0), SeedMatchers.normalized(returning(match("a", 0), match("b", 0)))
                .match("q", "c", null, 5).stream().map(SeedMatch::score).toList());
        assertEquals(List.of(), SeedMatchers.normalized(returning()).match("q", "c", null, 5));
    }

    @Test
    void aWeightedSumOfNormalisedScoresIsNotDominatedByTheLargerScale() {
        // Raw sum: X = 100 + 1 beats Z = 50 + 3. Normalised: X = 1 + 1/3, Z = 1/2 + 1.
        List<SeedMatch> summed = SeedMatchers.weightedSum(List.of(identifierLike(), keywordLike()), List.of(1.0, 1.0))
                .match("q", "c", null, 10);

        assertEquals("Z", summed.getFirst().entity().name());
        assertEquals(1.5, summed.getFirst().score(), 1e-12);
        assertEquals(List.of("Z", "X", "W", "Y"), names(summed));
        assertEquals("weighted", summed.getFirst().matchedBy());
    }

    @Test
    void theWeightsDecideBetweenTheMatchers() {
        List<SeedMatch> byIdentifier = SeedMatchers.weightedSum(List.of(identifierLike(), keywordLike()),
                List.of(3.0, 1.0)).match("q", "c", null, 10);

        assertEquals("X", byIdentifier.getFirst().entity().name());
        assertThrows(IllegalArgumentException.class,
                () -> SeedMatchers.weightedSum(List.of(identifierLike()), List.of(1.0, 2.0)));
        assertThrows(IllegalArgumentException.class, () -> SeedMatchers.weightedSum(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SeedMatchers.weightedSum(List.of(identifierLike()), List.of(-1.0)));
    }

    // -- Enforcing the contract -------------------------------------------------------------

    @Test
    void aMatcherThatKeepsTheContractPassesThroughValidation() {
        SeedMatcher matcher = identifierLike();

        assertEquals(names(matcher.match("q", "c", null, 2)),
                names(SeedMatchers.validated(matcher).match("q", "c", null, 2)));
    }

    @Test
    void validationRejectsUnsortedNonFiniteAndTooManyMatches() {
        SeedMatcher unsorted = returning(match("a", 1), match("b", 2));
        SeedMatcher notANumber = returning(match("a", Double.NaN));
        SeedMatcher infinite = returning(match("a", Double.POSITIVE_INFINITY));
        SeedMatcher tooMany = (question, corpusId, graph, limit) -> List.of(match("a", 3), match("b", 2), match("c", 1));
        SeedMatcher nothing = (question, corpusId, graph, limit) -> null;

        for (SeedMatcher broken : List.of(unsorted, notANumber, infinite, nothing)) {
            assertThrows(IllegalStateException.class, () -> SeedMatchers.validated(broken).match("q", "c", null, 5));
        }
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> SeedMatchers.validated(tooMany).match("q", "c", null, 2));
        assertTrue(thrown.getMessage().contains("3") && thrown.getMessage().contains("2"), thrown.getMessage());
    }

    @Test
    void helpersKeepTheMatchersTheyWrapUsable() {
        SeedMatcher inner = identifierLike();

        assertEquals(3, SeedMatchers.validated(SeedMatchers.normalized(inner)).match("q", "c", null, 10).size());
    }
}
