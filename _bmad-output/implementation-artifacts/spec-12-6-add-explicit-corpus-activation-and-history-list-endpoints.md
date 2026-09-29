---
title: 'Add Explicit Corpus Activation and History List Endpoints'
type: 'feature'
created: '2026-09-29'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '984566abfbe81d31038da36489e4415d6956c906'
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `Neo4jCorpusRegistry` can persist and read back one corpus at a time, but nothing lists every retained corpus or records which one was most recently worked on — the two pieces Story 12.7's switcher UI needs.

**Approach:** Add `GET /api/corpora` (every retained corpus, ordered by `lastActivatedAt` descending) and `POST /api/corpora/{corpusId}/activate` (the only thing that updates `lastActivatedAt`) to `CorpusController`, backed by two new `Neo4jCorpusRegistry` methods.

## Boundaries & Constraints

**Always:** `GET /api/corpora` returns `{"corpora": [{"id", "name", "status", "createdAt", "lastActivatedAt"}, ...]}`, ordered by `lastActivatedAt` descending via Cypher `ORDER BY`, matching `CorpusController`'s existing `Map.of`-based response convention. `POST /api/corpora/{corpusId}/activate` updates only `lastActivatedAt` (via `graphrag-web`'s own `Instant.now()`, never Neo4j's `datetime()`, per AD-22) and 404s (via the existing `IllegalArgumentException` → `handleIllegalArgumentException` pattern already used by `query()`) for an unknown `corpusId`. No server-side "current active corpus" singleton — ordering/auto-restore stays pure frontend logic over this endpoint's response (Story 12.7's concern, not this one's).

**Never:** Query/vector-space/progress endpoints must not be touched to also update `lastActivatedAt` — activation is exclusively this new endpoint. Demo/offline corpora (`markOffline`) must not appear in `GET /api/corpora` — `list()` filters by the existing in-process `isOffline(id)` check. No corpus-deletion or retention/pruning capability (explicit non-goal, carried from `spec-neo4j-corpus-persistence`). No change to `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter`/`InterruptedCorpusReconciler`/the existing 409-on-`BUILDING`/`FAILED` query gate.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| List multiple corpora | 3 corpora with different `lastActivatedAt` values | `GET /api/corpora` returns all 3, ordered most-recently-activated first | N/A |
| Activate updates ordering | Corpus A activated, then corpus B activated | A subsequent `GET /api/corpora` lists B before A | N/A |
| Activate unknown corpus | `POST /api/corpora/nonexistent/activate` | `404` with `{"error": "No corpus was found for id nonexistent"}`, matching `query()`'s existing pattern | N/A |
| Offline/demo corpus excluded | An offline corpus exists (`isOffline` true) | It does not appear in `GET /api/corpora`'s response | N/A |
| Empty registry | No corpora yet | `GET /api/corpora` returns `{"corpora": []}`, not an error | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jCorpusRegistry.java` — add `list()` (new `MATCH (c:CorpusMeta) RETURN ... ORDER BY c.lastActivatedAt DESC` read, filtered post-query by `!isOffline(id)`) returning a new small local record (e.g. `CorpusSummary(String corpusId, String name, CorpusWorkflowStatus status, String createdAt, String lastActivatedAt)`), and `activate(String corpusId)` (mirrors `updateStatus`'s existing per-id `MATCH ... SET` shape, but sets `lastActivatedAt` to a fresh `Instant.now().toString()` instead of `status`). Neither of `put()`'s existing timestamp-writing (lines ~77-95) nor `get()` (116-138, only reads `name`/`documentNames` today) need changing.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` — new `@GetMapping("/api/corpora")` and `@PostMapping("/api/corpora/{corpusId}/activate")`, following the file's established conventions: `@PathVariable("corpusId")` naming, `ResponseEntity<Map<String,Object>>` responses via `Map.of(...)`/a small private payload helper (matching `corpusPayload`/`stepPayload`'s existing style), and the existing `IllegalArgumentException`-throwing 404 pattern already used by `query()` (line ~204-205) — no new `@ExceptionHandler` needed.
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` — existing `@SpringBootTest`/`@AutoConfigureMockMvc`/`@Autowired Neo4jCorpusRegistry corpusRegistry` setup and `mockMvc.perform(get/post(...))...andExpect(jsonPath(...))` style to follow; `SharedNeo4jTestContainer.driver()` already available here for any direct-Cypher double-checks.

## Tasks & Acceptance

**Execution:**
- [x] `Neo4jCorpusRegistry.java` -- add `list()`: read all `CorpusMeta` nodes ordered by `lastActivatedAt` descending, excluding offline ids, returning a small new `CorpusSummary` record per corpus -- the durable history data source
- [x] `Neo4jCorpusRegistry.java` -- add `activate(String corpusId)`: sets `lastActivatedAt` to a fresh timestamp for that one corpus, no-ops silently if the id doesn't exist (existence/404 is the controller's job, matching the existing `get()`-then-`orElseThrow` pattern) -- the only path that ever updates `lastActivatedAt` after creation
- [x] `CorpusController.java` -- add `GET /api/corpora` mapping `list()`'s results into the `Map.of`-based response shape
- [x] `CorpusController.java` -- add `POST /api/corpora/{corpusId}/activate`, 404-ing via `IllegalArgumentException` for an unknown id (reusing the existing exception handler), otherwise calling `activate(corpusId)`
- [x] Tests: list ordering after multiple activations, unknown-corpus 404, offline-corpus exclusion, empty-registry response shape

**Acceptance Criteria:**
- Given corpora A and B both exist, when B is activated after A, then `GET /api/corpora` lists B first
- Given an offline/demo corpus exists alongside regular corpora, when `GET /api/corpora` is called, then the offline one is absent from the response

## Implementation Notes

- All 5 tasks complete. `CorpusSummary` record, `list()` (bulk read + in-process offline filter), and `activate()` (per-id timestamp update) added to `Neo4jCorpusRegistry`; `GET /api/corpora`/`POST /api/corpora/{corpusId}/activate` added to `CorpusController`, following existing conventions exactly (`Map.of` responses, the established `IllegalArgumentException`→404 pattern, no new exception handler).
- `activate()`'s success response shape (`{"id", "activated": true}`) wasn't prescribed by the spec (only the error shape was) — flagged for Story 12.7 to confirm it's what the frontend switcher needs.
- Verified: full `graphrag-web` suite (139 tests, up from 135 — 4 new endpoint tests) passes cleanly on an independent rerun with real Docker access (not just the implementing subagent's compile-only check, which couldn't reach Docker in its own sandbox).
- Same residual risk as prior stories: the adapter module's new `list()`/`activate()` Testcontainers tests are unconfirmed to execute in this dev environment (tracked in `deferred-work.md`); logic reviewed by hand against the same Cypher patterns already verified elsewhere in the class.

**Real bug found and fixed during my own re-verification of the review patch (not by the reviewers, not by the implementing subagent):** the patch for the timestamp-ordering finding passed a raw `java.time.Instant` as a Cypher parameter. Recompiling and running the full suite (rather than trusting the subagent's compile-only check) surfaced `org.neo4j.driver.exceptions.ClientException: Unable to convert java.time.Instant to Neo4j Value` across every test that persists a corpus — the Neo4j Java driver has no direct parameter mapping for `Instant` (only `ZonedDateTime`/`OffsetDateTime`/etc.). Fixed by converting to `Instant.now().atZone(ZoneOffset.UTC)` before passing it in `put()`/`activate()`; `list()`'s existing `.asZonedDateTime()` read-back needed no further change. Re-verified: full `graphrag-web` suite (140 tests) passes cleanly.

## Spec Change Log

## Review Triage Log

- **patch** (high) — `createdAt`/`lastActivatedAt` are stored as `Instant.now().toString()`, and `list()`'s `ORDER BY c.lastActivatedAt DESC` is therefore a plain lexicographic string sort. `Instant#toString()` prints a *variable-width* fractional-second group (0, 3, 6, or 9 digits, whichever is shortest that's exact) — two timestamps with different digit-group widths can compare incorrectly as strings (e.g. a 3-digit-ms instant can sort after a later 9-digit-ns instant, since `'0' < 'Z'` lexicographically). Verified independently by Blind Hunter and Edge Case Hunter — this defeats the entire ordering guarantee this story exists to deliver. Fix: pass `Instant` objects directly as Cypher parameters (the Neo4j Java driver maps `java.time.Instant` to its native `DATETIME` type automatically) instead of `.toString()`, in both `put()` (Story 12.4, retroactively) and the new `activate()`; read back via `.asInstant()` in `list()` so `ORDER BY` sorts on Neo4j's native temporal type, unambiguous regardless of print width.
- **patch** (low) — The two new ordering tests (`listOrdersMostRecentlyActivatedFirst`, `activatingAnEarlierCorpusMovesItBackToTheFront`) call `activate()` back-to-back with no delay, unlike the sibling `activateSetsLastActivatedAtToATimestampAfterCreation` test, which already added a small sleep to guarantee distinct timestamps. Add the same guard so these tests can't tie and become flaky.
- **patch** (low) — The spec's own I/O Matrix calls for "3 corpora with different `lastActivatedAt` values"; the delivered tests only exercise 2. Extend to 3, matching the frozen matrix literally.
- **patch** (medium) — No test asserts the full `GET /api/corpora` payload shape — every existing test on that endpoint reads only `$.corpora[*].id`. Verified by Verification Gap Reviewer: a field-name typo or wrong-accessor regression in `corpusSummaryPayload` (e.g. `name`/`status` swapped) would pass every current test. Add `jsonPath` assertions for `name`/`status`/`createdAt`/`lastActivatedAt` too.
- **patch** (low) — Activating an offline/demo corpus is untested; its effect (a harmless write to a `lastActivatedAt` nobody reads via `list()` while offline) is intentional but undocumented by any test. Add one asserting `POST .../activate` still returns 200 for an offline corpus id.
- **false** — Edge Case Hunter flagged `list()`'s offline-exclusion relying solely on the in-process `isOffline` set (not a persisted Neo4j flag) as a new finding. This is the exact, already-documented limitation in this spec's own Design Notes section, not something newly discovered.
- **low, rejected** — `CorpusWorkflowStatus.valueOf(...)` in `list()`'s row-mapping could throw `IllegalArgumentException` on an unrecognized persisted status string. Unreachable via any current writer (`put()`/`updateStatus()` only ever write valid enum names) — same disposition as the identical finding already rejected in Story 12.4's review.

## Design Notes

Known limitation, not fixed by this story: `list()`'s offline-corpus filter relies on the in-process `isOffline` set, which (correctly, per this epic's design) does not survive a restart. If an offline corpus's `CorpusMeta` node was written before a restart (today's `useOfflineDemoDataset()` already calls `put()` for it, same as any other corpus — a pre-existing characteristic from Story 12.4, not introduced here), it would reappear in `GET /api/corpora` after that restart, since the flag that would have hidden it is gone too. Fixing this properly means either persisting the offline flag in Neo4j (reversing this epic's own "offline stays in-process only" decision) or adding a corpus-deletion/pruning capability (an explicit non-goal) — out of this story's scope; worth a follow-up if it proves disruptive in practice.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-adapter-neo4j -am test` -- expected: new `list()`/`activate()` registry tests pass (or manually verified per the already-tracked Docker-sandboxing caveat in `deferred-work.md`)
- `mvn -q -B -pl graphrag-web -am test` -- expected: new endpoint tests pass; full existing suite unaffected
- `mvn -q -B clean install` -- expected: full reactor green
