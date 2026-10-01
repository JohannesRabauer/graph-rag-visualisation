package dev.rabauer.graphrag.adapter.neo4j;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.ProjectionModel;
import dev.rabauer.graphrag.core.port.VectorStorePort;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.Neo4jException;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Real Neo4j-backed implementation of {@link VectorStorePort}, using the
 * plain {@code org.neo4j.driver} (never Spring Data Neo4j). Chunks are
 * written via Cypher {@code MERGE} keyed by a composite {@code (corpusId,
 * id)} key (AD-20), so re-processing the same chunk id replaces its
 * text/embedding/projection rather than duplicating the node. The fitted
 * {@link ProjectionModel} is one {@code (:ProjectionModel {corpusId})} node
 * per corpus, MERGE-keyed on {@code corpusId} alone.
 *
 * <p>Embeddings ({@code float[]}) and the three {@link ProjectionModel}
 * arrays ({@code double[]}) are stored as Cypher {@code LIST<FLOAT>}
 * properties, passed to the driver as plain primitive arrays.</p>
 *
 * <p>No {@code CREATE VECTOR INDEX} is declared here: nothing in the current
 * codebase performs a similarity search through this port, so there is no
 * real caller to size a vector index for yet. Tracked as a follow-up in
 * {@code _bmad-output/implementation-artifacts/deferred-work.md}.</p>
 */
public class Neo4jVectorStoreAdapter implements VectorStorePort {

    private static final Logger LOG = System.getLogger(Neo4jVectorStoreAdapter.class.getName());

    private final Driver driver;

    public Neo4jVectorStoreAdapter(Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        ensureConstraints();
    }

    private void ensureConstraints() {
        ensureConstraint(
                "CREATE CONSTRAINT chunk_corpus_id IF NOT EXISTS "
                        + "FOR (c:Chunk) REQUIRE (c.corpusId, c.id) IS UNIQUE");
        ensureConstraint(
                "CREATE CONSTRAINT projection_model_corpus_id IF NOT EXISTS "
                        + "FOR (p:ProjectionModel) REQUIRE p.corpusId IS UNIQUE");
    }

    /**
     * Declares a single uniqueness constraint idempotently, logging rather
     * than failing if it cannot be created (mirrors
     * {@link Neo4jGraphStoreAdapter#ensureConstraint(String)}).
     */
    private void ensureConstraint(String cypher) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher).consume());
        } catch (Neo4jException e) {
            LOG.log(Level.WARNING, () -> "Could not create constraint (continuing unconstrained): " + cypher
                    + " -- " + e.getMessage());
        }
    }

    @Override
    public void persistChunks(String corpusId, Collection<EmbeddedChunk> chunks) {
        if (corpusId == null || corpusId.isBlank() || chunks == null) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                for (EmbeddedChunk embeddedChunk : chunks) {
                    if (embeddedChunk == null || embeddedChunk.chunk() == null) {
                        continue;
                    }
                    Chunk chunk = embeddedChunk.chunk();
                    if (chunk.text() == null || embeddedChunk.embedding() == null
                            || embeddedChunk.projection() == null) {
                        continue;
                    }
                    tx.run("MERGE (c:Chunk {corpusId: $corpusId, id: $id}) "
                                    + "SET c.ordinal = $ordinal, c.text = $text, "
                                    + "c.embedding = $embedding, c.projection = $projection",
                            Map.of(
                                    "corpusId", corpusId,
                                    "id", chunk.id(),
                                    "ordinal", chunk.ordinal(),
                                    "text", chunk.text(),
                                    "embedding", embeddedChunk.embedding(),
                                    "projection", embeddedChunk.projection()));
                }
                return null;
            });
        }
    }

    @Override
    public Collection<EmbeddedChunk> chunks(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<EmbeddedChunk> result = new ArrayList<>();
                for (Record record : tx.run(
                        "MATCH (c:Chunk {corpusId: $corpusId}) RETURN c.id AS id, c.ordinal AS ordinal, "
                                + "c.text AS text, c.embedding AS embedding, c.projection AS projection",
                        Map.of("corpusId", corpusId)).list()) {
                    Chunk chunk = new Chunk(
                            record.get("id").asString(),
                            corpusId,
                            record.get("ordinal").asInt(),
                            record.get("text").asString());
                    float[] embedding = toFloatArray(record.get("embedding").asList(Value::asFloat));
                    double[] projection = toDoubleArray(record.get("projection").asList(Value::asDouble));
                    result.add(new EmbeddedChunk(chunk, embedding, projection));
                }
                return result;
            });
        }
    }

    @Override
    public void persistProjectionModel(String corpusId, ProjectionModel model) {
        if (corpusId == null || corpusId.isBlank() || model == null) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (p:ProjectionModel {corpusId: $corpusId}) "
                                + "SET p.mean = $mean, p.pc1 = $pc1, p.pc2 = $pc2",
                        Map.of(
                                "corpusId", corpusId,
                                "mean", model.mean(),
                                "pc1", model.pc1(),
                                "pc2", model.pc2()));
                return null;
            });
        }
    }

    @Override
    public Optional<ProjectionModel> projectionModel(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return Optional.empty();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<Record> records = tx.run(
                        "MATCH (p:ProjectionModel {corpusId: $corpusId}) "
                                + "RETURN p.mean AS mean, p.pc1 AS pc1, p.pc2 AS pc2",
                        Map.of("corpusId", corpusId)).list();
                if (records.isEmpty()) {
                    return Optional.empty();
                }
                Record record = records.get(0);
                double[] mean = toDoubleArray(record.get("mean").asList(Value::asDouble));
                double[] pc1 = toDoubleArray(record.get("pc1").asList(Value::asDouble));
                double[] pc2 = toDoubleArray(record.get("pc2").asList(Value::asDouble));
                return Optional.of(new ProjectionModel(mean, pc1, pc2));
            });
        }
    }

    private static float[] toFloatArray(List<Float> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static double[] toDoubleArray(List<Double> values) {
        double[] result = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }
}
