---
title: 'Demo-ready end-to-end showcase workflow'
type: 'feature'
created: '2026-09-20'
status: 'in-review'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '39bd7917812bc2a0d4e994f6458c3c2a171dd2fd'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The current demo is difficult to present credibly because workflow readiness is unclear and the retrieval behavior behind “Local” and “Global” search does not consistently reflect an actual graph-scoped GraphRAG story for the chosen corpus.

**Approach:** Deliver a full showcase-credibility slice: make ingestion/query readiness explicit and recoverable in the UI, shift Local Search to graph-grounded retrieval behavior, and ensure Global Search answers are scoped to the currently selected corpus so the presenter can narrate one coherent end-to-end flow.

## Boundaries & Constraints

**Always:**
- Keep existing endpoint paths and SSE envelope shape intact (`/api/corpora`, `/api/corpora/demo`, `/api/corpora/{id}/progress` with `{type,data}`).
- Preserve retrieval trace capture and replay for both Local and Global responses.
- Keep fallback behavior operational when no `OPENAI_API_KEY` is present.
- Keep implementation inside existing module boundaries (`graphrag-core` use cases/ports, adapters, `graphrag-web` orchestration/UI).
- Apply the chosen scope decision for this story: **FULL SHOWCASE CREDIBILITY** (workflow hardening plus retrieval credibility improvements).

**Never:**
- Do not introduce a new frontend build system or framework migration.
- Do not add cross-session persistence requirements beyond current app constraints.
- Do not replace SSE with another transport.
- Do not implement unrelated product redesign work outside the main showcase flow.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Ingestion completes | User uploads corpus or chooses demo dataset; backend emits `ingestion-complete` | Main screen transitions from building to explicit ready state and prompts query action | N/A |
| Ingestion fails | Backend emits SSE `error` payload | Main screen presents clear failure state and retry/restart path without reload | Existing user-facing error content remains visible |
| Local query on selected corpus | User submits LOCAL question after readiness | Response and trace are grounded in graph entities/relationships tied to selected corpus | If no graph-grounded match, return explicit no-match guidance without pretending a match |
| Global query on selected corpus | User submits GLOBAL question | Answer is derived from communities belonging to selected corpus only | If selected corpus has no communities yet, return existing no-answer shape with clear reason |
| Query attempted before readiness | User submits while build still running | Submission is blocked with clear “graph still building” guidance | No backend query call is made |

</frozen-after-approval>

## Code Map

- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/templates/index.html` -- Main-screen UX hooks for ready/failure/recovery guidance.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/static/js/upload.js` -- UI workflow state transitions (building/ready/error) and pre-ready query guard.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- Ingestion and query orchestration; LOCAL/GLOBAL response contract and trace handling.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/main/java/com/graphraglens/core/usecase/AnswerGlobalSearch.java` -- Global-search logic currently reading process-global communities; needs corpus scoping behavior.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/main/java/com/graphraglens/core/usecase/ExtractEntitiesAndRelationships.java` -- Graph extraction persistence path used by ingestion.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/main/java/com/graphraglens/core/port/GraphStorePort.java` -- Graph read/write contract likely requiring corpus-aware retrieval hooks.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/InMemoryGraphStoreAdapter.java` -- Concrete store semantics to update for corpus-scoped reads.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/java/com/graphraglens/web/CorpusStore.java` -- Corpus lifecycle and active-corpus context in web layer.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` -- API contract and ingestion/query lifecycle expectations.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerGlobalSearchTest.java` -- Global-search no-answer and response-shape behavior.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/MainControllerTest.java` -- Main-screen rendering assertions for new workflow affordances.

## Tasks & Acceptance

**Execution:**
- [x] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/static/js/upload.js` -- Implement explicit ingestion-complete ready transition, pre-ready query guard, and recoverable error-state UX wiring -- ensures presenters always know next step.
- [x] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/templates/index.html` -- Add minimal markup for ready and retry/restart guidance states used by `upload.js` -- keeps workflow cues visible without redesign.
- [x] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/main/java/com/graphraglens/core/port/GraphStorePort.java` -- Extend contract for corpus-aware graph/community reads needed by query use cases -- enables credible corpus scoping.
- [x] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/InMemoryGraphStoreAdapter.java` -- Implement corpus-aware storage/read behavior required by the new port methods -- aligns runtime behavior with selected corpus.
- [x] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/main/java/com/graphraglens/core/usecase/AnswerGlobalSearch.java` -- Update global retrieval to only use communities of the selected corpus context -- avoids cross-corpus leakage during demos.
- [x] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- Replace current sentence-first LOCAL answer path with graph-grounded retrieval orchestration and pass corpus context into LOCAL/GLOBAL query flows while preserving response shapes and trace capture -- improves GraphRAG credibility.
- [x] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` -- Add/adjust tests for readiness-guarded querying, graph-grounded local behavior, and corpus-scoped global behavior.
- [x] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerGlobalSearchTest.java` -- Extend tests to verify selected-corpus scoping and no-answer semantics remain explicit.
- [x] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/MainControllerTest.java` -- Update static-page assertions for new ready/recovery cues.

**Acceptance Criteria:**
- Given corpus ingestion reaches completion, when `ingestion-complete` is emitted, then the main UI exits building mode and clearly indicates query readiness.
- Given ingestion emits `error`, when the user remains on the main screen, then they can retry/restart corpus flow without page reload.
- Given a LOCAL query on a ready corpus, when a result is returned, then it is grounded in graph entities/relationships for that corpus and accompanied by a retrieval trace.
- Given a GLOBAL query on a ready corpus, when communities exist, then the answer is derived only from that corpus’s communities and never from another corpus.
- Given a query attempt before readiness, when submit is pressed, then the UI blocks submission and explains that graph construction is still in progress.
- Given the feature changes are complete, when test suites run, then existing endpoint shapes and trace payload contracts remain compatible.

## Implementation Notes

- Implemented corpus workflow lifecycle states (`BUILDING`/`READY`/`FAILED`) in `graphrag-web/src/main/java/com/graphraglens/web/CorpusStore.java` and wired query-time `409` readiness guards in `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java`.
- Added corpus-scoped graph storage/retrieval methods in `graphrag-core/src/main/java/com/graphraglens/core/port/GraphStorePort.java` and implemented them in `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/InMemoryGraphStoreAdapter.java`.
- Updated retrieval use cases for corpus scoping: `graphrag-core/src/main/java/com/graphraglens/core/usecase/ExtractEntitiesAndRelationships.java`, `graphrag-core/src/main/java/com/graphraglens/core/usecase/DetectCommunities.java`, and `graphrag-core/src/main/java/com/graphraglens/core/usecase/AnswerGlobalSearch.java`.
- Replaced LOCAL query path with graph-grounded matching in `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` while preserving trace capture/shape.
- Added workflow readiness/recovery UI in `graphrag-web/src/main/resources/templates/index.html` and `graphrag-web/src/main/resources/static/js/upload.js` (includes `ingestion-complete` transition and retry/restart affordances).
- Updated tests in:
  - `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java`
  - `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerGlobalSearchTest.java`
  - `graphrag-web/src/test/java/com/graphraglens/web/MainControllerTest.java`
  - `graphrag-core/src/test/java/com/graphraglens/core/usecase/AnswerGlobalSearchTest.java`
  - `graphrag-core/src/test/java/com/graphraglens/core/usecase/DetectCommunitiesTest.java`
  - `graphrag-core/src/test/java/com/graphraglens/core/usecase/ExtractEntitiesAndRelationshipsTest.java`
  - `graphrag-adapter-neo4j/src/test/java/com/graphraglens/adapter/neo4j/InMemoryGraphStoreAdapterTest.java`
- Verification is blocked in this environment because JDK 25 is unavailable:
  - `mvn test` fails at enforcer (`GraphRAG Lens requires JDK 25 or newer`)
  - `mvn -Denforcer.skip=true test` fails during compile (`release version 25 not supported`)
  - `mvn -pl graphrag-web test` fails standalone dependency resolution without prior reactor install.

## Spec Change Log

## Review Triage Log

- blind-hunter — `edge-case-hunter` returned `Input empty or undecodable` for `/tmp/spec-showcase-review.diff` — **verdict: false** — other layers successfully read the same diff file, so this is reviewer execution failure, not a product defect.
- blind-hunter — `InMemoryGraphStoreAdapter` read methods mutated scoped maps via `computeIfAbsent` — **verdict: medium / patch** — fixed by switching scoped reads to non-mutating `readScopedMap(...)`.
- blind-hunter — per-corpus maps are not fully synchronized under concurrent ingestion/query — **verdict: maybe-false / defer** — shared maps were pre-existing and no concrete failing path was demonstrated; would need concurrent stress test evidence.
- blind-hunter — SSE `"error"` event conflated payload errors with transport errors in `upload.js` — **verdict: medium / patch** — fixed by only transitioning to FAILED when structured payload error exists.
- blind-hunter — “Retry progress stream” does not restart failed graph construction — **verdict: low / false** — UI offers explicit restart path and this story’s accepted behavior is recovery guidance, not in-place pipeline replay.
- blind-hunter — no scoped communities/memberships adapter tests — **verdict: medium / patch** — added `readsScopedCommunitiesAndMembershipsByCorpusId` in `InMemoryGraphStoreAdapterTest`.
- blind-hunter — `ExtractEntitiesAndRelationshipsTest` did not assert corpus id persisted — **verdict: medium / patch** — added `persistedCorpusId` assertion.
- blind-hunter — `DetectCommunitiesTest` did not verify corpus-scoped reads/writes — **verdict: medium / patch** — recording store now captures read/persist corpus ids and assertions added.
- blind-hunter — `CorpusStore.status()` default BUILDING can block unknown corpus ids — **verdict: low / false** — unknown ids are guarded earlier by `corpusStore.get(...).orElseThrow(...)`, so this path does not create user-visible indefinite blocks.
- blind-hunter — async failure path emits generic message without exception details — **verdict: low / defer** — behavior is intentional user-safe messaging and logging strategy is outside this story’s approved scope.
- verification-gap — FAILED branch at query boundary unverified — **verdict: medium / patch** — added `queryingAfterFailedIngestionReturnsConflictWithFailureGuidance`.
- verification-gap — LOCAL corpus scoping unverified in multi-corpus scenario — **verdict: medium / patch** — added `localSearchReadsOnlyGraphDataFromTheSelectedCorpus`.
- verification-gap — relationship-step trace behavior weakly asserted — **verdict: medium / patch** — strengthened trace test to require `RELATIONSHIP` kind.

## Design Notes

Use a consistent corpus context key from ingestion through query-time use cases so Local and Global behavior both map to the same selected corpus identity. Keep retrieval trace semantics stable: behavior can improve, but every successful LOCAL/GLOBAL response still returns trace metadata and a retrievable trace resource.

## Verification

**Commands:**
- `cd /home/runner/work/graph-rag-visualisation/graph-rag-visualisation && mvn test` -- expected: all module tests pass.
- `cd /home/runner/work/graph-rag-visualisation/graph-rag-visualisation && mvn -pl graphrag-web test` -- expected: web module tests pass with updated workflow and query semantics.
- `cd /home/runner/work/graph-rag-visualisation/graph-rag-visualisation && mvn -pl graphrag-core test` -- expected: core retrieval/use-case tests pass for corpus-aware logic.

**Manual checks (if no CLI):**
- Load demo dataset and verify transition from Building to Ready before allowing query submit.
- Ask a LOCAL question and verify returned trace reflects graph-grounded steps.
- Load a second corpus and verify GLOBAL answers are scoped to the currently selected corpus.
