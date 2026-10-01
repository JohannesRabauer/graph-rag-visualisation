package com.graphraglens.adapter.neo4j;

import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.CommunityMembership;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.TextUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testcontainers-backed tests proving {@link Neo4jGraphStoreAdapter} behaves
 * correctly against a real Neo4j instance, not just that it compiles.
 */
@Testcontainers
class Neo4jGraphStoreAdapterTest {

    @Container
    private static final Neo4jContainer<?> NEO4J =
            new Neo4jContainer<>("neo4j:2026.08.1-community").withoutAuthentication();

    private static Driver driver;

    @BeforeAll
    static void startDriver() {
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.none());
    }

    @AfterAll
    static void stopDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    void persistsEntitiesAndReadsThemBackForTheirCorpusOnly() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-" + System.nanoTime();
        String otherCorpusId = "corpus-other-" + System.nanoTime();

        adapter.persistEntities(corpusId, List.of(new Entity("Apple", "Org")));

        Collection<Entity> read = adapter.entities(corpusId);
        assertEquals(1, read.size());
        assertEquals("Apple", read.iterator().next().name());
        assertTrue(adapter.entities(otherCorpusId).isEmpty());
    }

    @Test
    void persistsTextUnitsAndReadsThemBackForTheirCorpusOnlyWithoutDuplicates() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-tu-" + System.nanoTime();
        String otherCorpusId = "corpus-tu-other-" + System.nanoTime();
        TextUnit first = new TextUnit(corpusId + "::doc-0::tu-0", corpusId, "java.txt", 0, "Java began in 1991.");
        TextUnit second = new TextUnit(corpusId + "::doc-0::tu-1", corpusId, "java.txt", 1, "Java 8 added lambdas.");

        adapter.persistTextUnits(corpusId, List.of(first));
        adapter.persistTextUnits(corpusId, List.of(second));
        adapter.persistTextUnits(corpusId, List.of(first));

        assertEquals(List.of(first, second), List.copyOf(adapter.textUnits(corpusId)));
        assertTrue(adapter.textUnits(otherCorpusId).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> adapter.persistTextUnits(" ", List.of(first)));
    }

    @Test
    void reingestingTheSameEntityDoesNotDuplicateIt() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-" + System.nanoTime();

        adapter.persistEntities(corpusId, List.of(new Entity("Apple", "Org")));
        adapter.persistEntities(corpusId, List.of(new Entity("Apple", "Org")));

        assertEquals(1, adapter.entities(corpusId).size());
    }

    @Test
    void reingestingTheSameRelationshipDoesNotDuplicateIt() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-" + System.nanoTime();
        List<Relationship> relationships = List.of(new Relationship("Apple", "Org", "acquired", "Beats", "Org"));

        adapter.persistRelationships(corpusId, relationships);
        adapter.persistRelationships(corpusId, relationships);

        assertEquals(1, adapter.relationships(corpusId).size());
    }

    @Test
    void reingestingTheSameCommunityDoesNotDuplicateIt() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-" + System.nanoTime();
        List<Community> communities = List.of(new Community("community-0", "Summary"));

        adapter.persistCommunities(corpusId, communities);
        adapter.persistCommunities(corpusId, communities);

        assertEquals(1, adapter.communities(corpusId).size());
    }

    @Test
    void reingestingTheSameCommunityMembershipDoesNotDuplicateIt() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-" + System.nanoTime();
        List<CommunityMembership> memberships =
                List.of(new CommunityMembership("community-0", Entity.identityOf("Apple", "Org")));

        adapter.persistCommunityMemberships(corpusId, memberships);
        adapter.persistCommunityMemberships(corpusId, memberships);

        assertEquals(1, adapter.communityMemberships(corpusId).size());
    }

    @Test
    void twoCorporaProducingTheSameCommunityIdGetDistinctNodes() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusA = "corpus-a-" + System.nanoTime();
        String corpusB = "corpus-b-" + System.nanoTime();

        adapter.persistCommunities(corpusA, List.of(new Community("community-0", "Summary A")));
        adapter.persistCommunities(corpusB, List.of(new Community("community-0", "Summary B")));

        assertEquals(1, adapter.communities(corpusA).size());
        assertEquals(1, adapter.communities(corpusB).size());
        assertEquals("Summary A", adapter.communities(corpusA).iterator().next().summary());
        assertEquals("Summary B", adapter.communities(corpusB).iterator().next().summary());
    }

    @Test
    void unscopedLegacyCallsThrow() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);

        assertThrows(UnsupportedOperationException.class,
                () -> adapter.persistEntities(List.of(new Entity("Apple", "Org"))));
        assertThrows(UnsupportedOperationException.class,
                () -> adapter.persistRelationships(
                        List.of(new Relationship("Apple", "Org", "acquired", "Beats", "Org"))));
        assertThrows(UnsupportedOperationException.class,
                () -> adapter.persistCommunities(List.of(new Community("community-0", "Summary"))));
        assertThrows(UnsupportedOperationException.class,
                () -> adapter.persistCommunityMemberships(
                        List.of(new CommunityMembership("community-0", Entity.identityOf("Apple", "Org")))));
    }

    @Test
    void corpusScopedWritesRejectNullOrBlankCorpusId() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        List<Entity> entities = List.of(new Entity("Apple", "Org"));
        List<Relationship> relationships = List.of(new Relationship("Apple", "Org", "acquired", "Beats", "Org"));
        List<Community> communities = List.of(new Community("community-0", "Summary"));
        List<CommunityMembership> memberships =
                List.of(new CommunityMembership("community-0", Entity.identityOf("Apple", "Org")));

        assertThrows(IllegalArgumentException.class, () -> adapter.persistEntities(null, entities));
        assertThrows(IllegalArgumentException.class, () -> adapter.persistEntities(" ", entities));
        assertThrows(IllegalArgumentException.class, () -> adapter.persistRelationships(null, relationships));
        assertThrows(IllegalArgumentException.class, () -> adapter.persistRelationships(" ", relationships));
        assertThrows(IllegalArgumentException.class, () -> adapter.persistCommunities(null, communities));
        assertThrows(IllegalArgumentException.class, () -> adapter.persistCommunities(" ", communities));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.persistCommunityMemberships(null, memberships));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.persistCommunityMemberships(" ", memberships));
    }

    @Test
    void relationshipsForAnEmptyCorpusReturnEmptyCollectionWithoutException() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);

        assertTrue(adapter.relationships("nonexistent-corpus").isEmpty());
    }

    @Test
    void freshAdapterInstanceReadsBackAllFourKindsAfterSimulatedRestart() {
        String corpusId = "corpus-restart-" + System.nanoTime();
        Neo4jGraphStoreAdapter first = new Neo4jGraphStoreAdapter(driver);

        first.persistEntities(corpusId, List.of(new Entity("Apple", "Org"), new Entity("Beats", "Org")));
        first.persistRelationships(corpusId,
                List.of(new Relationship("Apple", "Org", "acquired", "Beats", "Org")));
        first.persistCommunities(corpusId, List.of(new Community("community-0", "Consumer tech")));
        first.persistCommunityMemberships(corpusId,
                List.of(new CommunityMembership("community-0", Entity.identityOf("Apple", "Org"))));

        // A fresh instance pointed at the same Neo4j simulates an app restart.
        Neo4jGraphStoreAdapter restarted = new Neo4jGraphStoreAdapter(driver);

        assertEquals(2, restarted.entities(corpusId).size());
        assertEquals(1, restarted.relationships(corpusId).size());
        assertEquals(1, restarted.communities(corpusId).size());
        assertEquals(1, restarted.communityMemberships(corpusId).size());
    }
}
