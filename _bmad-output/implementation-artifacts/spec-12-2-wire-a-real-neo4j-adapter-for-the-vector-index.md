---
title: 'Wire a Real Neo4j Adapter for the Vector Index'
type: 'feature'
created: '2026-09-28'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'b18a19b4c1e3bdb655d735c30d48db3102ea7031'
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `Neo4jVectorStoreAdapter` is `class Neo4jVectorStoreAdapter extends InMemoryVectorStoreAdapter {}` — an empty alias, same as `Neo4jGraphStoreAdapter` was before Story 12.1. A corpus's embedded chunks and fitted 2D projection model live only in JVM heap and vanish on restart.

**Approach:** Implement `VectorStorePort` for real against the plain Neo4j Java Driver, replacing the alias class, reusing Story 12.1's established patterns (constructor(Driver), idempotent constraint setup, corpus-scoped Cypher). This story does not wire the adapter into the running app (Story 12.3 does).

## Boundaries & Constraints

**Always:** `graphrag-core` gains zero Neo4j import (AD-1). `persistChunks(corpusId, chunks)` is Cypher `MERGE` on `(corpusId, chunk.id)` (AD-20) — re-processing the same chunk id replaces its text/embedding/projection rather than duplicating the node, matching `InMemoryVectorStoreAdapter`'s existing overwrite behavior. `persistProjectionModel(corpusId, model)`/`projectionModel(corpusId)` read/write exactly one `(:ProjectionModel {corpusId, mean, pc1, pc2})` node per corpus, MERGE-keyed on `corpusId` alone. Chunk embeddings (`float[]`) and all three `ProjectionModel` arrays (`double[]`) are stored as Cypher `LIST<FLOAT>` properties, passed to the driver as plain primitive arrays (no manual boxing to `List<Double>`/`List<Float>` needed). A composite uniqueness constraint on `(corpusId, id)` for `:Chunk` is declared idempotently at construction, mirroring `Neo4jGraphStoreAdapter`'s `ensureConstraint` pattern.

**Never:** No Spring Data Neo4j — plain driver only (AD-2). No change to `graphrag-core` (`VectorStorePort`, `ConstructVectorIndex`, `AnswerVectorBaseline`, domain records) or to `InMemoryVectorStoreAdapter`/`InMemoryGraphStoreAdapter`/`Neo4jGraphStoreAdapter`. No Spring bean wiring / `ParserConfig` change (Story 12.3). No `CREATE VECTOR INDEX` with a hardcoded embedding dimensionality: `VectorStorePort.chunks(corpusId)` takes no query vector — nothing in the current codebase performs a similarity search through this port (`AnswerVectorBaseline` reads every chunk for a corpus via `chunks(corpusId)` and ranks by cosine similarity itself, in Java) — so there is no real caller to size a vector index for yet, and guessing a dimension the embedding provider might not match is worse than not creating one. `chunks(corpusId)` is a plain corpus-scoped Cypher read, never a `SEARCH` clause.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Persist then read back | `persistChunks(corpusId, [chunk1, chunk2])` then `chunks(corpusId)` | Returns both chunks with their embedding/projection intact; a second corpus's `chunks(otherCorpusId)` does not include them | N/A |
| Re-processing same chunk id | Same `corpusId` + same `chunk.id()` persisted twice with different text/embedding | One node exists, latest values win (MERGE, not duplicate) | N/A |
| Unknown corpus read | `chunks("nonexistent-corpus")` | Returns empty collection, no exception | N/A |
| Null/blank corpusId or null chunks on write | `persistChunks(null, ...)` / `persistChunks("", ...)` / `persistChunks(corpusId, null)` | No-op, no exception (matches `InMemoryVectorStoreAdapter`'s existing contract — this port has no unscoped-overload trap to close, unlike `GraphStorePort`) | N/A |
| ProjectionModel round-trip | `persistProjectionModel(corpusId, model)` then `projectionModel(corpusId)` | Returns a model with `mean`/`pc1`/`pc2` arrays equal (`assertArrayEquals`) to what was persisted | N/A |
| ProjectionModel for unknown corpus | `projectionModel("nonexistent-corpus")` | Returns `Optional.empty()` | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jVectorStoreAdapter.java` — replace the alias body with the real implementation; constructor takes `org.neo4j.driver.Driver` (same shape as `Neo4jGraphStoreAdapter`).
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jGraphStoreAdapter.java` — the established pattern to mirror: `Objects.requireNonNull(driver, "driver")` in the constructor, an `ensureConstraint(String cypher)` helper that try/catches `Neo4jException` and logs rather than fails, `driver.session()` + `executeWrite`/`executeRead` for Cypher, a `requireCorpusId` guard on writes. Reuse the same structural shape; do not change this file.
- `graphrag-core/src/main/java/io/graphrag/core/port/VectorStorePort.java` — only `persistChunks(String, Collection<EmbeddedChunk>)` is abstract; `chunks(String)` (default → `List.of()`), `persistProjectionModel`/`projectionModel` (default no-ops) must all be directly `@Override`-n, not inherited (same discipline as Story 12.1, though this port has no unscoped/scoped pair to trap on — every method already takes `corpusId`).
- `graphrag-core/src/main/java/io/graphrag/core/domain/{Chunk,EmbeddedChunk,ProjectionModel}.java` — `Chunk(id, corpusId, ordinal, text)`; `EmbeddedChunk(Chunk chunk, float[] embedding, double[] projection)`; `ProjectionModel(double[] mean, double[] pc1, double[] pc2)` with defensive-copy accessors — reconstruct these from Cypher query results field-by-field.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/InMemoryVectorStoreAdapter.java` — behavioral contract to match: overwrite-not-duplicate on re-`persistChunks` by `chunk.id()`, no-op (not exception) on null/blank `corpusId` or null collection/model.
- `graphrag-adapter-neo4j/src/test/java/com/graphraglens/adapter/neo4j/InMemoryVectorStoreAdapterTest.java` — mirror this test suite's scenarios (corpus-scoped isolation, unknown-corpus empty read, re-processing replaces not duplicates, null/blank no-ops, projection-model round trip via `assertArrayEquals`) against the real adapter.
- `graphrag-adapter-neo4j/src/test/java/com/graphraglens/adapter/neo4j/Neo4jGraphStoreAdapterTest.java` — the Testcontainers test-setup pattern (`@Testcontainers`, `Neo4jContainer<>("neo4j:2026.08.1-community").withoutAuthentication()`, shared `Driver` in `@BeforeAll`/`@AfterAll`) to copy for the new `Neo4jVectorStoreAdapterTest`.
- `graphrag-adapter-neo4j/pom.xml` — already has `neo4j-java-driver` + Testcontainers deps from Story 12.1; no change needed.

## Tasks & Acceptance

**Execution:**
- [x] `Neo4jVectorStoreAdapter.java` -- implement constructor(Driver) + idempotent `(corpusId, id)` uniqueness constraint on `:Chunk` -- establishes the composite key before any write
- [x] `Neo4jVectorStoreAdapter.java` -- implement `persistChunks(corpusId, Collection<EmbeddedChunk>)` as Cypher `MERGE` storing embedding/projection as `LIST<FLOAT>` properties -- the durable write
- [x] `Neo4jVectorStoreAdapter.java` -- implement `chunks(corpusId)` as a plain corpus-scoped Cypher read reconstructing `EmbeddedChunk`/`Chunk` -- the durable read
- [x] `Neo4jVectorStoreAdapter.java` -- implement `persistProjectionModel`/`projectionModel` as Cypher `MERGE`/`MATCH` on one `(:ProjectionModel {corpusId})` node -- durable projection-model storage
- [x] `graphrag-adapter-neo4j/src/test/java/.../Neo4jVectorStoreAdapterTest.java` (new) -- Testcontainers-backed tests covering the I/O Matrix above -- written and logic manually verified; automated execution blocked by the same Docker-sandboxing gap as Story 12.1, see Implementation Notes

**Acceptance Criteria:**
- Given a corpus's embedded chunks and projection model persisted via the adapter, when the adapter is a fresh instance pointed at the same Neo4j (simulating a restart), then `chunks(corpusId)` and `projectionModel(corpusId)` return the same data
- Given the same `chunk.id()` persisted twice with different embeddings, when `chunks(corpusId)` is read, then exactly one chunk exists with the latest embedding, never two

## Implementation Notes

- All 5 execution tasks complete. `Neo4jVectorStoreAdapter` mirrors `Neo4jGraphStoreAdapter`'s established shape: `Objects.requireNonNull(driver)`, idempotent `ensureConstraint` for a `(corpusId, id)` composite uniqueness constraint on `:Chunk`, `driver.session()` + `executeWrite`/`executeRead`. `persistChunks` MERGEs and overwrites by `(corpusId, chunk.id)`; `chunks(corpusId)` is a plain corpus-scoped `MATCH` (no `SEARCH` clause, no vector index — per the spec's explicit "Never", nothing calls this port with a query vector to search against). `persistProjectionModel`/`projectionModel` MERGE/MATCH one `(:ProjectionModel {corpusId})` node. `float[]`/`double[]` pass through the driver as plain primitive arrays with no manual boxing.
- New `Neo4jVectorStoreAdapterTest` (Testcontainers) covers every I/O Matrix row plus both ACs, following `Neo4jGraphStoreAdapterTest`'s setup pattern.
- **Same residual risk as Story 12.1:** the Testcontainers test could not execute in this dev environment (Docker-sandboxing pipe restriction, already logged in `deferred-work.md`). Logic manually verified against the project's own running `docker-compose` Neo4j container (`ALL CHECKS PASSED` for every I/O Matrix scenario); this is not the same as the automated suite passing green. Covered by the existing deferred-work.md action item to confirm both `Neo4jGraphStoreAdapterTest` and `Neo4jVectorStoreAdapterTest` on an unrestricted machine/CI.
- `InMemoryGraphStoreAdapterTest`/`InMemoryVectorStoreAdapterTest` rerun clean (no regression) after this change.

## Spec Change Log

## Review Triage Log

- **patch** (medium) — `persistChunks`'s per-chunk `Map.of(...)` throws `NullPointerException` on any null value, including `chunk.text()`, `embeddedChunk.embedding()`, or `embeddedChunk.projection()` being null (unlike `ProjectionModel`, whose compact constructor already null-safes its three arrays). Found independently by Blind Hunter and Edge Case Hunter.
- **patch** (medium) — No uniqueness constraint exists on `(:ProjectionModel {corpusId})`, unlike `:Chunk`. Verified: under a constraint-creation failure or a first-construction race, `MERGE` without a backing constraint can create duplicate `ProjectionModel` nodes for the same corpus, and `projectionModel(corpusId)` then picks whichever record comes back first — nondeterministic, contradicting the documented one-model-per-corpus guarantee. Found independently by Blind Hunter and Edge Case Hunter.
- **patch** (low) — No pointer links the class Javadoc's "no vector index created yet" note to the tracked backlog item. One-line addition referencing `deferred-work.md`.
- **false** — Edge Case Hunter flagged the four data methods (`persistChunks`/`chunks`/`persistProjectionModel`/`projectionModel`) for not catching `Neo4jException` the way `ensureConstraint` does. Verified false: this matches `Neo4jGraphStoreAdapter`'s established, deliberate pattern from Story 12.1 — only constraint setup has documented graceful degradation (MERGE stays correct even unconstrained); actual data reads/writes are meant to fail loud on a genuine Neo4j error, consistent with AD-21's "never silently degrade" ethos. Not a regression or inconsistency.
- **low, rejected** — `chunks()`/`projectionModel()` call `.asList()`/`.asFloat()` etc. with no null-property guard, which would throw if a node were ever partially written. Verified real in principle, but each write happens inside one Cypher `MERGE ... SET` statement per chunk/model within a single transaction (atomic per-item), so a genuinely partial node isn't reachable through this adapter's own write paths today; the fix would touch all four read methods, more than a direct correction.
- **low, rejected** — No test exercises `ensureConstraint`'s `catch (Neo4jException)` fallback path directly. Same disposition as Story 12.1's identical finding: real gap, but the fix needs driver/exception-mocking infrastructure this module doesn't have, and the pinned real Neo4j version already supports the constraint in practice.
- **low, rejected** — Per-chunk `tx.run(...)` loop instead of batched `UNWIND`. Same disposition as Story 12.1's identical finding: real perf concern at scale, not a correctness issue for a single-user/local-only app (NFR3); the fix (rewriting the write body around list-parameterized `UNWIND`) is more than a direct correction.

## Design Notes

No `CREATE VECTOR INDEX` is created in this story (see Boundaries & Constraints' "Never" — no real caller needs similarity search through this port yet, and guessing an embedding dimensionality to configure one would be speculative). Embeddings are still stored as `LIST<FLOAT>` properties on `:Chunk` nodes, satisfying AD-17's storage-shape intent; creating the actual vector index is deferred until a real similarity-search capability and a known, stable dimensionality exist to size it correctly.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-adapter-neo4j -am test` -- expected: `Neo4jVectorStoreAdapterTest` passes against a Testcontainers-managed Neo4j instance (or, if this environment's Docker-sandboxing gap from Story 12.1 still applies, manually verify against the project's own running `docker-compose` Neo4j container and document the same residual-risk caveat); `InMemoryVectorStoreAdapterTest`/`Neo4jGraphStoreAdapterTest` unaffected
- `mvn -q -B clean install` -- expected: full reactor green
