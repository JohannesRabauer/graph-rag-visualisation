---
title: 'Detect Communities with GDS Leiden'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: 'ff63490c379745eb24cf85df61e2a1ddf10478f6'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** `DetectCommunities` groups Entities by BFS connected components, so a well-linked area becomes one giant Community and every isolated pair its own Community. FR-6/AD-4 promised modularity-based GDS Leiden, and the GDS plugin is already installed in `docker-compose.yml`.

**Approach:** Add `GraphStorePort.detectCommunities(corpusId)` returning member-identity groups. Its default implementation is the existing connected-components algorithm, so the in-memory adapter and offline mode are unchanged. `Neo4jGraphStoreAdapter` overrides it with a corpus-scoped, undirected, weighted GDS projection plus `gds.leiden.stream`. `DetectCommunities` keeps summarizing, persisting and the callback, and gets its grouping from the port.

## Boundaries & Constraints

**Always:**
- `graphrag-core` stays framework-free: no Neo4j or GDS types.
- The projection includes only Entities with `corpusId = $corpusId` and `:RELATIONSHIP` edges with that `corpusId`.
- The projection is `UNDIRECTED` and weighted by `toFloat(coalesce(r.weight, 1))`.
- The graph name contains the corpusId (sanitized to `[A-Za-z0-9_-]`) plus a unique suffix, so concurrent runs never collide.
- Leiden uses a fixed `randomSeed` and `concurrency: 1` for reproducibility.
- The projection is dropped in a `finally` (`gds.graph.drop(name, false)`), even when Leiden fails.
- An Entity with no relationships becomes its own single-member Community.
- Community ids stay `community-1..n`. They are assigned in the order of each group's first member in `graphStorePort.entities(corpusId)`, and members keep that order, so the ids are deterministic for any adapter.
- The `onCommunityDetected` ordering contract (fire only after both persists) is preserved.
- When GDS is unavailable or Leiden throws, the exception propagates. The corpus is marked FAILED and an SSE `error` event is sent. The message names community detection, not the LLM.

**Never:**
- No silent fallback to connected components on the Neo4j path.
- No hierarchical or multi-level communities.
- No change to community summary content; that is Story 14.2.
- No new Maven profiles, failsafe, or `*IT` naming.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Two dense groups + bridge (Neo4j) | 2 four-node cliques joined by 1 edge | 2 Communities, each = one clique | none |
| Same graph, default (in-memory) | same | 1 Community (connected components, unchanged) | none |
| Isolated entity (Neo4j) | entity without relationships alongside linked ones | its own single-member Community | none |
| No relationships at all (Neo4j) | 3 entities, 0 edges | 3 single-member Communities, no GDS call needed | none |
| No entities | empty corpus | empty list, nothing persisted | none |
| Other corpus present | second corpus with linked entities | not in result, untouched | none |
| GDS missing / Leiden fails | procedure not found | exception propagates; projection dropped if created | corpus FAILED + SSE error naming community detection |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/io/graphrag/core/port/GraphStorePort.java` -- add `default List<List<String>> detectCommunities(String corpusId)`. It builds connected components from `entities(corpusId)` and `relationships(corpusId)` (private static helper in the interface) and returns groups of `Entity.identityOf(name,type)`-style identities in entity order. The 6 test fakes and the in-memory adapter inherit it; none needs editing.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/DetectCommunities.java:55-114` -- replace the BFS with a call to the port. Map identities back to the `Entity` objects with that identity (keep duplicates, as today), order groups by first-member entity index, then keep the existing summary, persist and callback code. Update the class Javadoc.
- `graphrag-core/src/main/java/io/graphrag/core/domain/Entity.java:23,34` -- `normalizedIdentity()` / `identityOf` are the identity format, the same as the private helper in DetectCommunities:161-169.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jGraphStoreAdapter.java` -- override `detectCommunities`. Entities are `:Entity {corpusId, normalizedIdentity}`; edges are `:RELATIONSHIP {corpusId, weight}` (int), lines 184-196. Session pattern: `driver.session()` + `executeWrite/executeRead`. Use the Cypher-aggregation projection `gds.graph.project(name, s, t, {relationshipProperties:{weight:...}}, {undirectedRelationshipTypes:['*']})` from `MATCH (s:Entity {corpusId}) OPTIONAL MATCH (s)-[r:RELATIONSHIP {corpusId}]->(t:Entity {corpusId})`. If the corpus has no relationships, return singletons without calling GDS (Leiden on an edgeless graph is pointless). Map `gds.util.asNode(nodeId).normalizedIdentity`. Any entity the stream omits becomes a singleton.
- `graphrag-adapter-neo4j/src/test/java/com/graphraglens/adapter/neo4j/Neo4jGraphStoreAdapterTest.java:31-50` -- Testcontainers pattern (`neo4j:2026.08.1-community`, `withoutAuthentication()`, unique `corpus-<nanoTime>` ids). Add GDS via `.withEnv("NEO4J_PLUGINS", "[\"graph-data-science\"]")`.
- `graphrag-web/src/test/java/com/graphraglens/web/SharedNeo4jTestContainer.java` -- the shared container for SpringBootTest and UI tests. Ingestion tests there run against the real adapter, so they need GDS too (same env).
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java:472-503` -- ingestion pipeline catch block. It currently always sends `EXTRACTION_FAILURE_MESSAGE` (LLM wording). Add a distinct community-detection failure message for exceptions thrown from the DetectCommunities stage.
- `graphrag-core/src/test/java/io/graphrag/core/usecase/DetectCommunitiesTest.java` -- existing fake `RecordingGraphStore` (line 91). It must keep passing.
- `graphrag-core/CHANGELOG.md`, `graphrag-core/README.md:46` -- document the new port method (additive minor change).

## Tasks & Acceptance

**Execution:**
- `graphrag-core/.../port/GraphStorePort.java` -- add the default `detectCommunities` with connected components -- port boundary (AD-27).
- `graphrag-core/.../usecase/DetectCommunities.java` -- delegate grouping to the port and order deterministically -- single source of grouping.
- `graphrag-core/src/test/.../usecase/DetectCommunitiesTest.java` -- add tests: a port override is honoured (groups and ids come from it, ordered by entity order); the default port gives one Community for two cliques plus a bridge, and a singleton for an isolated entity -- unchanged default behaviour.
- `graphrag-adapter-neo4j/.../Neo4jGraphStoreAdapter.java` -- Leiden override with `finally` drop -- AD-4/AD-20/AD-25.
- `graphrag-adapter-neo4j/src/test/.../Neo4jGraphStoreAdapterTest.java` -- enable GDS and add tests for: two cliques plus a bridge splitting into 2 groups; an isolated entity as a singleton; an edgeless corpus; isolation from another corpus; and no leftover projections afterwards (`gds.graph.list` contains none with the corpusId) -- the integration AC.
- `graphrag-web/src/test/.../SharedNeo4jTestContainer.java` -- enable GDS -- keep the existing ingestion tests green.
- `graphrag-web/.../CorpusController.java` -- add a community-detection-specific failure message -- visible, accurate failure (FR-5).
- `graphrag-core/CHANGELOG.md`, `graphrag-core/README.md` -- document the new port method.

**Acceptance Criteria:**
- Given a Neo4j corpus with two dense groups joined by a single edge, when `DetectCommunities` runs via `Neo4jGraphStoreAdapter`, then two Communities with the correct memberships are persisted, where connected components give one.
- Given the in-memory adapter or a test fake, when `DetectCommunities` runs, then results equal today's connected-components output, and all existing core and web tests pass.
- Given GDS is unavailable, when ingestion runs, then the corpus ends FAILED and the SSE `error` names community detection. There is no fallback.
- Given Leiden throws after projection, when `detectCommunities` returns exceptionally, then no projection for that corpus remains in `gds.graph.list()`.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 22 findings — high 0, medium 4, low 14, false 4, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) A summary LLM failure inside DetectCommunities is reported as "Community detection failed … GDS?" — confirmed: the flag spans the whole run, including `llmPort.summarizeCommunity`; the message now depends on whether the failure is an LLM failure, with a test for a summary failure.
  - `[low]` `[patch]` (blind) Leiden result map filled inside a retryable transaction function duplicates members on retry — the map is now built inside the lambda.
  - `[low]` `[reject]` (blind) A retried `gds.graph.project` after a lost commit fails with "already exists" — needs a commit-ack loss on a single local instance; the fix would add retry and idempotency branches.
  - `[low]` `[patch]` (blind) The stream runs in `executeRead`, which a cluster may route to another member — switched to `executeWrite` (direct correction).
  - `[low]` `[reject]` (blind) Orphaned projections after a JVM kill are never cleaned — rare; the fix adds startup cleanup machinery, and a Neo4j restart clears the catalog.
  - `[low]` `[reject]` (blind) No adapter test against a GDS-less container — needs a second container image setup; the visible-failure path is covered at the controller and by a forced Leiden failure with the drop in `finally`.
  - `[low]` `[patch]` (blind) Tautological assertions in the new controller test — removed; a summary-failure test was added (grouped with the first row).
  - `[false]` `[reject]` (blind) Non-numeric or negative weight breaks `toFloat(coalesce(...))` — `Relationship.weight` is an int clamped to >= 1 (Relationship.java:24); the adapter only ever writes ints.
  - `[low]` `[reject]` (blind) The default connected components is O(n × components) — the old BFS was O(n²) too; it is only used in-memory and offline on small corpora.
  - `[low]` `[reject]` (blind) `concurrency: 1` and the seed trade-off are not documented — cosmetic; the Javadoc states the seed purpose.
  - `[false]` `[reject]` (blind) Count, identities and projection are not one snapshot — detection runs only after BuildKnowledgeGraph finishes for that corpus; nothing writes the corpus concurrently.
  - `[low]` `[reject]` (blind) The GDS requirement is not documented for the adapter — README and docker-compose already state that Neo4j runs with GDS.
  - `[medium]` `[patch]` (edge) Same root cause as the first row — grouped and patched there.
  - `[low]` `[reject]` (edge) `catch (RuntimeException)` misses Errors, so a drop failure can mask an Error — Errors such as OOM during a driver call are fatal anyway; widening to Throwable adds complexity for no realistic case.
  - `[low]` `[patch]` (edge) The "default output unchanged" claim is false: member order is now entity order, not BFS order — the CHANGELOG is reworded to state that ids and member sets are unchanged and member order is entity order.
  - `[medium]` `[patch]` (verification-gap) The extraction-failure test checks the error payload with `any()` — now pinned to `EXTRACTION_FAILURE_MESSAGE`.
  - `[medium]` `[patch]` (verification-gap) No test shows weight affecting Leiden — added a test where weighted and uniform corpora of the same topology group differently.
  - `[low]` `[patch]` (verification-gap other) Changelog ordering claim — same root cause as the edge-case row; patched there.
  - `[low]` `[reject]` (intent) No end-to-end controller test asserting a Leiden split — controller and UI ingestion tests now run real Leiden on the GDS-enabled shared container; the split itself is asserted at the adapter, per the AC.
  - `[false]` `[reject]` (intent) The port returns groups, not "memberships" — DetectCommunities builds `CommunityMembership` from the groups; the behaviour at the use-case surface is identical and core numbering stays in one place.
  - `[false]` `[reject]` (intent) The projection name has a UUID suffix beyond the corpusId — AD-27/AC require the name to include the corpusId, which it does; the suffix prevents concurrent collisions.
  - `[low]` `[reject]` (intent) Edgeless or empty corpora succeed without GDS — no silent fallback happens: there is nothing to cluster, and singletons are what Leiden would return.

## Design Notes

Projection sketch (GDS 2.x aggregation form):
```cypher
MATCH (s:Entity {corpusId: $corpusId})
OPTIONAL MATCH (s)-[r:RELATIONSHIP {corpusId: $corpusId}]->(t:Entity {corpusId: $corpusId})
WITH gds.graph.project($graphName, s, t,
       {relationshipProperties: {weight: toFloat(coalesce(r.weight, 1))}},
       {undirectedRelationshipTypes: ['*']}) AS g
RETURN g.nodeCount
```
Then `CALL gds.leiden.stream($graphName, {relationshipWeightProperty: 'weight', randomSeed: 42, concurrency: 1}) YIELD nodeId, communityId`. Group by `communityId` and map to `normalizedIdentity`. Leiden's community ids are arbitrary; core reorders the groups.

## Verification

**Commands:**
- `mvn -B -q -pl graphrag-core test` -- expected: all pass
- `mvn -B -q -pl graphrag-adapter-neo4j -am test -Dtest=Neo4jGraphStoreAdapterTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: all pass, including Leiden tests (Docker required)
- `mvn -B clean install` -- expected: BUILD SUCCESS

## Auto Run Result

Status: done

**Summary:** Communities now come from GDS Leiden on the Neo4j path. The grouping goes through a new additive `GraphStorePort.detectCommunities(corpusId)`, whose default is the previous connected-components grouping, so the in-memory adapter and offline mode are unchanged. `DetectCommunities` numbers groups deterministically by entity order. A failure in the grouping stage fails ingestion visibly with a community-detection message; an LLM failure while summarizing keeps the LLM message.

**Files changed:**
- `graphrag-core/.../port/GraphStorePort.java` -- new default `detectCommunities` (connected components).
- `graphrag-core/.../usecase/DetectCommunities.java` -- delegates grouping to the port and orders groups and members by entity order.
- `graphrag-adapter-neo4j/.../Neo4jGraphStoreAdapter.java` -- Leiden override: a corpus-scoped undirected weighted projection, fixed seed with concurrency 1, and a `finally` drop.
- `graphrag-web/.../CorpusController.java` -- community-detection failure message, distinct from LLM failures.
- Tests: `DetectCommunitiesTest` (+2), `Neo4jGraphStoreAdapterTest` (+9, GDS-enabled container), `CorpusControllerTest` (+2, extraction message pinned), `SharedNeo4jTestContainer` (GDS enabled).
- `graphrag-core/CHANGELOG.md`, `graphrag-core/README.md` -- document the new port method and the ordering change.

**Review findings:** 22 findings in total. 9 rows were patched, covering 5 distinct fixes:
- the summary-failure message;
- the retry-safe Leiden map;
- the stream moved to `executeWrite`;
- the weight test;
- the extraction-message pin, plus the CHANGELOG ordering wording and the removed tautological assertions.

0 deferred. 13 rejected; the reasons are in the Review Triage Log.

**Follow-up review recommendation:** true, because 3 medium entries were patched (0 high, 3 medium, plus low patches). Named unverified risks:
- The message split recognizes only `OpenAiLlmPort.LlmCallFailedException` as an LLM failure.
- The real "GDS not installed" path is not exercised against a GDS-less container.

**Verification:**
- `mvn -pl graphrag-core,graphrag-adapter-neo4j,graphrag-adapter-langchain4j test`: BUILD SUCCESS. `Neo4jGraphStoreAdapterTest` passed 23/23 against Neo4j 2026.08.1 with GDS.
- `CorpusControllerTest`: 43/43.
- A full `mvn clean install` before the patches passed everything except 5 Playwright UI tests: `CanvasSettingsPopoverUiTest` (1), `EntityTypeColorToggleUiTest` (2), `MainScreenDetailPanelUiTest` (1) and `ReplayCommunityHullVisibilityUiTest` (1). The same 5 fail on the unchanged baseline locally. CI on main is green.
- Docker 29 requires `-Dapi.version=1.44` locally.

**Residual risks:**
- Test containers download the GDS plugin at startup, which needs network access and makes startup slower.
- Leiden may group the demo corpus differently, so UI tests that assume specific demo community layouts could shift in CI.
