package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repurposed from the deleted {@code CorpusStoreTest} (Story 12.4): the
 * in-process offline-corpus-id set is the one piece of {@link
 * Neo4jCorpusRegistry} that deliberately stays non-Neo4j (demo/offline
 * corpora never persist — see the registry's class Javadoc), so this test
 * exercises only that behavior. The registry itself still needs a real
 * {@link org.neo4j.driver.Driver} to construct (its constructor declares
 * the {@code CorpusMeta} uniqueness constraint), so it's built directly
 * against {@link SharedNeo4jTestContainer}'s shared singleton rather than a
 * fake/interface, matching this module's other plain, non-Spring test classes.
 */
class Neo4jCorpusRegistryOfflineBehaviorTest {

    private static Neo4jCorpusRegistry newRegistry() {
        return new Neo4jCorpusRegistry(SharedNeo4jTestContainer.driver());
    }

    @Test
    void aCorpusIsNotOfflineUnlessExplicitlyMarked() {
        Neo4jCorpusRegistry registry = newRegistry();

        assertFalse(registry.isOffline("some-corpus"));
    }

    @Test
    void markOfflineFlagsOnlyTheGivenCorpusId() {
        Neo4jCorpusRegistry registry = newRegistry();

        registry.markOffline("corpus-a");

        assertTrue(registry.isOffline("corpus-a"));
        assertFalse(registry.isOffline("corpus-b"));
    }

    @Test
    void markOfflineIgnoresNullOrBlankIdsWithoutThrowing() {
        Neo4jCorpusRegistry registry = newRegistry();

        registry.markOffline(null);
        registry.markOffline(" ");

        assertFalse(registry.isOffline(null));
        assertFalse(registry.isOffline(" "));
    }

    @Test
    void offlineFlagDoesNotSurviveAFreshRegistryInstanceSimulatingARestart() {
        Neo4jCorpusRegistry registry = newRegistry();
        registry.markOffline("corpus-restart");

        Neo4jCorpusRegistry restarted = newRegistry();

        assertFalse(restarted.isOffline("corpus-restart"));
    }
}
