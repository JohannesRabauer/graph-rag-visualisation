package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.retrieval.HybridSeedMatcher;
import dev.rabauer.graphrag.core.retrieval.IdentifierSeedMatcher;
import dev.rabauer.graphrag.core.retrieval.KeywordSeedMatcher;
import dev.rabauer.graphrag.core.retrieval.SeedMatch;
import dev.rabauer.graphrag.core.retrieval.SeedMatcher;
import dev.rabauer.graphrag.core.retrieval.SeedMatchers;
import dev.rabauer.graphrag.core.retrieval.SemanticSeedMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CHARGE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.CORPUS;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.PLACE_ORDER;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.REPOSITORY;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.SAVE;
import static dev.rabauer.graphrag.core.usecase.SmallCodeGraph.SERVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedMatchersTest {

    private final TestGraphStore store = SmallCodeGraph.store();
    private final IdentifierSeedMatcher identifiers = new IdentifierSeedMatcher();

    private List<String> names(SeedMatcher matcher, String question, int limit) {
        return matcher.match(question, CORPUS, store, limit).stream().map(match -> match.entity().name()).toList();
    }

    @Test
    void matchesAClassBySimpleNameAboveItsMembers() {
        assertEquals(SERVICE, names(identifiers, "What does OrderService do?", 3).getFirst());
    }

    @Test
    void matchesAMethodByItsCamelCaseName() {
        assertEquals(List.of(PLACE_ORDER), names(identifiers, "Who calls placeOrder?", 1));
    }

    @Test
    void prefersTheQualifiedMethodForAQualifiedToken() {
        for (String question : List.of("OrderService.placeOrder", "OrderService::placeOrder",
                "OrderService#placeOrder(Order)", "com.shop.order.OrderService#placeOrder")) {
            assertEquals(PLACE_ORDER, names(identifiers, question, 1).getFirst(), question);
        }
    }

    @Test
    void matchesSnakeCaseAgainstCamelCaseByWords() {
        assertEquals(PLACE_ORDER, names(identifiers, "where is place_order implemented", 1).getFirst());
    }

    @Test
    void toleratesATypoInALongIdentifier() {
        assertEquals(PLACE_ORDER, names(identifiers, "what does placeOrdr return", 1).getFirst());
    }

    @Test
    void plainWordsMatchNameWordsIgnoringPluralsAndStopWords() {
        List<String> matched = names(identifiers, "where are payments charged", 5);
        assertTrue(matched.contains(CHARGE) || matched.contains(SmallCodeGraph.PAYMENT), matched::toString);
        assertEquals(List.of(), names(identifiers, "what does it do", 5));
    }

    @Test
    void matchesNameAttributesToo() {
        TestGraphStore withAlias = new TestGraphStore();
        withAlias.persistEntities(CORPUS, List.of(new Entity("type-17", "Class", "", List.of(),
                Map.of("simpleName", "InvoiceMailer"), null)));

        assertEquals(List.of("type-17"), new IdentifierSeedMatcher()
                .match("who uses InvoiceMailer", CORPUS, withAlias, 3).stream().map(m -> m.entity().name()).toList());
    }

    @Test
    void ranksBestFirstAndHonoursTheLimit() {
        List<SeedMatch> matches = identifiers.match("OrderRepository save", CORPUS, store, 2);
        assertEquals(2, matches.size());
        assertEquals(REPOSITORY, matches.getFirst().entity().name());
        assertTrue(matches.get(0).score() >= matches.get(1).score());
        assertEquals("identifier", matches.getFirst().matchedBy());
    }

    @Test
    void theKeywordMatcherReturnsTheClassicBestSeed() {
        TestGraphStore text = new TestGraphStore();
        text.persistEntities("c", List.of(new Entity("Sherlock Holmes", "Person"), new Entity("Baker Street", "Location")));

        assertEquals(List.of("Sherlock Holmes"), new KeywordSeedMatcher().match("Where does Sherlock live?", "c", text, 1)
                .stream().map(match -> match.entity().name()).toList());
        assertEquals(List.of(), new KeywordSeedMatcher().match("Nothing relevant", "c", text, 3));
    }

    @Test
    void hybridFusesRanksReciprocally() {
        SeedMatcher first = (question, corpusId, graph, limit) -> List.of(
                new SeedMatch(new Entity("A", "Class"), 9, "x"), new SeedMatch(new Entity("B", "Class"), 8, "x"));
        SeedMatcher second = (question, corpusId, graph, limit) -> List.of(
                new SeedMatch(new Entity("B", "Class"), 1, "y"), new SeedMatch(new Entity("C", "Class"), 1, "y"));

        List<SeedMatch> fused = new HybridSeedMatcher(List.of(first, second)).match("q", CORPUS, store, 3);

        assertEquals(List.of("B", "A", "C"), fused.stream().map(match -> match.entity().name()).toList());
        assertEquals(1.0 / 62 + 1.0 / 61, fused.getFirst().score(), 1e-12);
        assertEquals("hybrid", fused.getFirst().matchedBy());
        assertThrows(IllegalArgumentException.class, () -> new HybridSeedMatcher(List.of()));
    }

    @Test
    void theDefaultIsKeywordsWithoutASemanticModel() {
        assertInstanceOf(KeywordSeedMatcher.class, SeedMatchers.defaultFor(null));
        assertInstanceOf(KeywordSeedMatcher.class,
                SeedMatchers.defaultFor(new SemanticTestFixtures.FakeEmbeddingPort(false)));
        assertInstanceOf(IdentifierSeedMatcher.class, SeedMatchers.forCode(null));
        assertInstanceOf(HybridSeedMatcher.class, SeedMatchers.forCode(new SemanticTestFixtures.FakeEmbeddingPort()));
    }

    @Test
    void theSemanticMatcherFallsBackToKeywordsWhenTheCorpusHasNoEmbeddings() {
        SeedMatcher defaults = SeedMatchers.defaultFor(new SemanticTestFixtures.FakeEmbeddingPort());

        assertEquals(List.of(SAVE), names(defaults, "save", 1));
        assertEquals(List.of(), new SemanticSeedMatcher(null).match("save", CORPUS, store, 3));
    }
}
