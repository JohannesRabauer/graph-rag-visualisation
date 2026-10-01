package dev.rabauer.graphrag.adapter.neo4j;

import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.UploadedDocument;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.Neo4jException;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
 * own {@link Instant#now()} (converted to {@link ZonedDateTime} at UTC, since
 * the Neo4j Java driver has no direct {@code Instant} parameter mapping) as a
 * Cypher parameter, never Neo4j's server-side {@code datetime()} (AD-22).
 * Stored as Neo4j's native temporal type so {@code ORDER BY} sorts
 * chronologically, not as a lexicographically-fragile string. Raw document
 * bytes are never persisted — only
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
        ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
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

    /**
     * A single retained corpus's registry-list entry, as returned by
     * {@link #list()} -- deliberately not {@link Corpus} itself, since a
     * history-list row needs workflow status and both timestamps but never
     * document content.
     */
    public record CorpusSummary(
            String corpusId,
            String name,
            CorpusWorkflowStatus status,
            String createdAt,
            String lastActivatedAt) {
    }

    /**
     * Story 12.6: every retained corpus, ordered most-recently-activated
     * first (via Cypher {@code ORDER BY}, not an in-JVM sort), excluding
     * demo/offline corpora ({@link #isOffline(String)}) -- the durable
     * history-list data source for {@code GET /api/corpora}.
     */
    public List<CorpusSummary> list() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run(
                            "MATCH (c:CorpusMeta) RETURN c.corpusId AS corpusId, c.name AS name, "
                                    + "c.status AS status, c.createdAt AS createdAt, "
                                    + "c.lastActivatedAt AS lastActivatedAt "
                                    + "ORDER BY c.lastActivatedAt DESC")
                    .list())
                    .stream()
                    .map(record -> new CorpusSummary(
                            record.get("corpusId").asString(),
                            record.get("name").asString(),
                            CorpusWorkflowStatus.valueOf(record.get("status").asString()),
                            record.get("createdAt").asZonedDateTime().toInstant().toString(),
                            record.get("lastActivatedAt").asZonedDateTime().toInstant().toString()))
                    .filter(summary -> !isOffline(summary.corpusId()))
                    .toList();
        }
    }

    /**
     * Story 12.6: the only path that ever updates {@code lastActivatedAt}
     * after creation, called exclusively by {@code POST
     * /api/corpora/{corpusId}/activate} -- never inferred from query traffic
     * (AD per epic-12-context). Sets a fresh {@link Instant#now()}, written as
     * a Cypher parameter, never Neo4j's server-side {@code datetime()} (AD-22).
     * No-ops silently for an unknown {@code corpusId}; existence/404 is the
     * controller's job, matching {@link #get(String)}'s
     * {@code Optional}-then-{@code orElseThrow} pattern.
     */
    public void activate(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return;
        }
        ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (c:CorpusMeta {corpusId: $corpusId}) SET c.lastActivatedAt = $lastActivatedAt",
                        Map.of("corpusId", corpusId, "lastActivatedAt", now));
                return null;
            });
        }
    }

    /**
     * Story 12.5: a startup sweep, not a per-corpus operation. Transitions
     * every {@code CorpusMeta} node still {@code BUILDING} (left behind by a
     * crash mid-ingestion) to {@code FAILED}, via one bulk conditional Cypher
     * write inside Neo4j's own transaction -- never a read into the JVM
     * followed by per-corpus writes, so it stays correct even if two {@code
     * app} instances briefly overlap during a redeploy. Already-settled
     * ({@code READY}/{@code FAILED}) corpora are untouched by the {@code
     * WHERE} clause; an empty {@code CorpusMeta} set is a no-op.
     *
     * @return the number of {@code CorpusMeta} nodes actually flipped to
     *     {@code FAILED}, taken from Neo4j's own write-result counters
     *     ({@code propertiesSet()} -- exactly one property is set per
     *     affected node by this query), so the caller can log it.
     */
    public long reconcileInterruptedCorpora() {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> tx.run(
                            "MATCH (c:CorpusMeta) WHERE c.status = $buildingStatus SET c.status = $failedStatus",
                            Map.of(
                                    "buildingStatus", CorpusWorkflowStatus.BUILDING.name(),
                                    "failedStatus", CorpusWorkflowStatus.FAILED.name()))
                    .consume()
                    .counters()
                    .propertiesSet());
        }
    }
}
