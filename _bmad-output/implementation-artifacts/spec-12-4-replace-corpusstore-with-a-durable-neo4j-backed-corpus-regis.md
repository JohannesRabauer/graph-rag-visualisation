---
title: 'Replace CorpusStore with a Durable Neo4j-Backed Corpus Registry'
type: 'feature'
created: '2026-09-29'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'ad1a859a949952fe1a43d1bfd4d5f61106a7d989'
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `CorpusStore` (`graphrag-web`) holds corpus bookkeeping — name, workflow status, offline flag — in a `ConcurrentHashMap`, so it's lost on every restart even though the graph/vector data behind it (Stories 12.1-12.3) now survives.

**Approach:** Delete `CorpusStore` outright and replace it with `Neo4jCorpusRegistry` (`graphrag-adapter-neo4j`) — a plain class, not a `graphrag-core` port (AD-19) — persisting `CorpusMeta` nodes keyed on `corpusId` alone, mirroring `Neo4jGraphStoreAdapter`'s constructor/`ensureConstraint` shape. `CorpusWorkflowStatus` relocates alongside it. Demo/offline corpora stay a plain in-process field on the new class, never persisted, exactly as `CorpusStore` held them.

## Boundaries & Constraints

**Always:** `CorpusMeta` node stores `{corpusId, name, documentNames, status, createdAt, lastActivatedAt}`, uniqueness-constrained on `corpusId` alone. `createdAt`/`lastActivatedAt` are both written from `graphrag-web`'s own `Instant.now()` as a Cypher parameter, never Neo4j's `datetime()` (AD-22); `lastActivatedAt` is initialized equal to `createdAt` at creation (this story only creates that initial value — the `/activate` endpoint that updates it later is Story 12.6). Raw document bytes are never persisted — only `corpus.documentNames()` (filenames), never `corpus.documents()`. `Neo4jCorpusRegistry`'s constructor takes a `Driver`, mirroring `Neo4jGraphStoreAdapter`. Wired into `ParserConfig` as a new `Driver`-parameterized `@Bean`, exactly like `graphStorePort(Driver)`/`vectorStorePort(Driver)`.

**Never:** No new `graphrag-core` port — `Neo4jCorpusRegistry` is called directly by `graphrag-web`, same as any other adapter-side Spring bean. No change to `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter`/`ParserConfig`'s existing beans beyond adding the new one. No change to `AnswerLocalSearch`/`AnswerGlobalSearch`/`AnswerDriftSearch`/`AnswerVectorBaseline`/`DetectCommunities`/`ExtractEntitiesAndRelationships` — none of them touch corpus bookkeeping. `size()` is dropped, not carried over — it has no live caller anywhere in `CorpusController`, and nothing here needs it (don't build unused surface).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create then read back | `put(corpus)` then `get(corpusId)` | Returns a `Corpus` with the original `id`/`name`/`documentNames()` (empty-content placeholders — raw bytes were never stored), `status()` is `BUILDING` | N/A |
| Status transitions | `markReady(id)` / `markFailed(id)` after `put` | `status(id)` reflects the transition; `createdAt` unchanged, `lastActivatedAt` unchanged (this story doesn't touch it after creation) | N/A |
| Unknown corpus | `get("nonexistent")` | `Optional.empty()`; `status("nonexistent")` defaults to `BUILDING` (matches `CorpusStore`'s existing default) | N/A |
| Restart survives | `put`+`markReady` via one registry instance; a fresh instance against the same Neo4j | `get(id)`/`status(id)` return the same data | N/A |
| Offline corpora never persist | `markOffline(id)` then a fresh registry instance (simulated restart) | `isOffline(id)` is `false` on the fresh instance — offline flag is in-process only, lost on restart, exactly as before | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-web/src/main/java/com/graphraglens/web/CorpusStore.java` — delete entirely; its full API (`put`/`markOffline`/`isOffline`/`get`/`status`/`markReady`/`markFailed`, plus the nested `CorpusWorkflowStatus` enum) is the contract `Neo4jCorpusRegistry` must satisfy (minus `size()`, dropped per Boundaries).
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jGraphStoreAdapter.java:51-87` — the exact shape to mirror: constructor(Driver) + `Objects.requireNonNull`, private `ensureConstraint(String)` catching `Neo4jException` and logging (not fatal), `driver.session()` + `executeWrite`/`executeRead`.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` — field/constructor (lines ~76,87,93), every call site (lines ~139,151,174-175,203-212,367,371), and the qualified `CorpusStore.CorpusWorkflowStatus` references (lines ~208-212) all move to the new type/package.
- `graphrag-core/src/main/java/io/graphrag/core/domain/Corpus.java:9,22,26` — `documentNames()`/`documentCount()` are what `Neo4jCorpusRegistry` reads to populate `CorpusMeta`; its stale Javadoc mentioning `CorpusStore` needs a one-line update. Do not modify the record itself.
- `graphrag-web/src/main/java/com/graphraglens/web/RetrievalTraceStore.java:11` — stale Javadoc mentioning `CorpusStore` by name; update the reference only, `RetrievalTraceStore` itself is functionally untouched (stays in-memory, per this epic's non-goals).
- `graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java:88-91,105-108` — the `driver(Driver)`-parameterized `@Bean` pattern to copy for a new `neo4jCorpusRegistry(Driver driver)` bean.
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusStoreTest.java` — repurpose into a test of the new registry's in-process offline-set behavior (the one piece that stays non-Neo4j).
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java:71,185,212,235,435-659` (and the same `new CorpusStore()`-in-constructor pattern in `CorpusControllerGlobalSearchTest.java`, `CorpusControllerDriftSearchTest.java`, `CorpusControllerVectorBaselineTest.java`) — every manual `new CorpusController(...)` call passes `new CorpusStore()`; these are plain, no-Spring-context tests (unlike `CorpusControllerTest`'s own `@Autowired` case), so they need a real `Neo4jCorpusRegistry(Driver)` constructed directly against `SharedNeo4jTestContainer`'s shared singleton — not a new interface/fake, and not pulling these tests into a full Spring context they were deliberately built to avoid.
- `graphrag-web/src/test/java/com/graphraglens/web/SharedNeo4jTestContainer.java` — add a small static `Driver driver()` accessor (or expose the existing one) so the plain non-Spring tests above can construct a real `Neo4jCorpusRegistry` without a Spring context.

## Tasks & Acceptance

**Execution:**
- [x] `graphrag-adapter-neo4j/.../Neo4jCorpusRegistry.java` (new) + relocated `CorpusWorkflowStatus` enum -- constructor(Driver), idempotent `corpusId`-only uniqueness constraint, `put`/`markOffline`/`isOffline`/`get`/`status`/`markReady`/`markFailed` as real Cypher MERGE/MATCH, offline-id set as a plain in-process field -- the durable replacement
- [x] `SharedNeo4jTestContainer.java` -- expose the shared `Driver` for direct (non-Spring) test construction
- [x] `ParserConfig.java` -- add `neo4jCorpusRegistry(Driver)` `@Bean` -- wires it into the running app
- [x] `CorpusController.java` -- swap `CorpusStore` field/constructor/every call site for `Neo4jCorpusRegistry`/relocated `CorpusWorkflowStatus` -- the actual cutover
- [x] Delete `CorpusStore.java`; repurpose `CorpusStoreTest.java` into `Neo4jCorpusRegistryOfflineBehaviorTest.java` -- no dead code left behind
- [x] Update the 4 test files' manual `new CorpusController(...)` call sites to construct `Neo4jCorpusRegistry` against the shared container's `Driver` instead of `new CorpusStore()` -- keeps them Spring-context-free as originally designed
- [x] Fix the two stale `CorpusStore`-mentioning Javadoc comments (`Corpus.java`, `RetrievalTraceStore.java`)

**Acceptance Criteria:**
- Given a corpus is `put` then `markReady`, when the app restarts (fresh `Neo4jCorpusRegistry` instance against the same Neo4j), then `get`/`status` return the same name/status
- Given `CorpusController`'s existing behavior (409 on `BUILDING`/`FAILED`, offline-query blocking), when this story lands, then that behavior is unchanged — only where status/offline-flag is read from and stored moves

## Implementation Notes

- All 7 tasks complete. `Neo4jCorpusRegistry` mirrors `Neo4jGraphStoreAdapter`'s shape exactly; `CorpusController` and all 4 previously-Spring-free test classes (`CorpusController{,GlobalSearch,DriftSearch,VectorBaseline}Test`) now construct it directly against `SharedNeo4jTestContainer.driver()`, staying Spring-context-free as designed. New `Neo4jCorpusRegistryOfflineBehaviorTest` replaces `CorpusStoreTest`; new `Neo4jCorpusRegistryTest` (Testcontainers) covers every I/O Matrix scenario against real Neo4j.
- Verified: `graphrag-web`'s full suite (133 tests) passes cleanly, confirmed independently (not just on the implementing subagent's word) by rerunning both the 6 individually-flagged-as-"pre-existing-flaky" UI tests in isolation (all pass) and the full suite again from a clean install (133/133 pass, 0 failures/errors).
- Same residual risk as Stories 12.1/12.2: `Neo4jCorpusRegistryTest`'s Testcontainers execution is unconfirmed in this dev environment (the same Docker-sandboxing pipe restriction, already tracked in `deferred-work.md`); logic manually verified against the project's real running Neo4j container.
- `size()` deliberately dropped from the new registry (no live caller existed) per the spec's own "Never" boundary.

## Spec Change Log

## Review Triage Log

- **patch** (medium) — `README.md`'s existing caveat ("restarting the app container clears every Corpus's workflow state... both live only in the app process's own memory (`CorpusStore`/`RetrievalTraceStore`)") is now false for corpus bookkeeping — `Neo4jCorpusRegistry` persists it and survives a restart. Found independently by Blind Hunter and Verification Gap Reviewer (marked high-confidence). Correct the caveat: only `RetrievalTraceStore` still matches it.
- **patch** (low) — No test exercises calling `put()` twice for the same `corpusId` — exactly the scenario the new `corpusId`-uniqueness constraint exists to make safe. Add one proving `MERGE` doesn't duplicate the `CorpusMeta` node.
- **patch** (low) — Removing `CorpusStore.size()` also removed the only regression check that a rejected upload (unsupported file type) registers no corpus. Verified real: `CorpusController.upload()` currently validates before `put()` is reached, so nothing is broken today, but a future reordering could silently regress with no test catching it. Add a direct Cypher count query via the shared test driver (`MATCH (c:CorpusMeta) RETURN count(c)`) before/after the rejected request.
- **patch** (low) — Local variable names (`corpusStore`, `isolatedCorpusStore`) in the four migrated test classes weren't renamed despite the type change to `Neo4jCorpusRegistry` — cosmetic naming drift in an otherwise-thorough rename.
- **defer** — `SharedNeo4jTestContainer`'s `withReuse(true)` (Story 12.3) means `CorpusMeta` nodes now accumulate durably across test runs with no cleanup, and some test classes reuse literal ids (e.g. `"corpus-1"`) across files. Not a defect in this story (133/133 tests pass cleanly), but a real risk for Story 12.6/12.7's corpus-history-listing endpoint, which will read every `CorpusMeta` node in the shared container. Logged to `deferred-work.md` for that story's implementer to consider.
- **false** — Blind Hunter flagged `put()` unconditionally resetting `status` to `BUILDING` on any re-invocation, with no guard against overwriting an existing `READY`/`FAILED` corpus. Verified false as a *regression*: the original `CorpusStore.put()` did exactly the same unconditional reset (`statuses.put(corpus.id(), BUILDING)`) — this story faithfully mirrors pre-existing behavior, not a new defect.
- **low, rejected** — `status()` throws an uncaught `IllegalArgumentException` if the persisted status string isn't a valid enum value. Unreachable via any current writer — `put()`/`updateStatus()` only ever write `CorpusWorkflowStatus.name()` values; only manual Cypher tampering could produce a bad value.
- **low, rejected** — `ensureConstraint()`'s non-fatal `catch (Neo4jException)` could theoretically let a `put()` race create duplicate `CorpusMeta` nodes if constraint creation failed. Same accepted trade-off already established for `Neo4jGraphStoreAdapter` in Story 12.1 (log-and-continue-unconstrained is deliberate), not new to this story.
- **low, rejected** — `ensureConstraint()` re-running on every `Neo4jCorpusRegistry` construction across many test classes is a theoretical schema-lock contention risk. Matches the identical, already-accepted pattern from `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter` since Story 12.1; the full 133-test suite passes cleanly and repeatably with no observed contention.
- **low, rejected** — `Neo4jCorpusRegistryOfflineBehaviorTest` pays a live-Neo4j constructor cost to test pure in-process offline-flag logic. Real minor inefficiency, not worth restructuring for a passing, fast test class.
- **low, rejected** — `get()`'s empty-content `UploadedDocument` placeholders have no runtime guard against a hypothetical future caller reading `.content()`. Matches the spec's own Design Notes — explicit, reasoned intent (nothing downstream reads document content), not an oversight.

## Design Notes

`get(corpusId)` reconstructs a `Corpus` from `CorpusMeta`'s stored `name`/`documentNames` using empty-content `UploadedDocument` placeholders (`new UploadedDocument(filename, "")` per stored name) — raw document text was never persisted (non-goal) and nothing downstream of a registry lookup reads document content; every caller only uses `corpus.id()` after retrieval.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-adapter-neo4j -am test` -- expected: new `Neo4jCorpusRegistryTest` passes (or is manually verified per the Story 12.1/12.2 Docker-sandboxing caveat already tracked in `deferred-work.md`)
- `mvn -q -B -pl graphrag-web -am test` -- expected: `CorpusController*Test` suite and the full UI suite pass against the shared Testcontainers Neo4j
- `mvn -q -B clean install` -- expected: full reactor green
