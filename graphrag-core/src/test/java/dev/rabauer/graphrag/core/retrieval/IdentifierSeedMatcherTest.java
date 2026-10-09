package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.port.GraphReadPort;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentifierSeedMatcherTest {

    private static final String SERVICE = "org.pulsar.broker.service.BrokerService";
    private static final String ADMIN = "org.pulsar.broker.admin.AdminResource";
    private static final String CACHE = "org.pulsar.broker.Cache";
    private static final String CLIENT = "org.pulsar.client.PulsarClient";

    private final IdentifierSeedMatcher matcher = new IdentifierSeedMatcher();

    private static GraphReadPort graphOf(String... names) {
        List<Entity> entities = java.util.Arrays.stream(names).map(name -> new Entity(name, "Class")).toList();
        return new GraphReadPort() {
            @Override
            public Collection<Entity> entities(String corpusId) {
                return entities;
            }
        };
    }

    private List<String> names(String question, GraphReadPort graph) {
        return matcher.match(question, "c", graph, 10).stream().map(match -> match.entity().name()).toList();
    }

    @Test
    void aPackageSegmentEqualToPartOfTheQuestionedClassDoesNotMatchItsSiblings() {
        // "BrokerService" has the words broker + service; the classes of package "broker" only share "broker".
        assertEquals(List.of(SERVICE), names("How does BrokerService start?", graphOf(SERVICE, ADMIN, CACHE, CLIENT)));
    }

    @Test
    void packageSegmentsScoreFarBelowTheSimpleName() {
        double simple = matcher.score(new Entity(SERVICE, "Class"), "BrokerService");
        double siblingInPackage = matcher.score(new Entity(ADMIN, "Class"), "BrokerService");

        assertTrue(simple >= IdentifierSeedMatcher.SUFFIX, "simple name: " + simple);
        assertTrue(siblingInPackage < IdentifierSeedMatcher.MIN_SCORE, "package sibling: " + siblingInPackage);
    }

    @Test
    void plainQuestionWordsDoNotMatchPackageSegments() {
        List<String> matched = names("where is the broker started", graphOf(SERVICE, ADMIN, CACHE, CLIENT));

        assertEquals(List.of(SERVICE), matched);
    }

    @Test
    void aTokenEqualToAPackageSegmentDoesNotMatchTheClassesInIt() {
        assertFalse(names("what is in broker_state", graphOf("org.pulsar.broker_state.Holder")).contains(
                "org.pulsar.broker_state.Holder"));
    }

    @Test
    void theOwnerClassOfAMethodStillMatchesAsASegment() {
        assertTrue(matcher.score(new Entity("org.pulsar.broker.CacheManager#evict()", "Method"), "CacheManager")
                >= IdentifierSeedMatcher.SEGMENT);
    }

    @Test
    void lowercaseNamesWithoutAnyTypeSegmentKeepMatchingBySegment() {
        // e.g. Python or Go modules: nothing is recognisably a package, so nothing is demoted.
        assertTrue(matcher.score(new Entity("billing.order_service.place_order", "Function"), "order_service")
                >= IdentifierSeedMatcher.SEGMENT);
    }

    @Test
    void theNormalisedNamesAreComputedOncePerCorpus() {
        GraphReadPort graph = graphOf(SERVICE, ADMIN, CACHE, CLIENT);

        matcher.match("BrokerService", "c", graph, 5);
        matcher.match("PulsarClient", "c", graph, 5);
        matcher.match("AdminResource", "c", graph, 5);

        assertEquals(1, matcher.indexBuilds());
    }

    @Test
    void theIndexIsRebuiltWhenTheCorpusChanges() {
        matcher.match("BrokerService", "c", graphOf(SERVICE, ADMIN), 5);
        matcher.match("BrokerService", "c", graphOf(SERVICE, ADMIN), 5);
        assertEquals(1, matcher.indexBuilds(), "equal entities reuse the index");

        List<String> after = names("PulsarClient", graphOf(SERVICE, ADMIN, CLIENT));

        assertEquals(2, matcher.indexBuilds());
        assertEquals(List.of(CLIENT), after);
    }

    @Test
    void separateCorporaHaveSeparateIndexes() {
        matcher.match("BrokerService", "one", graphOf(SERVICE), 5);
        matcher.match("PulsarClient", "two", graphOf(CLIENT), 5);
        matcher.match("BrokerService", "one", graphOf(SERVICE), 5);

        assertEquals(2, matcher.indexBuilds());
    }
}
