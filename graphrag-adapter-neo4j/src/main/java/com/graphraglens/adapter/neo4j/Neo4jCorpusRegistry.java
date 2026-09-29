package com.graphraglens.adapter.neo4j;

import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.UploadedDocument;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.Neo4jException;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable, Neo4j-backed replacement for the deleted {@code CorpusStore}.
 * A plain class, not a {@code graphrag-core} port (AD-19) — this is
 * demo-app bookkeeping, not a GraphRAG-library concern, so {@code
 * graphrag-web} calls it directly, the same way it would call any other
 * adapter-side Spring bean.
 *
 * <p>Persists one {@code (:CorpusMeta {corpusId, name, documentNames,
 * status, createdAt, lastActivatedAt})} node per corpus, uniqueness
 * constrained on {@code corpusId} alone (AD-20's per-corpus scoping doesn't
 * apply here — a corpus registry entry has no narrower scope than the
 * corpus itself). Mirrors {@link Neo4jGraphStoreAdapter}'s constructor/
 * {@code ensureConstraint} shape.</p>
 *
 * <p>{@code createdAt}/{@code lastActivatedAt} are written from this class's
 * own {@link Instant#now()} as a Cypher parameter, never Neo4j's server-side
 * {@code datetime()} (AD-22). Raw document bytes are never persisted — only
 * {@link Corpus#documentNames()} (filenames); {@link #get(String)}
 * reconstructs a {@link Corpus} using empty-content {@link UploadedDocument}
 * placeholders, since nothing downstream of a registry lookup reads document
 * content.</p>
 *
 * <p>Demo/offline corpora ({@link #markOffline(String)}) are the one
 * exception: a plain in-process field, never persisted, lost on restart,
 * exactly as {@code CorpusStore} held them.</p>
 */
public class Neo4jCorpusRegistry {

    public enum CorpusWorkflowStatus {
        BUILDING,
        READY,
        FAILED
    }

    private static final Logger LOG = System.getLogger(Neo4jCorpusRegistry.class.getName());

    private final Driver driver;
    private final Set<String> offlineCorpusIds = ConcurrentHashMap.newKeySet();

    public Neo4jCorpusRegistry(Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        ensureConstraint();
    }

    private void ensureConstraint() {
        String cypher = "CREATE CONSTRAINT corpus_meta_corpus_id IF NOT EXISTS "
                + "FOR (c:CorpusMeta) REQUIRE c.corpusId IS UNIQUE";
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run(cypher).consume());
        } catch (Neo4jException e) {
            LOG.log(Level.WARNING, () -> "Could not create constraint (continuing unconstrained): " + cypher
                    + " -- " + e.getMessage());
        }
    }

    public void put(Corpus corpus) {
        Objects.requireNonNull(corpus, "corpus");
        String now = Instant.now().toString();
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:CorpusMeta {corpusId: $corpusId}) "
                                + "SET c.name = $name, c.documentNames = $documentNames, "
                                + "c.status = $status, c.createdAt = $createdAt, c.lastActivatedAt = $lastActivatedAt",
                        Map.of(
                                "corpusId", corpus.id(),
                                "name", corpus.name(),
                                "documentNames", corpus.documentNames(),
                                "status", CorpusWorkflowStatus.BUILDING.name(),
                                "createdAt", now,
                                "lastActivatedAt", now));
                return null;
            });
        }
    }

    /**
     * Marks a corpus as built entirely through the demo-safe offline path
     * (Story 9.1) — its own deterministic LLM/embedding stubs, never the
     * app's normally-configured ports, regardless of whether
     * {@code OPENAI_API_KEY} is set. Query-time blocks on this alone, so a
     * live call can never leak in through the query path either. Held
     * in-process only, never persisted (non-goal: durable history for
     * demo/offline corpora).
     */
    public void markOffline(String corpusId) {
        if (corpusId != null && !corpusId.isBlank()) {
            offlineCorpusIds.add(corpusId);
        }
    }

    public boolean isOffline(String corpusId) {
        return corpusId != null && offlineCorpusIds.contains(corpusId);
    }

    public Optional<Corpus> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<Record> records = tx.run(
                        "MATCH (c:CorpusMeta {corpusId: $corpusId}) "
                                + "RETURN c.name AS name, c.documentNames AS documentNames",
                        Map.of("corpusId", id)).list();
                if (records.isEmpty()) {
                    return Optional.empty();
                }
                Record record = records.get(0);
                String name = record.get("name").asString();
                List<String> documentNames = record.get("documentNames").asList(Value::asString);
                List<UploadedDocument> documents = documentNames.stream()
                        .map(filename -> new UploadedDocument(filename, ""))
                        .toList();
                return Optional.of(new Corpus(id, documents, name));
            });
        }
    }

    public CorpusWorkflowStatus status(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return CorpusWorkflowStatus.BUILDING;
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<Record> records = tx.run(
                        "MATCH (c:CorpusMeta {corpusId: $corpusId}) RETURN c.status AS status",
                        Map.of("corpusId", corpusId)).list();
                if (records.isEmpty()) {
                    return CorpusWorkflowStatus.BUILDING;
                }
                String status = records.get(0).get("status").asString();
                return CorpusWorkflowStatus.valueOf(status);
            });
        }
    }

    public void markReady(String corpusId) {
        updateStatus(corpusId, CorpusWorkflowStatus.READY);
    }

    public void markFailed(String corpusId) {
        updateStatus(corpusId, CorpusWorkflowStatus.FAILED);
    }

    private void updateStatus(String corpusId, CorpusWorkflowStatus status) {
        if (corpusId == null || corpusId.isBlank()) {
            return;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (c:CorpusMeta {corpusId: $corpusId}) SET c.status = $status",
                        Map.of("corpusId", corpusId, "status", status.name()));
                return null;
            });
        }
    }
}
