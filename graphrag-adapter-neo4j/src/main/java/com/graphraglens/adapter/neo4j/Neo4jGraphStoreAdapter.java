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
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
                    tx.run("MERGE (c:Community {corpusId: $corpusId, id: $id}) SET c.summary = $summary",
                            Map.of(
                                    "corpusId", corpusId,
                                    "id", community.id(),
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
    public List<Community> communities(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<Community> result = new ArrayList<>();
                for (Record record : tx.run("MATCH (c:Community {corpusId: $corpusId}) RETURN c.id AS id, "
                        + "c.summary AS summary", Map.of("corpusId", corpusId)).list()) {
                    result.add(new Community(record.get("id").asString(), record.get("summary").asString()));
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
}
