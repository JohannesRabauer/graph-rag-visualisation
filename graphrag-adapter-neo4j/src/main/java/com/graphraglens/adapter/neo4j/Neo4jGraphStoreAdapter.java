package com.graphraglens.adapter.neo4j;

import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.CommunityMembership;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.port.GraphStorePort;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.Neo4jException;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Real Neo4j-backed implementation of {@link GraphStorePort}, using the
 * plain {@code org.neo4j.driver} (never Spring Data Neo4j). Every write is a
 * Cypher {@code MERGE} keyed by a composite key that always includes
 * {@code corpusId} (AD-20), so re-ingestion never duplicates nodes and two
 * corpora never collide on a per-run identifier such as a Community's
 * generated {@code id}.
 *
 * <p>The two unscoped, single-argument {@link GraphStorePort} methods
 * ({@link #persistEntities(Collection)}, {@link #persistRelationships(Collection)})
 * have no {@code corpusId} to key on and would otherwise silently fall back
 * to the interface's unscoped {@code default} behavior; this adapter instead
 * makes them fail loud.</p>
 *
 * <p>Entities all share one generic {@code :Entity} label and relationships
 * one generic {@code :RELATIONSHIP} type, with the real semantic type stored
 * as a property, rather than mapping each domain type to its own dynamic
 * native Neo4j label/relationship-type; this is a deliberate tradeoff that
 * keeps the uniqueness constraints in {@link #ensureConstraints()} fixed and
 * declarable up front instead of created dynamically per observed type.</p>
 */
public class Neo4jGraphStoreAdapter implements GraphStorePort {

    private static final Logger LOG = System.getLogger(Neo4jGraphStoreAdapter.class.getName());

    private static final String RELATIONSHIP_TYPE = "RELATIONSHIP";

    /** Fixed Leiden seed so repeated runs over the same graph group identically. */
    static final long LEIDEN_RANDOM_SEED = 42L;

    /** Prefix of every in-memory GDS projection this adapter creates. */
    static final String PROJECTION_PREFIX = "graphrag-communities-";

    private final Driver driver;

    public Neo4jGraphStoreAdapter(Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        ensureConstraints();
    }

    private void ensureConstraints() {
        ensureConstraint(
                "CREATE CONSTRAINT entity_corpus_identity IF NOT EXISTS "
                        + "FOR (e:Entity) REQUIRE (e.corpusId, e.normalizedIdentity) IS UNIQUE");
        ensureConstraint(
                "CREATE CONSTRAINT community_corpus_id IF NOT EXISTS "
                        + "FOR (c:Community) REQUIRE (c.corpusId, c.id) IS UNIQUE");
        ensureConstraint(
                "CREATE CONSTRAINT relationship_corpus_key IF NOT EXISTS "
                        + "FOR ()-[r:" + RELATIONSHIP_TYPE + "]-() "
                        + "REQUIRE (r.corpusId, r.source, r.type, r.target) IS UNIQUE");
        ensureConstraint(
                "CREATE CONSTRAINT community_membership_corpus_key IF NOT EXISTS "
                        + "FOR ()-[m:BELONGS_TO]-() "
                        + "REQUIRE (m.corpusId, m.communityId, m.entityIdentity) IS UNIQUE");
        ensureConstraint(
                "CREATE CONSTRAINT text_unit_corpus_id IF NOT EXISTS "
                        + "FOR (t:TextUnit) REQUIRE (t.corpusId, t.id) IS UNIQUE");
    }

    /**
     * Declares a single uniqueness constraint idempotently. Composite
     * relationship-property uniqueness constraints are not supported on
     * every Neo4j edition/version; when that is the case the corresponding
     * {@code MERGE} on all key properties is still correct, just
     * unconstrained, so failures here are logged rather than fatal.
     */
    private void ensureConstraint(String cypher) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher).consume());
        } catch (Neo4jException e) {
            LOG.log(Level.WARNING, () -> "Could not create constraint (continuing unconstrained): " + cypher
                    + " -- " + e.getMessage());
        }
    }

    // -- Unscoped legacy methods: fail loud (AD-20) --------------------------

    @Override
    public void persistEntities(Collection<Entity> entities) {
        throw new UnsupportedOperationException(
                "Neo4jGraphStoreAdapter requires a corpusId; use persistEntities(String, Collection) instead.");
    }

    @Override
    public void persistRelationships(Collection<Relationship> relationships) {
        throw new UnsupportedOperationException(
                "Neo4jGraphStoreAdapter requires a corpusId; use persistRelationships(String, Collection) instead.");
    }

    @Override
    public void persistCommunities(Collection<Community> communities) {
        throw new UnsupportedOperationException(
                "Neo4jGraphStoreAdapter requires a corpusId; use persistCommunities(String, Collection) instead.");
    }

    @Override
    public void persistCommunityMemberships(Collection<CommunityMembership> memberships) {
        throw new UnsupportedOperationException(
                "Neo4jGraphStoreAdapter requires a corpusId; "
                        + "use persistCommunityMemberships(String, Collection) instead.");
    }

    // -- Corpus-scoped writes -------------------------------------------------

    private static void requireCorpusId(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            throw new IllegalArgumentException("corpusId must not be null or blank");
        }
    }

    @Override
    public void persistEntities(String corpusId, Collection<Entity> entities) {
        requireCorpusId(corpusId);
        if (entities == null || entities.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = entities.stream()
                .filter(Objects::nonNull)
                .map(entity -> Map.<String, Object>of(
                        "normalizedIdentity", entity.normalizedIdentity(),
                        "name", entity.name(),
                        "type", entity.type(),
                        "description", entity.description(),
                        "sourceTextUnitIds", entity.sourceTextUnitIds()))
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(
                    "UNWIND $rows AS row "
                            + "MERGE (e:Entity {corpusId: $corpusId, normalizedIdentity: row.normalizedIdentity}) "
                            + "SET e.name = row.name, e.type = row.type, "
                            + "e.description = row.description, e.sourceTextUnitIds = row.sourceTextUnitIds "
                            + "WITH e, row "
                            + "UNWIND row.sourceTextUnitIds AS sourceTextUnitId "
                            + "MATCH (t:TextUnit {corpusId: $corpusId, id: sourceTextUnitId}) "
                            + "MERGE (e)-[:MENTIONED_IN {corpusId: $corpusId}]->(t)",
                    Map.of("corpusId", corpusId, "rows", rows)).consume());
        }
    }

    @Override
    public void persistRelationships(String corpusId, Collection<Relationship> relationships) {
        requireCorpusId(corpusId);
        if (relationships == null || relationships.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = relationships.stream()
                .filter(Objects::nonNull)
                .map(relationship -> Map.<String, Object>of(
                        "sourceIdentity", Entity.identityOf(relationship.source(), relationship.sourceType()),
                        "targetIdentity", Entity.identityOf(relationship.target(), relationship.targetType()),
                        "source", relationship.source(),
                        "sourceType", relationship.sourceType(),
                        "type", relationship.type(),
                        "target", relationship.target(),
                        "targetType", relationship.targetType(),
                        "description", relationship.description(),
                        "sourceTextUnitIds", relationship.sourceTextUnitIds(),
                        "weight", relationship.weight()))
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(
                    "UNWIND $rows AS row "
                            + "MERGE (s:Entity {corpusId: $corpusId, normalizedIdentity: row.sourceIdentity}) "
                            + "ON CREATE SET s.name = row.source, s.type = row.sourceType "
                            + "MERGE (t:Entity {corpusId: $corpusId, normalizedIdentity: row.targetIdentity}) "
                            + "ON CREATE SET t.name = row.target, t.type = row.targetType "
                            + "MERGE (s)-[r:" + RELATIONSHIP_TYPE + " {corpusId: $corpusId, "
                            + "source: row.source, type: row.type, target: row.target}]->(t) "
                            + "SET r.sourceType = row.sourceType, r.targetType = row.targetType, "
                            + "r.description = row.description, r.sourceTextUnitIds = row.sourceTextUnitIds, "
                            + "r.weight = row.weight",
                    Map.of("corpusId", corpusId, "rows", rows)).consume());
        }
    }

    @Override
    public void retypeEntity(String corpusId, String previousIdentity, Entity resolved) {
        requireCorpusId(corpusId);
        if (previousIdentity == null || previousIdentity.isBlank() || resolved == null) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(
                    "MATCH (e:Entity {corpusId: $corpusId, normalizedIdentity: $previousIdentity}) "
                            + "SET e.normalizedIdentity = $normalizedIdentity, e.name = $name, e.type = $type, "
                            + "e.description = $description, e.sourceTextUnitIds = $sourceTextUnitIds "
                            + "WITH e "
                            + "OPTIONAL MATCH (e)-[out:" + RELATIONSHIP_TYPE + " {corpusId: $corpusId}]->() "
                            + "SET out.source = $name, out.sourceType = $type "
                            + "WITH e "
                            + "OPTIONAL MATCH ()-[in:" + RELATIONSHIP_TYPE + " {corpusId: $corpusId}]->(e) "
                            + "SET in.target = $name, in.targetType = $type",
                    Map.of(
                            "corpusId", corpusId,
                            "previousIdentity", previousIdentity,
                            "normalizedIdentity", resolved.normalizedIdentity(),
                            "name", resolved.name(),
                            "type", resolved.type(),
                            "description", resolved.description(),
                            "sourceTextUnitIds", resolved.sourceTextUnitIds())).consume());
        }
    }

    @Override
    public void persistTextUnits(String corpusId, Collection<TextUnit> textUnits) {
        requireCorpusId(corpusId);
        if (textUnits == null || textUnits.isEmpty()) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                for (TextUnit textUnit : textUnits) {
                    if (textUnit == null) {
                        continue;
                    }
                    tx.run("MERGE (t:TextUnit {corpusId: $corpusId, id: $id}) "
                                    + "SET t.documentName = $documentName, t.ordinal = $ordinal, t.text = $text",
                            Map.of(
                                    "corpusId", corpusId,
                                    "id", textUnit.id(),
                                    "documentName", textUnit.documentName() == null ? "" : textUnit.documentName(),
                                    "ordinal", textUnit.ordinal(),
                                    "text", textUnit.text() == null ? "" : textUnit.text()));
                }
                return null;
            });
        }
    }

    @Override
    public void persistCommunities(String corpusId, Collection<Community> communities) {
        requireCorpusId(corpusId);
        if (communities == null || communities.isEmpty()) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                for (Community community : communities) {
                    if (community == null) {
                        continue;
                    }
                    tx.run("MERGE (c:Community {corpusId: $corpusId, id: $id}) SET c.title = $title, c.summary = $summary",
                            Map.of(
                                    "corpusId", corpusId,
                                    "id", community.id(),
                                    "title", community.title(),
                                    "summary", community.summary()));
                }
                return null;
            });
        }
    }

    @Override
    public void persistCommunityMemberships(String corpusId, Collection<CommunityMembership> memberships) {
        requireCorpusId(corpusId);
        if (memberships == null || memberships.isEmpty()) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                for (CommunityMembership membership : memberships) {
                    if (membership == null) {
                        continue;
                    }
                    tx.run("MERGE (c:Community {corpusId: $corpusId, id: $communityId}) "
                                    + "ON CREATE SET c.summary = 'Community cluster' "
                                    + "MERGE (e:Entity {corpusId: $corpusId, normalizedIdentity: $entityIdentity}) "
                                    + "ON CREATE SET e.name = '', e.type = 'Unknown' "
                                    + "MERGE (e)-[m:BELONGS_TO {corpusId: $corpusId, communityId: $communityId, "
                                    + "entityIdentity: $entityIdentity}]->(c)",
                            Map.of(
                                    "corpusId", corpusId,
                                    "communityId", membership.communityId(),
                                    "entityIdentity", membership.entityIdentity()));
                }
                return null;
            });
        }
    }

    // -- Corpus-scoped reads ---------------------------------------------------

    @Override
    public List<Entity> entities(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<Entity> result = new ArrayList<>();
                for (Record record : tx.run("MATCH (e:Entity {corpusId: $corpusId}) RETURN e.name AS name, "
                        + "e.type AS type, coalesce(e.description, '') AS description, "
                        + "coalesce(e.sourceTextUnitIds, []) AS sourceTextUnitIds", Map.of("corpusId", corpusId)).list()) {
                    result.add(new Entity(record.get("name").asString(), record.get("type").asString(),
                            record.get("description").asString(),
                            record.get("sourceTextUnitIds").asList(value -> value.asString())));
                }
                return result;
            });
        }
    }

    @Override
    public List<Relationship> relationships(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<Relationship> result = new ArrayList<>();
                for (Record record : tx.run(
                        "MATCH ()-[r:" + RELATIONSHIP_TYPE + " {corpusId: $corpusId}]->() "
                                + "RETURN r.source AS source, r.sourceType AS sourceType, r.type AS type, "
                                + "r.target AS target, r.targetType AS targetType, "
                                + "coalesce(r.description, '') AS description, "
                                + "coalesce(r.sourceTextUnitIds, []) AS sourceTextUnitIds, "
                                + "coalesce(r.weight, 1) AS weight",
                        Map.of("corpusId", corpusId)).list()) {
                    result.add(new Relationship(
                            record.get("source").asString(),
                            record.get("sourceType").asString(),
                            record.get("type").asString(),
                            record.get("target").asString(),
                            record.get("targetType").asString(),
                            record.get("description").asString(),
                            record.get("sourceTextUnitIds").asList(value -> value.asString()),
                            record.get("weight").asInt()));
                }
                return result;
            });
        }
    }

    @Override
    public List<TextUnit> textUnits(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<TextUnit> result = new ArrayList<>();
                for (Record record : tx.run("MATCH (t:TextUnit {corpusId: $corpusId}) "
                                + "RETURN t.id AS id, t.documentName AS documentName, t.ordinal AS ordinal, "
                                + "t.text AS text ORDER BY t.documentName, t.ordinal, t.id",
                        Map.of("corpusId", corpusId)).list()) {
                    result.add(new TextUnit(
                            record.get("id").asString(),
                            corpusId,
                            record.get("documentName").asString(),
                            record.get("ordinal").asInt(),
                            record.get("text").asString()));
                }
                return result;
            });
        }
    }

    @Override
    public Optional<TextUnit> textUnit(String corpusId, String textUnitId) {
        if (corpusId == null || corpusId.isBlank() || textUnitId == null || textUnitId.isBlank()) {
            return Optional.empty();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("MATCH (t:TextUnit {corpusId: $corpusId, id: $id}) "
                            + "RETURN t.id AS id, t.documentName AS documentName, t.ordinal AS ordinal, t.text AS text",
                    Map.of("corpusId", corpusId, "id", textUnitId)).list().stream()
                    .findFirst()
                    .map(record -> new TextUnit(
                            record.get("id").asString(),
                            corpusId,
                            record.get("documentName").asString(),
                            record.get("ordinal").asInt(),
                            record.get("text").asString())));
        }
    }

    @Override
    public List<Community> communities(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<Community> result = new ArrayList<>();
                for (Record record : tx.run("MATCH (c:Community {corpusId: $corpusId}) RETURN c.id AS id, "
                        + "coalesce(c.title, '') AS title, c.summary AS summary", Map.of("corpusId", corpusId)).list()) {
                    result.add(new Community(record.get("id").asString(), record.get("title").asString(),
                            record.get("summary").asString()));
                }
                return result;
            });
        }
    }

    @Override
    public List<CommunityMembership> communityMemberships(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<CommunityMembership> result = new ArrayList<>();
                for (Record record : tx.run(
                        "MATCH ()-[m:BELONGS_TO {corpusId: $corpusId}]->() "
                                + "RETURN m.communityId AS communityId, m.entityIdentity AS entityIdentity",
                        Map.of("corpusId", corpusId)).list()) {
                    result.add(new CommunityMembership(
                            record.get("communityId").asString(), record.get("entityIdentity").asString()));
                }
                return result;
            });
        }
    }

    // -- Community detection (GDS Leiden, AD-4) --------------------------------

    /**
     * Groups the corpus's Entities with GDS Leiden over a corpus-scoped,
     * undirected projection weighted by {@code r.weight} (missing weights
     * count as 1). The projection is uniquely named per call and always
     * dropped afterwards, even when Leiden fails. There is deliberately no
     * fallback to connected components: when GDS is unavailable or Leiden
     * throws, the failure propagates as an {@link IllegalStateException}
     * naming community detection.
     *
     * <p>Entities without relationships, and any Entity the Leiden stream
     * omits, become single-member groups. A corpus without relationships is
     * answered with singletons without calling GDS at all.</p>
     */
    @Override
    public List<List<String>> detectCommunities(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            List<String> identities = session.executeRead(tx -> tx.run(
                    "MATCH (e:Entity {corpusId: $corpusId}) RETURN e.normalizedIdentity AS identity",
                    Map.of("corpusId", corpusId)).list(record -> record.get("identity").asString()));
            if (identities.isEmpty()) {
                return List.of();
            }
            long relationshipCount = session.executeRead(tx -> tx.run(
                    "MATCH (:Entity {corpusId: $corpusId})-[r:" + RELATIONSHIP_TYPE + " {corpusId: $corpusId}]->"
                            + "(:Entity {corpusId: $corpusId}) RETURN count(r) AS count",
                    Map.of("corpusId", corpusId)).single().get("count").asLong());
            if (relationshipCount == 0) {
                return identities.stream().map(List::of).toList();
            }

            String graphName = projectionName(corpusId);
            Map<Long, List<String>> groupsByCommunityId;
            IllegalStateException failure = null;
            try {
                session.executeWrite(tx -> tx.run(
                        "MATCH (s:Entity {corpusId: $corpusId}) "
                                + "OPTIONAL MATCH (s)-[r:" + RELATIONSHIP_TYPE + " {corpusId: $corpusId}]->"
                                + "(t:Entity {corpusId: $corpusId}) "
                                + "WITH gds.graph.project($graphName, s, t, "
                                + "{relationshipProperties: {weight: toFloat(coalesce(r.weight, 1))}}, "
                                + "{undirectedRelationshipTypes: ['*']}) AS g "
                                + "RETURN g.nodeCount AS nodeCount",
                        Map.of("corpusId", corpusId, "graphName", graphName)).consume());
                // executeWrite: routed to the same (leader) member that holds the projection.
                // The map is built inside the transaction function so a driver retry starts fresh.
                groupsByCommunityId = session.executeWrite(tx -> {
                    Map<Long, List<String>> byCommunityId = new LinkedHashMap<>();
                    for (Record record : tx.run(leidenStreamCypher(),
                            Map.of("graphName", graphName, "randomSeed", LEIDEN_RANDOM_SEED)).list()) {
                        byCommunityId
                                .computeIfAbsent(record.get("communityId").asLong(), ignored -> new ArrayList<>())
                                .add(record.get("identity").asString());
                    }
                    return byCommunityId;
                });
            } catch (RuntimeException e) {
                failure = new IllegalStateException(
                        "Community detection (GDS Leiden) failed for corpus " + corpusId + ": " + e.getMessage(), e);
                throw failure;
            } finally {
                dropProjection(session, graphName, failure);
            }

            List<List<String>> groups = new ArrayList<>();
            Set<String> grouped = new LinkedHashSet<>();
            for (List<String> group : groupsByCommunityId.values()) {
                groups.add(List.copyOf(group));
                grouped.addAll(group);
            }
            for (String identity : identities) {
                if (!grouped.contains(identity)) {
                    groups.add(List.of(identity));
                }
            }
            return List.copyOf(groups);
        }
    }

    /**
     * Drops the projection, tolerating a missing one. When dropping fails
     * while another failure is already propagating (e.g. GDS is not
     * installed at all), the drop failure is attached as suppressed instead
     * of masking the original cause.
     */
    private static void dropProjection(Session session, String graphName, RuntimeException pending) {
        try {
            session.executeWrite(tx -> tx.run(
                    "CALL gds.graph.drop($graphName, false) YIELD graphName RETURN graphName",
                    Map.of("graphName", graphName)).consume());
        } catch (RuntimeException dropFailure) {
            if (pending != null) {
                pending.addSuppressed(dropFailure);
                return;
            }
            throw new IllegalStateException(
                    "Community detection could not drop GDS projection " + graphName + ": "
                            + dropFailure.getMessage(), dropFailure);
        }
    }

    /**
     * The Leiden stream query, run against the projection named
     * {@code $graphName}; it must yield {@code identity} and
     * {@code communityId}. Package-private so a test can make Leiden fail
     * after the projection exists.
     */
    String leidenStreamCypher() {
        return "CALL gds.leiden.stream($graphName, {relationshipWeightProperty: 'weight', "
                + "randomSeed: $randomSeed, concurrency: 1}) "
                + "YIELD nodeId, communityId "
                + "RETURN gds.util.asNode(nodeId).normalizedIdentity AS identity, communityId "
                + "ORDER BY communityId";
    }

    static String projectionName(String corpusId) {
        return PROJECTION_PREFIX + corpusId.replaceAll("[^A-Za-z0-9_-]", "_") + "-" + UUID.randomUUID();
    }
}
