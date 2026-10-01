package dev.rabauer.graphrag.adapter.neo4j;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.usecase.DetectCommunities;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
            new Neo4jContainer<>("neo4j:2026.08.1-community")
                    .withoutAuthentication()
                    .withEnv("NEO4J_PLUGINS", "[\"graph-data-science\"]");

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
        assertEquals(first, adapter.textUnit(corpusId, first.id()).orElseThrow());
        assertTrue(adapter.textUnit(corpusId, "missing").isEmpty());
        assertTrue(adapter.textUnit(otherCorpusId, first.id()).isEmpty());
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
    void roundTripsEntityAndRelationshipDescriptionSourceIdsWeightAndMentionLinks() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-provenance-" + System.nanoTime();
        TextUnit first = new TextUnit(corpusId + "::doc-0::tu-0", corpusId, "engine.txt", 0, "Ada wrote notes.");
        TextUnit second = new TextUnit(corpusId + "::doc-0::tu-1", corpusId, "engine.txt", 1, "Ada wrote more notes.");
        Entity entity = new Entity("Ada Lovelace", "Person", "A mathematician.", List.of(first.id(), second.id()));
        Relationship relationship = new Relationship("Ada Lovelace", "Person", "wrote_about", "Engine", "Concept",
                "Ada wrote about the Engine.", List.of(first.id(), second.id()), 2);

        adapter.persistTextUnits(corpusId, List.of(first, second));
        adapter.persistEntities(corpusId, List.of(entity));
        adapter.persistRelationships(corpusId, List.of(relationship));

        assertEquals(entity, adapter.entities(corpusId).getFirst());
        assertEquals(relationship, adapter.relationships(corpusId).getFirst());
        try (var session = driver.session()) {
            Long mentionCount = session.executeRead(tx -> tx.run(
                    "MATCH (:Entity {corpusId: $corpusId, normalizedIdentity: $identity})"
                            + "-[:MENTIONED_IN {corpusId: $corpusId}]->(:TextUnit {corpusId: $corpusId}) "
                            + "RETURN count(*) AS count",
                    Map.of("corpusId", corpusId, "identity", entity.normalizedIdentity()))
                    .single().get("count").asLong());
            assertEquals(2L, mentionCount);
        }
    }

    @Test
    void retypeEntityMovesNodeKeyAndUpdatesIncidentRelationshipEndpointTypes() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-retype-" + System.nanoTime();
        Entity oldJaguar = new Entity("Jaguar", "Animal");
        Relationship relationship = new Relationship("Jaguar", "Animal", "appears_in", "Market", "Concept");
        Relationship incoming = new Relationship("Market", "Concept", "features", "Jaguar", "Animal");
        Entity newJaguar = new Entity("Jaguar", "Organization", "A company.", List.of("u1"));

        adapter.persistEntities(corpusId, List.of(oldJaguar));
        adapter.persistRelationships(corpusId, List.of(relationship, incoming));
        adapter.retypeEntity(corpusId, oldJaguar.normalizedIdentity(), newJaguar);

        assertEquals(newJaguar, adapter.entities(corpusId).stream()
                .filter(entity -> entity.normalizedIdentity().equals(newJaguar.normalizedIdentity()))
                .findFirst().orElseThrow());
        assertEquals(2, adapter.relationships(corpusId).size());
        assertEquals("Organization", adapter.relationships(corpusId).stream()
                .filter(r -> r.type().equals("appears_in")).findFirst().orElseThrow().sourceType());
        assertEquals("Organization", adapter.relationships(corpusId).stream()
                .filter(r -> r.type().equals("features")).findFirst().orElseThrow().targetType());
        try (var session = driver.session()) {
            Long oldCount = session.executeRead(tx -> tx.run(
                    "MATCH (e:Entity {corpusId: $corpusId, normalizedIdentity: $identity}) RETURN count(e) AS count",
                    Map.of("corpusId", corpusId, "identity", oldJaguar.normalizedIdentity()))
                    .single().get("count").asLong());
            assertEquals(0L, oldCount);
        }
    }

    @Test
    void legacyEntityAndRelationshipPropertiesCoalesceOnRead() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-legacy-" + System.nanoTime();
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE (e:Entity {corpusId: $corpusId, normalizedIdentity: $identity, "
                                + "name: 'Ada Lovelace', type: 'Person'})",
                        Map.of("corpusId", corpusId, "identity", Entity.identityOf("Ada Lovelace", "Person")));
                tx.run("CREATE (s:Entity {corpusId: $corpusId, normalizedIdentity: $sId, name: 'Ada Lovelace', type: 'Person'}) "
                                + "CREATE (t:Entity {corpusId: $corpusId, normalizedIdentity: $tId, name: 'Engine', type: 'Concept'}) "
                                + "CREATE (s)-[:RELATIONSHIP {corpusId: $corpusId, source: 'Ada Lovelace', "
                                + "sourceType: 'Person', type: 'wrote_about', target: 'Engine', targetType: 'Concept'}]->(t)",
                        Map.of("corpusId", corpusId,
                                "sId", Entity.identityOf("Ada Lovelace", "Person") + "-rel",
                                "tId", Entity.identityOf("Engine", "Concept")));
                return null;
            });
        }

        Entity readEntity = adapter.entities(corpusId).stream()
                .filter(entity -> entity.normalizedIdentity().equals(Entity.identityOf("Ada Lovelace", "Person")))
                .findFirst().orElseThrow();
        Relationship readRelationship = adapter.relationships(corpusId).getFirst();

        assertEquals("", readEntity.description());
        assertEquals(List.of(), readEntity.sourceTextUnitIds());
        assertEquals("", readRelationship.description());
        assertEquals(List.of(), readRelationship.sourceTextUnitIds());
        assertEquals(1, readRelationship.weight());
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
    void communityTitleRoundTripsAndATitleLessNodeReadsAsEmpty() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-title-" + System.nanoTime();

        adapter.persistCommunities(corpusId, List.of(
                new Community("community-1", "Baker Street Detectives", "Holmes and Watson solve cases.")));
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run(
                    "CREATE (:Community {corpusId: $corpusId, id: 'community-2', summary: 'Old summary'})",
                    Map.of("corpusId", corpusId)).consume());
        }

        Map<String, Community> byId = new java.util.HashMap<>();
        adapter.communities(corpusId).forEach(community -> byId.put(community.id(), community));
        assertEquals(new Community("community-1", "Baker Street Detectives", "Holmes and Watson solve cases."),
                byId.get("community-1"));
        assertEquals(new Community("community-2", "", "Old summary"), byId.get("community-2"));
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

    // -- Community detection (GDS Leiden) ---------------------------------------

    @Test
    void leidenSplitsTwoBridgedCliquesIntoTwoCommunitiesWhereConnectedComponentsGiveOne() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-leiden-" + System.nanoTime();
        persistBridgedCliques(adapter, corpusId);
        Set<Set<String>> expected = Set.of(
                Set.of(id("A1"), id("A2"), id("A3"), id("A4")),
                Set.of(id("B1"), id("B2"), id("B3"), id("B4")));

        assertEquals(expected, asSets(adapter.detectCommunities(corpusId)));

        List<Community> communities = new DetectCommunities(adapter).detect(new Corpus(corpusId, List.of()));
        assertEquals(List.of("community-1", "community-2"), communities.stream().map(Community::id).toList());
        assertEquals(2, adapter.communities(corpusId).size());
        Map<String, Set<String>> membersByCommunity = new HashMap<>();
        for (CommunityMembership membership : adapter.communityMemberships(corpusId)) {
            membersByCommunity.computeIfAbsent(membership.communityId(), ignored -> new HashSet<>())
                    .add(membership.entityIdentity());
        }
        assertEquals(expected, Set.copyOf(membersByCommunity.values()));
        assertTrue(projectionsFor(corpusId).isEmpty());
    }

    @Test
    void leidenHonoursRelationshipWeights() {
        // Same topology in both corpora: two 4-cliques joined by the A4-B1 bridge.
        // With uniform weights Leiden returns the two cliques; with a very heavy
        // bridge, A4 and B1 must end up in the same Community.
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String uniformCorpusId = "corpus-leiden-uniform-" + System.nanoTime();
        String weightedCorpusId = "corpus-leiden-weighted-" + System.nanoTime();
        List<Relationship> cliques = new ArrayList<>();
        cliques.addAll(clique("A1", "A2", "A3", "A4"));
        cliques.addAll(clique("B1", "B2", "B3", "B4"));
        adapter.persistRelationships(uniformCorpusId, cliques);
        adapter.persistRelationships(uniformCorpusId, List.of(
                new Relationship("A4", "Node", "bridges", "B1", "Node", "", List.of(), 1)));
        adapter.persistRelationships(weightedCorpusId, cliques);
        adapter.persistRelationships(weightedCorpusId, List.of(
                new Relationship("A4", "Node", "bridges", "B1", "Node", "", List.of(), 1000)));

        Set<Set<String>> uniformGroups = asSets(adapter.detectCommunities(uniformCorpusId));
        Set<Set<String>> weightedGroups = asSets(adapter.detectCommunities(weightedCorpusId));

        assertEquals(Set.of(
                Set.of(id("A1"), id("A2"), id("A3"), id("A4")),
                Set.of(id("B1"), id("B2"), id("B3"), id("B4"))), uniformGroups);
        assertTrue(weightedGroups.stream().anyMatch(group -> group.contains(id("A4")) && group.contains(id("B1"))),
                () -> "weighted grouping: " + weightedGroups);
        assertNotEquals(uniformGroups, weightedGroups);
    }

    @Test
    void leidenIsReproducibleForTheSameGraph() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-leiden-repro-" + System.nanoTime();
        persistBridgedCliques(adapter, corpusId);

        assertEquals(asSets(adapter.detectCommunities(corpusId)), asSets(adapter.detectCommunities(corpusId)));
    }

    @Test
    void leidenKeepsAnIsolatedEntityAsItsOwnGroupButDetectCommunitiesLeavesItOut() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-leiden-isolated-" + System.nanoTime();
        persistBridgedCliques(adapter, corpusId);
        adapter.persistEntities(corpusId, List.of(new Entity("Loner", "Node")));

        List<List<String>> groups = adapter.detectCommunities(corpusId);

        assertTrue(groups.contains(List.of(id("Loner"))), () -> "groups: " + groups);
        assertEquals(3, groups.size(), () -> "groups: " + groups);
        assertEquals(9, groups.stream().mapToInt(List::size).sum());

        // The port still returns the singleton; the use case drops it (MIN_COMMUNITY_SIZE = 3).
        List<Community> communities = new DetectCommunities(adapter).detect(new Corpus(corpusId, List.of()));
        assertEquals(List.of("community-1", "community-2"), communities.stream().map(Community::id).toList());
        assertTrue(adapter.communityMemberships(corpusId).stream()
                .noneMatch(membership -> membership.entityIdentity().equals(id("Loner"))));
        assertTrue(adapter.entities(corpusId).stream()
                .anyMatch(entity -> entity.normalizedIdentity().equals(id("Loner"))), "the Loner stays an ordinary Entity");
    }

    @Test
    void edgelessCorpusYieldsOneSingletonPerEntity() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-leiden-edgeless-" + System.nanoTime();
        adapter.persistEntities(corpusId, List.of(
                new Entity("X", "Node"), new Entity("Y", "Node"), new Entity("Z", "Node")));

        assertEquals(Set.of(Set.of(id("X")), Set.of(id("Y")), Set.of(id("Z"))),
                asSets(adapter.detectCommunities(corpusId)));
        assertTrue(projectionsFor(corpusId).isEmpty());
    }

    @Test
    void emptyCorpusYieldsNoCommunities() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);

        assertTrue(adapter.detectCommunities("corpus-leiden-empty-" + System.nanoTime()).isEmpty());
    }

    @Test
    void leidenIgnoresOtherCorporaAndLeavesThemUntouched() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-leiden-own-" + System.nanoTime();
        String otherCorpusId = "corpus-leiden-other-" + System.nanoTime();
        persistBridgedCliques(adapter, corpusId);
        adapter.persistRelationships(otherCorpusId, List.of(
                new Relationship("A1", "Node", "links", "Other", "Node"),
                new Relationship("Other", "Node", "links", "B1", "Node")));

        List<List<String>> groups = adapter.detectCommunities(corpusId);

        assertFalse(groups.stream().flatMap(List::stream).anyMatch(id("Other")::equals));
        assertEquals(8, groups.stream().mapToInt(List::size).sum());
        assertEquals(3, adapter.entities(otherCorpusId).size());
        assertEquals(2, adapter.relationships(otherCorpusId).size());
        assertTrue(adapter.communities(otherCorpusId).isEmpty());
        assertTrue(projectionsFor(otherCorpusId).isEmpty());
    }

    @Test
    void leidenFailureAfterProjectionPropagatesAndLeavesNoProjectionBehind() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver) {
            @Override
            String leidenStreamCypher() {
                return "CALL gds.leiden.stream($graphName, {relationshipWeightProperty: 'doesNotExist', "
                        + "randomSeed: $randomSeed, concurrency: 1}) "
                        + "YIELD nodeId, communityId RETURN '' AS identity, communityId";
            }
        };
        String corpusId = "corpus-leiden-fail-" + System.nanoTime();
        persistBridgedCliques(adapter, corpusId);

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> adapter.detectCommunities(corpusId));

        assertTrue(failure.getMessage().startsWith("Community detection"), failure::getMessage);
        assertTrue(projectionsFor(corpusId).isEmpty());
    }

    @Test
    void projectionNameIsSanitizedAndUniquePerCall() {
        String first = Neo4jGraphStoreAdapter.projectionName("my corpus/x:1");
        String second = Neo4jGraphStoreAdapter.projectionName("my corpus/x:1");

        assertTrue(first.matches("[A-Za-z0-9_-]+"), first);
        assertTrue(first.contains("my_corpus_x_1"), first);
        assertNotEquals(first, second);
    }

    private static void persistBridgedCliques(Neo4jGraphStoreAdapter adapter, String corpusId) {
        List<Entity> entities = new ArrayList<>();
        for (String name : List.of("A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4")) {
            entities.add(new Entity(name, "Node"));
        }
        List<Relationship> relationships = new ArrayList<>();
        relationships.addAll(clique("A1", "A2", "A3", "A4"));
        relationships.addAll(clique("B1", "B2", "B3", "B4"));
        relationships.add(new Relationship("A4", "Node", "bridges", "B1", "Node"));
        adapter.persistEntities(corpusId, entities);
        adapter.persistRelationships(corpusId, relationships);
    }

    private static List<Relationship> clique(String... names) {
        List<Relationship> relationships = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            for (int j = i + 1; j < names.length; j++) {
                relationships.add(new Relationship(names[i], "Node", "links", names[j], "Node"));
            }
        }
        return relationships;
    }

    private static String id(String name) {
        return Entity.identityOf(name, "Node");
    }

    // -- Story 15.1: embeddings and vector similarity --------------------------

    private static final float[] QUERY = {1, 0, 0, 0};

    @Test
    void similarEntitiesAndCommunitiesAreEmptyForACorpusWithoutEmbeddings() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-no-embeddings-" + System.nanoTime();
        adapter.persistEntities(corpusId, List.of(new Entity("Plain", "Node")));
        adapter.persistCommunities(corpusId, List.of(new Community("community-1", "Plain community.")));

        assertTrue(adapter.similarEntities(corpusId, QUERY, 3).isEmpty());
        assertTrue(adapter.similarCommunities(corpusId, QUERY, 3).isEmpty());
    }

    @Test
    void similarEntitiesReturnsTheTopKOfTheCorpusInScoreOrderAsFullRecords() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-vec-" + System.nanoTime();
        String otherCorpusId = "corpus-vec-other-" + System.nanoTime();
        List<Entity> entities = List.of(
                new Entity("Far", "Node", "far away", List.of()),
                new Entity("Near", "Node", "very close", List.of("tu-1")),
                new Entity("Mid", "Node", "in between", List.of()),
                new Entity("Close", "Node", "close", List.of()),
                new Entity("Unembedded", "Node", "no vector", List.of()));
        adapter.persistEntities(corpusId, entities);
        adapter.persistEntities(otherCorpusId, List.of(new Entity("Near", "Node"), new Entity("Exact", "Node")));

        adapter.persistEntityEmbeddings(corpusId, Map.of(
                id("Far"), new float[] {0, 1, 0, 0},
                id("Near"), new float[] {1, 0.1f, 0, 0},
                id("Mid"), new float[] {1, 1, 0, 0},
                id("Close"), new float[] {1, 0.5f, 0, 0}));
        adapter.persistEntityEmbeddings(otherCorpusId, Map.of(
                id("Near"), new float[] {0, 0, 1, 0},
                id("Exact"), new float[] {1, 0, 0, 0}));

        List<Entity> similar = adapter.similarEntities(corpusId, QUERY, 3);

        assertEquals(List.of("Near", "Close", "Mid"), similar.stream().map(Entity::name).toList());
        assertEquals(new Entity("Near", "Node", "very close", List.of("tu-1")), similar.getFirst());
        assertEquals(List.of("Exact", "Near"),
                adapter.similarEntities(otherCorpusId, QUERY, 3).stream().map(Entity::name).toList());
        assertTrue(vectorIndexExists(Neo4jGraphStoreAdapter.ENTITY_VECTOR_INDEX, "Entity"));
    }

    @Test
    void similarCommunitiesReturnsTheTopKOfTheCorpusInScoreOrderAsFullRecords() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-cvec-" + System.nanoTime();
        String otherCorpusId = "corpus-cvec-other-" + System.nanoTime();
        adapter.persistCommunities(corpusId, List.of(
                new Community("community-1", "Far title", "Far."),
                new Community("community-2", "Near title", "Near."),
                new Community("community-3", "Mid title", "Mid.")));
        adapter.persistCommunities(otherCorpusId, List.of(new Community("community-1", "Exact.")));

        adapter.persistCommunityEmbeddings(corpusId, Map.of(
                "community-1", new float[] {0, 1, 0, 0},
                "community-2", new float[] {1, 0.1f, 0, 0},
                "community-3", new float[] {1, 1, 0, 0}));
        adapter.persistCommunityEmbeddings(otherCorpusId, Map.of("community-1", new float[] {1, 0, 0, 0}));

        List<Community> similar = adapter.similarCommunities(corpusId, QUERY, 2);

        assertEquals(List.of(new Community("community-2", "Near title", "Near."),
                new Community("community-3", "Mid title", "Mid.")), similar);
        assertEquals(List.of("community-1"),
                adapter.similarCommunities(otherCorpusId, QUERY, 3).stream().map(Community::id).toList());
        assertTrue(vectorIndexExists(Neo4jGraphStoreAdapter.COMMUNITY_VECTOR_INDEX, "Community"));
    }

    @Test
    void embeddingsSurviveALaterEntityAndCommunityRewrite() {
        Neo4jGraphStoreAdapter adapter = new Neo4jGraphStoreAdapter(driver);
        String corpusId = "corpus-vec-survive-" + System.nanoTime();
        adapter.persistEntities(corpusId, List.of(new Entity("Kept", "Node")));
        adapter.persistCommunities(corpusId, List.of(new Community("community-1", "Kept.")));
        adapter.persistEntityEmbeddings(corpusId, Map.of(id("Kept"), new float[] {1, 0, 0, 0}));
        adapter.persistCommunityEmbeddings(corpusId, Map.of("community-1", new float[] {1, 0, 0, 0}));

        adapter.persistEntities(corpusId, List.of(new Entity("Kept", "Node", "now described", List.of())));
        adapter.persistCommunities(corpusId, List.of(new Community("community-1", "Kept again.")));

        assertEquals(List.of("Kept"), adapter.similarEntities(corpusId, QUERY, 3).stream().map(Entity::name).toList());
        assertEquals(List.of("community-1"),
                adapter.similarCommunities(corpusId, QUERY, 3).stream().map(Community::id).toList());
        try (var session = driver.session()) {
            List<Object> stored = session.executeRead(tx -> tx.run(
                    "MATCH (e:Entity {corpusId: $corpusId}) RETURN e.embedding AS embedding",
                    Map.of("corpusId", corpusId)).single().get("embedding").asList());
            assertEquals(List.of(1.0, 0.0, 0.0, 0.0), stored);
        }
    }

    @Test
    void persistingEmbeddingsOfADifferentDimensionThanTheExistingIndexFailsNamingTheIndexAndBothDimensions() {
        String corpusId = "corpus-vec-dims-" + System.nanoTime();
        Neo4jGraphStoreAdapter first = new Neo4jGraphStoreAdapter(driver);
        first.persistEntities(corpusId, List.of(new Entity("Four", "Node"), new Entity("Five", "Node")));
        first.persistCommunities(corpusId, List.of(new Community("community-1", "Dims.")));
        first.persistEntityEmbeddings(corpusId, Map.of(id("Four"), new float[] {1, 0, 0, 0}));
        first.persistCommunityEmbeddings(corpusId, Map.of("community-1", new float[] {1, 0, 0, 0}));

        // A fresh adapter (e.g. after a restart with a new embedding model) has no cached dimension.
        Neo4jGraphStoreAdapter restarted = new Neo4jGraphStoreAdapter(driver);
        IllegalStateException entityFailure = assertThrows(IllegalStateException.class,
                () -> restarted.persistEntityEmbeddings(corpusId, Map.of(id("Five"), new float[] {1, 0, 0, 0, 0})));
        IllegalStateException communityFailure = assertThrows(IllegalStateException.class,
                () -> first.persistCommunityEmbeddings(corpusId, Map.of("community-1", new float[] {1, 0, 0, 0, 0})));

        assertTrue(entityFailure.getMessage().contains(Neo4jGraphStoreAdapter.ENTITY_VECTOR_INDEX));
        assertTrue(entityFailure.getMessage().contains("4 dimensions"));
        assertTrue(entityFailure.getMessage().contains("have 5"));
        assertTrue(communityFailure.getMessage().contains(Neo4jGraphStoreAdapter.COMMUNITY_VECTOR_INDEX));
        assertTrue(communityFailure.getMessage().contains("have 5"));
        assertEquals(List.of("Four"), restarted.similarEntities(corpusId, QUERY, 3).stream().map(Entity::name).toList());
    }

    private static boolean vectorIndexExists(String name, String label) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run(
                    "SHOW VECTOR INDEXES YIELD name, labelsOrTypes, properties, state "
                            + "WHERE name = $name RETURN labelsOrTypes, properties, state",
                    Map.of("name", name)).list()).stream()
                    .anyMatch(record -> record.get("labelsOrTypes").asList().contains(label)
                            && record.get("properties").asList().contains("embedding")
                            && "ONLINE".equals(record.get("state").asString()));
        }
    }

    private static Set<Set<String>> asSets(List<List<String>> groups) {
        Set<Set<String>> result = new HashSet<>();
        for (List<String> group : groups) {
            result.add(Set.copyOf(group));
        }
        return result;
    }

    private static List<String> projectionsFor(String corpusId) {
        String sanitized = corpusId.replaceAll("[^A-Za-z0-9_-]", "_");
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("CALL gds.graph.list() YIELD graphName RETURN graphName")
                    .list(record -> record.get("graphName").asString())).stream()
                    .filter(name -> name.contains(sanitized))
                    .toList();
        }
    }
}
