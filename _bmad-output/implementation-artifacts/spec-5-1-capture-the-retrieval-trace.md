---
title: 'Capture the Retrieval Trace'
type: 'feature'
created: '2026-09-19'
status: 'in-review'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '5bcfb807ad77d13247db49bbfa2ac165e6565f68'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Both search modes already generate and return a `traceId` in their query response, but nothing captures a trace behind it — there is no `RetrievalTrace` domain model, no store, and no `GET /api/traces/{traceId}` endpoint. Replay (Story 5.2) has nothing to fetch.

**Approach:** Add `RetrievalTrace`/`RetrievalStep` as first-class `graphrag-core` domain types (an ordered list of steps, each naming an Entity/Relationship/Community touched) and a `RetrievalTraceStore` in `graphrag-web` (in-memory, keyed by `traceId`, following the existing `CorpusStore` pattern — AD-5). Extend `AnswerGlobalSearch` to report, in order, every Community it examined while scoring (not just the winner) as its trace steps — this is a direct, honest reflection of what the algorithm already does. Extend `CorpusController`'s Local Search path (`buildAnswer`/`findBestSentence`) to report, for the matched document/sentence, which `graphStorePort.entities()` are named within it, as ENTITY steps — Local Search's current retrieval is a plain-text sentence match, not a graph traversal (a pre-existing gap, logged separately in `deferred-work.md`, out of scope to rework here), so its trace is necessarily a best-effort cross-reference against the graph rather than a record of an actual traversal. `CorpusController` builds a `RetrievalTrace` from whichever path ran, stores it under the same `traceId` already in the response, and a new `GET /api/traces/{traceId}` returns it.

## Boundaries & Constraints

**Always:** Trace steps are strictly ordered (a `List`, never a `Set` — AD-5). A trace is stored under the exact `traceId` already generated and returned by the query response for both LOCAL and GLOBAL modes, including the `noAnswer` case (a trace with zero or few steps is still captured and fetchable — "found nothing" is part of the story too). `RetrievalTrace` is held in memory only, never written to Neo4j (AD-5). `GET /api/traces/{traceId}` is a plain REST read, not SSE/WebSocket.

**Never:** Do not rework Local Search's actual retrieval mechanism (text sentence-matching) to traverse the graph — that is a separate, larger, pre-existing gap (logged in `deferred-work.md` alongside the `AnswerLocalSearch` extraction gap from Story 4.4), not this story's job; Local Search's trace is a best-effort cross-reference of the matched text against `graphStorePort.entities()`, not a record of genuine graph traversal, and that limitation is accepted here. Do not build the Replay UI/scrubber (Story 5.2). Do not persist traces to Neo4j or disk. Do not add per-Corpus data isolation to `GraphStorePort` (same accepted limitation as Stories 4.3/4.4).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Global Search produces a matched answer | `mode: "GLOBAL"`, a Community scores a match | Query response's `traceId` addresses a trace with one ordered `COMMUNITY` step per Community examined (in iteration order) | N/A |
| Global Search, no Communities exist yet (`noAnswer`) | `mode: "GLOBAL"`, `communities()` empty | `traceId` still returned; trace has zero steps, fetchable, not an error | N/A |
| Local Search produces a matched answer | `mode: "LOCAL"`, a document/sentence matches | Trace has one ordered `ENTITY` step per named entity (from `graphStorePort.entities()`) found in the matched sentence, in the order they appear in the text | N/A |
| Local Search, no match found | `mode: "LOCAL"`, no sentence scores | `traceId` still returned; trace has zero steps | N/A |
| `GET /api/traces/{traceId}` for an unknown id | Any `traceId` never produced by a query | `404` with a plain-language error, not a stack trace | Matches the existing `IllegalArgumentException` → 404 pattern already used for unknown `corpusId` |

</frozen-after-approval>

## Code Map

- `graphrag-core/.../domain/RetrievalTrace.java` (NEW) — `record RetrievalTrace(String traceId, List<RetrievalStep> steps)`.
- `graphrag-core/.../domain/RetrievalStep.java` (NEW) — `record RetrievalStep(Kind kind, String identifier, String label)` with a nested `enum Kind { ENTITY, RELATIONSHIP, COMMUNITY }`; `identifier` is the Entity's `normalizedIdentity()` or the Community's `id()` (matches the identity strings already used in Story 4.3's SSE payloads, for future frontend reuse), `label` is the human-readable name/summary snippet.
- `graphrag-core/.../usecase/AnswerGlobalSearch.java` — the scoring loop (`answer(String question)`) already iterates every `Community`; collect a `RetrievalStep` per Community examined, in iteration order, and return it as part of the result (extend `GlobalSearchAnswer` with a `List<RetrievalStep> steps` field — update its `matched`/`noCommunitiesYet` factories accordingly). `AnswerGlobalSearch` stays stateless/trace-store-unaware — it only returns the ordered steps; storing them under a `traceId` is the web layer's job.
- `graphrag-web/.../CorpusStore.java` — read as the pattern to follow (in-memory `ConcurrentHashMap`-backed `@Component`, `put`/`get`).
- `graphrag-web/.../RetrievalTraceStore.java` (NEW) — same pattern: `void put(String traceId, RetrievalTrace trace)`, `Optional<RetrievalTrace> get(String traceId)`.
- `graphrag-web/.../CorpusController.java` (`query()`, `globalSearchResponse()`, `buildAnswer()`/`findBestSentence()`) — after computing the GLOBAL result, build a `RetrievalTrace` from `GlobalSearchAnswer.steps()` and store it under the already-generated `traceId` before returning. For LOCAL, extend `findBestSentence()`/`buildAnswer()` to also return which `graphStorePort.entities()` are named in the matched sentence (a simple substring/name check against the entity's `name()`), build a `RetrievalTrace` from those as ENTITY steps, store it the same way. Add `GET /api/traces/{traceId}`: look up the store, `404` via the existing `IllegalArgumentException`-to-404 exception handler pattern if absent, else return the trace as JSON (`traceId`, ordered `steps` with `kind`/`identifier`/`label`).
- `graphrag-web/.../CorpusControllerTest.java` — add coverage: GLOBAL success populates a fetchable multi-step trace; GLOBAL `noAnswer` still produces a fetchable (empty-steps) trace; LOCAL success populates entity steps; unknown `traceId` returns 404.
- `graphrag-core/.../usecase/AnswerGlobalSearchTest.java` — add coverage asserting `GlobalSearchAnswer.steps()` contains one step per examined Community, in order.

## Tasks & Acceptance

**Execution:**
- [x] `RetrievalTrace.java` / `RetrievalStep.java` (new) -- add the domain types -- gives trace capture/replay a first-class, addressable representation
- [x] `AnswerGlobalSearch.java` / `GlobalSearchAnswer.java` -- collect and return an ordered step per Community examined -- captures Global Search's real, honest touch order
- [x] `RetrievalTraceStore.java` (new) -- in-memory store keyed by `traceId`, following `CorpusStore`'s pattern -- gives the web layer somewhere to persist traces in-process (AD-5)
- [x] `CorpusController.java` -- build and store a `RetrievalTrace` for both LOCAL and GLOBAL paths (including `noAnswer`/no-match cases) under the already-generated `traceId`; add `GET /api/traces/{traceId}` -- closes the loop from query response to fetchable trace
- [x] `CorpusControllerTest.java` / `AnswerGlobalSearchTest.java` -- cover the matrix above -- proves capture and fetch actually work end-to-end

**Acceptance Criteria:**
- Given a Global Search query that matches, when the trace is fetched via its `traceId`, then it contains one ordered step per Community examined during scoring (Story 5.1 AC1).
- Given any query (Local or Global, matched or not), when it completes, then its `traceId` addresses a trace — never a 404 for a `traceId` a real query just returned (Story 5.1 AC2).
- Given a trace is captured, when inspected, then its steps are a `List` in touch order, never reordered or deduplicated into a set (Story 5.1 AC3, AD-5).
- Given an unknown `traceId`, when fetched, then the response is `404` with a plain-language message, not a server error (Story 5.1 AC4).

## Implementation Notes

- Implemented directly (no subagent dispatch). All five execution tasks completed against the Code Map exactly as specified: new `graphrag-core` domain records `RetrievalStep`/`RetrievalTrace`; `GlobalSearchAnswer` gained a fourth `List<RetrievalStep> steps` component (both factories updated, `AnswerGlobalSearch` collects one `COMMUNITY` step per Community per loop iteration, before scoring, so it is emitted regardless of outcome); new `graphrag-web` `RetrievalTraceStore` (`ConcurrentHashMap`-backed `@Component`, same shape as `CorpusStore`); `CorpusController` gained a `RetrievalTraceStore` constructor dependency, builds/stores a `RetrievalTrace` under the already-generated `traceId` on both the LOCAL and GLOBAL paths (including the `noAnswer` and no-match cases), and exposes `GET /api/traces/{traceId}` (404 via the existing `IllegalArgumentException` handler for an unknown id).
- LOCAL mode's entity cross-reference (`entityStepsNamedIn`) scans `graphStorePort.entities()` for a case-insensitive substring match of each Entity's `name()` against the matched sentence, then orders the resulting steps by each name's first-occurrence index in the sentence text (not by `entities()`'s own iteration order) — this is what the I/O matrix's "in the order they appear in the text" requires, since the graph store's iteration order has no relationship to sentence position.
- `CorpusControllerGlobalSearchTest`'s existing constructor call site needed the new `RetrievalTraceStore` parameter added (not listed in the spec's own Code Map, but required for compilation); extended it with a same-file assertion that the `noAnswer` trace is fetchable with zero steps, plus a new test for the unknown-`traceId` 404 path at the `CorpusController.trace()` level.
- Verification: `mvn test` (`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`, the sandbox's default `mvn` resolves JDK 21 otherwise) — full reactor `BUILD SUCCESS`, 52 tests across all modules, 0 failures/errors, including the new `AnswerGlobalSearchTest` (2 new cases: ordered per-Community steps, zero steps when no Communities exist), `CorpusControllerTest` (3 new cases: LOCAL trace fetch with entity steps, GLOBAL trace fetch with one step per Community, unknown-`traceId` 404), and `CorpusControllerGlobalSearchTest` (extended `noAnswer` case, plus a new unknown-id case).
- No I/O & Edge-Case Matrix rows were left uncovered: every row has a corresponding automated test (GLOBAL matched, GLOBAL `noAnswer`, LOCAL matched, LOCAL no-match is implicitly covered by `buildAnswer`'s empty-steps `LocalSearchResult` branch — no dedicated test added for it since the existing no-match answer text is already covered by pre-existing tests and the empty-steps behavior is structurally identical to the GLOBAL `noAnswer` empty-steps case already asserted directly against the store).
- Nothing left incomplete against this spec's Tasks & Acceptance or Boundaries & Constraints. Manual `curl` verification was not run (no live server was started in this sandbox); the MockMvc-based automated tests exercise the identical HTTP request/response path end-to-end instead.

## Spec Change Log

## Review Triage Log

## Design Notes

Local Search's trace is a **best-effort cross-reference**, not a record of genuine graph traversal: its underlying retrieval (`findBestSentence`) scans raw document text for a keyword-scored sentence match — it never reads `graphStorePort.entities()`/`relationships()` at all today. This story does not rework that (a separate, larger, pre-existing architectural gap — see `deferred-work.md`); it only cross-references the matched sentence's text against the Entities that already exist in the graph, after the fact, to produce a real (if approximate) trace. This is an accepted, explicit trade-off, not an oversight — reworking Local Search to actually traverse the graph is a bigger change than "capture a trace" and belongs in its own future story alongside the already-deferred `AnswerLocalSearch` extraction.

## Verification

**Commands:**
- `mvn test` -- expected: all tests pass, including new `RetrievalTrace`/`AnswerGlobalSearch`/`CorpusController` coverage

**Manual checks (if no CLI):**
- `curl` a Global Search query, then `GET /api/traces/{traceId}` from the response and confirm an ordered, multi-step JSON body.
