---
title: 'Wire a Real Neo4j Adapter for the Knowledge Graph'
type: 'feature'
created: '2026-09-28'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'e33e24192d19549ece0fc39d0a26f89e27481967'
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `Neo4jGraphStoreAdapter` is `class Neo4jGraphStoreAdapter extends InMemoryGraphStoreAdapter {}` — an empty alias. Entities, Relationships, Communities, and Community Memberships live only in JVM heap and vanish on restart.

**Approach:** Implement `GraphStorePort` for real against the plain Neo4j Java Driver, replacing the alias class. Every corpus-scoped write/read is a real Cypher `MERGE`/query, keyed by the composite constraints AD-20 defines. This story does not wire the adapter into the running app (Story 12.3 does) and does not change `DetectCommunities`'s detection algorithm (still pure-Java BFS today — no real GDS Leiden exists anywhere in the codebase; introducing it is out of scope here).

## Boundaries & Constraints

**Always:** `graphrag-core` gains zero Neo4j import (AD-1). Every write is Cypher `MERGE` on a composite key that includes `corpusId`, never a blind `CREATE`, never an identity-only key: `Entity` on `(corpusId, normalizedIdentity)`, `Community` on `(corpusId, id)`, `Relationship` on `(corpusId, source, type, target)`, `CommunityMembership` on `(corpusId, communityId, entityIdentity)` (AD-20). Every corpus-scoped `GraphStorePort` method the adapter implements is a direct `@Override` with real Cypher — never inherited from the interface's `default` bodies, which no-op (`persistCommunities`/`persistCommunityMemberships`) or silently delegate to the unscoped overload. The two unscoped single-argument methods (`persistEntities(Collection)`, `persistRelationships(Collection)`) throw `UnsupportedOperationException` in this adapter. Composite uniqueness constraints (`CREATE CONSTRAINT ... IF NOT EXISTS ... IS UNIQUE`) are declared idempotently when the adapter is constructed, one per node type. Communities stay first-class `(:Community {corpusId, id, summary})` nodes with `[:BELONGS_TO]` relationships to member Entities (AD-11), never a scalar property.

**Never:** No Spring Data Neo4j — plain `org.neo4j.driver:neo4j-java-driver` only (AD-2). No change to `DetectCommunities.java`, `ExtractEntitiesAndRelationships`/`BuildKnowledgeGraph`, or any `graphrag-core` file. No GDS/Leiden Cypher in this story. No change to `InMemoryGraphStoreAdapter` (kept for the adapter module's own unit tests and any offline-mode use) or to the vector store adapter (separate story, 12.2). No Spring bean wiring / `ParserConfig` change (Story 12.3).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Persist then read back | `persistEntities(corpusId, [Entity("Apple","Org")])` then `entities(corpusId)` | Returns that Entity; a second corpus's `entities(otherCorpusId)` does not include it | N/A |
| Re-ingestion / repeated extraction | Same corpusId + same Entity persisted twice | One node exists (MERGE, not duplicate) | N/A |
| Cross-corpus Community id collision | Two corpora each produce a `Community("community-0", ...)` | Two distinct nodes, one per corpus, keyed by `(corpusId, id)` | N/A |
| Unscoped legacy call | `persistEntities(Collection)` (no corpusId) invoked directly | Throws `UnsupportedOperationException` | Fails loud, never silently writes globally-unscoped nodes |
| Relationship read for empty corpus | `relationships("nonexistent-corpus")` | Returns empty collection, no exception | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jGraphStoreAdapter.java` — replace the alias body with the real implementation; constructor takes `org.neo4j.driver.Driver`.
- `graphrag-core/src/main/java/io/graphrag/core/port/GraphStorePort.java` — read-only reference; every corpus-scoped/no-arg method's default behavior (lines ~33–110) must be overridden, not inherited.
- `graphrag-core/src/main/java/io/graphrag/core/domain/{Entity,Relationship,Community,CommunityMembership}.java` — field shapes for Cypher parameter mapping; `Entity.normalizedIdentity()` is the identity half of the Entity MERGE key.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/DetectCommunities.java:108` — confirms `Community.id` is per-run sequential (`"community-" + index`), the reason the MERGE key must include `corpusId`. Do not modify this file.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/InMemoryGraphStoreAdapter.java` — existing corpus-scoping pattern to mirror for read-method fallback semantics (unscoped `entities()` etc. returning `List.of()`-equivalent is fine to inherit; only writes and the abstract unscoped methods need the throwing/overriding treatment above).
- `graphrag-adapter-neo4j/pom.xml` — add `org.neo4j.driver:neo4j-java-driver` (compile scope) and `org.testcontainers:neo4j` + `org.testcontainers:junit-jupiter` (test scope, new to this repo — no existing Testcontainers usage anywhere).
- `docker-compose.yml:3` — `neo4j:2026.08.1-community` is the pinned version; match it (or the nearest available) in the Testcontainers image tag for realistic parity.

## Tasks & Acceptance

**Execution:**
- [x] `graphrag-adapter-neo4j/pom.xml` -- add neo4j-java-driver + Testcontainers deps -- needed to compile and test the real adapter
- [x] `Neo4jGraphStoreAdapter.java` -- implement constructor(Driver) + idempotent constraint setup -- establishes the composite-key schema before any write
- [x] `Neo4jGraphStoreAdapter.java` -- implement `persistEntities`/`persistRelationships`/`persistCommunities`/`persistCommunityMemberships` (corpus-scoped) as Cypher `MERGE` -- the actual durable writes
- [x] `Neo4jGraphStoreAdapter.java` -- implement `entities`/`relationships`/`communities`/`communityMemberships` (corpus-scoped) as Cypher reads -- durable reads
- [x] `Neo4jGraphStoreAdapter.java` -- override the two unscoped single-arg methods to throw `UnsupportedOperationException` -- closes AD-20's silent-fallback trap
- [x] `graphrag-adapter-neo4j/src/test/java/.../Neo4jGraphStoreAdapterTest.java` (new) -- Testcontainers-backed tests covering the I/O Matrix above -- written and logic manually verified; automated execution blocked in this environment, see Implementation Notes residual risk

**Acceptance Criteria:**
- Given a corpus's Entities/Relationships/Communities/CommunityMemberships persisted via the adapter, when the adapter is a fresh instance pointed at the same Neo4j (simulating a restart), then all four read methods return the same data for that `corpusId`
- Given two different `corpusId`s each producing a `Community` with the same generated `id`, when both are persisted, then Neo4j holds two distinct `(:Community)` nodes, never one merged together

## Implementation Notes

- All 6 execution tasks complete: `pom.xml` gained `neo4j-java-driver:5.28.4` (compile) + `testcontainers:1.20.4` bom-managed `neo4j`/`junit-jupiter` (test). `Neo4jGraphStoreAdapter` replaced the alias with a real `GraphStorePort` implementation: constructor(`Driver`) creates 4 composite uniqueness constraints idempotently (each individually try/caught and logged, non-fatal, per this file's Design Notes); all corpus-scoped write/read methods are direct `@Override`s with real Cypher `MERGE`/`MATCH`; the two unscoped single-arg methods throw `UnsupportedOperationException`. Relationship persistence also `MERGE`s the endpoint Entities by `(corpusId, normalizedIdentity)` so the graph stays connected even if an Entity wasn't separately persisted first. `graphrag-core`, `InMemoryGraphStoreAdapter`, `DetectCommunities`, `ExtractEntitiesAndRelationships`/`BuildKnowledgeGraph`, and `GraphStorePort` were not touched.
- New `Neo4jGraphStoreAdapterTest` (Testcontainers, `neo4j:2026.08.1-community` matching `docker-compose.yml`'s pin) covers every I/O Matrix row plus both ACs.
- **Residual risk, not fully closed:** the new Testcontainers test could not be executed in this environment — Testcontainers' Docker-detection fails against this machine's `~/.testcontainers.properties` (`tc.host=npipe:////./pipe/docker_cli`, an agent-sandboxing proxy pipe that serves the plain `docker` CLI but not the raw Docker Engine API Testcontainers' Java client needs). Confirmed independently in two separate sessions/shells, not a one-off fluke. The user declined repointing that systemwide config (Claude Code's own permission classifier blocked the edit as a sandbox-loosening change). In its place: (a) `InMemoryGraphStoreAdapterTest`/`InMemoryVectorStoreAdapterTest` reran and still pass (3/3, 6/6) unaffected; (b) the implementing subagent manually ran every I/O-matrix scenario as a standalone Java program against the project's own already-running `docker-compose` Neo4j container (the same pinned `neo4j:2026.08.1-community` image) and all passed, including `SHOW CONSTRAINTS` confirming all 4 composite constraints were created. This is real evidence of correctness but is not the same as the automated test suite passing green. **Before this story is considered fully done, run `mvn -pl graphrag-adapter-neo4j test -Dtest=Neo4jGraphStoreAdapterTest` on a machine/CI without this pipe restriction and confirm it passes.**
- Pre-existing gap noted, not introduced or fixed by this story: `DetectCommunities.java` performs community detection via pure-Java BFS/connected-components, not real Neo4j GDS Leiden — AD-4's "UNDIRECTED projection for GDS Leiden" language describes an intent that was never actually implemented anywhere in the codebase. This story's Cypher is pure persistence (MERGE/MATCH), no GDS call; introducing real GDS Leiden is out of scope here and would be its own future story if ever prioritized.

## Spec Change Log

## Review Triage Log

- **patch** (medium) — Corpus-scoped write methods (`persistEntities`/`persistRelationships`/`persistCommunities`/`persistCommunityMemberships`) never validate `corpusId` for null/blank, unlike the read methods. Verified: `Map.of(...)` throws an unguarded `NullPointerException` on a null value, and a blank `corpusId` writes data the read methods' own `isBlank()` guard then makes permanently unretrievable via the port. Found independently by Blind Hunter and Verification Gap Reviewer.
- **patch** (medium) — `persistCommunityMemberships` `MERGE`s a bare `Entity`/`Community` node with no `name`/`type`/`summary` if the membership is persisted before its entity/community. Verified: `entities(corpusId)`/`communities(corpusId)` then call `record.get("name").asString()` unconditionally, which throws on a null property. Currently unreachable via the one live caller (`DetectCommunities` always persists entities/communities first), but latent before Story 12.3 wires this adapter in. Found independently by all three reviewers (Blind Hunter, Edge Case Hunter, Verification Gap Reviewer).
- **patch** (medium) — `persistCommunities(Collection)`/`persistCommunityMemberships(Collection)` (the two unscoped `GraphStorePort` defaults not explicitly overridden) now silently no-op instead of throwing, since the class no longer extends `InMemoryGraphStoreAdapter`. This is exactly the silent-fallback trap AD-20 exists to close — the frozen Boundaries section named only the two abstract unscoped methods (`persistEntities`/`persistRelationships`) needing to throw and omitted these two by oversight, not by deliberate scope decision; a single reading (mirror the existing throw pattern) resolves it without renegotiating intent.
- **patch** (low) — No idempotency test exists for `persistRelationships`/`persistCommunities`/`persistCommunityMemberships` re-ingestion, only for `persistEntities`. Verified: `Neo4jGraphStoreAdapterTest.java` has exactly one such test. Direct, trivial addition mirroring the existing pattern.
- **patch** (low) — No Javadoc note explains why Entities share one generic `:Entity` label and Relationships one generic `:RELATIONSHIP` type (with the real type as a property) rather than dynamic native labels/types. Real, deliberate tradeoff; verified no such note exists in the diff. One-sentence Javadoc fix.
- **patch** (low) — Constructor doesn't guard against a null `Driver`; `ensureConstraint`'s `catch (Neo4jException e)` wouldn't catch an NPE from a null driver's `.session()` call. Verified: fix is a direct `Objects.requireNonNull` one-liner, so kept despite low severity/likelihood.
- **false** — Blind Hunter raised a possible divergence between `Entity.normalizedIdentity()` and `Entity.identityOf(name, type)` (used for relationship endpoints). Verified false: `Entity.identityOf` (`Entity.java:26-28`) is defined as `new Entity(name, type).normalizedIdentity()` — they can never disagree.
- **low, rejected** — `ensureConstraint`'s "unconstrained fallback" path (composite relationship-property constraints not supported on some Neo4j editions) has no test forcing that path. Real gap, but the fix requires driver/exception mocking infrastructure this module has none of — more than a direct correction, and the pinned real Neo4j version (2026.08.1-community) already supports all 4 constraints per this story's manual verification, so the path is inert on the one environment that matters today.
- **low, rejected** — Per-item `tx.run(...)` loops (vs. batched `UNWIND`) in every write method. Real perf concern at scale, but this is a single-user/local-only app (NFR3) with realistic corpus sizes; the fix (rewriting all 4 write bodies around list-parameterized `UNWIND`) is more than a direct correction.
- **defer** — No pagination/streaming on any corpus-scoped read method; would need a `GraphStorePort` signature change, out of this adapter-only story's blast radius. Logged to `deferred-work.md`.
- **defer** — No pruning of entities/relationships/communities that vanish from a corpus's source documents on re-ingestion of the *same* `corpusId` — the graph only grows. Re-ingestion-of-an-existing-corpus isn't wired anywhere in the app yet, and the intent doesn't settle whether pruning is even desired. Logged to `deferred-work.md`.
- **defer** — The Testcontainers-based `Neo4jGraphStoreAdapterTest` has still not been confirmed to execute successfully in an automated run (see Implementation Notes) — blocked in this dev environment by a Docker-sandboxing pipe restriction the user chose not to loosen after Claude Code's own permission classifier flagged the change. Logged to `deferred-work.md` as an explicit before-merge action item.

## Design Notes

Constraint setup runs in the adapter's constructor (not a separate Spring `@PostConstruct`, since this adapter isn't Spring-wired yet — that's Story 12.3): `CREATE CONSTRAINT entity_corpus_identity IF NOT EXISTS FOR (e:Entity) REQUIRE (e.corpusId, e.normalizedIdentity) IS UNIQUE`, and similarly for `Community`, `Relationship` (if Neo4j version supports relationship-property uniqueness constraints; otherwise MERGE on the four properties is still correct, just unconstrained), and `CommunityMembership`.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-adapter-neo4j -am test` -- expected: `Neo4jGraphStoreAdapterTest` passes against a Testcontainers-managed Neo4j instance, `InMemoryGraphStoreAdapterTest` unaffected
- `mvn -q -B clean install` -- expected: full reactor green, no other module broken by the new dependency
