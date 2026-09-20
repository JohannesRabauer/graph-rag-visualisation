---
title: 'Implement AnswerDriftSearch'
type: 'feature' # feature | bugfix | refactor | chore
created: '2026-09-20'
status: 'done' # draft | ready-for-dev | in-progress | in-review | done | blocked
baseline_revision: '7341ecb7c16cea936a374d27050f453a261e4b88'
baseline_commit: '7341ecb7c16cea936a374d27050f453a261e4b88'
review_loop_iteration: 0 # incremented by step-04 before each review loopback
followup_review_recommended: false # set by step-04 on status: done — true if the LLM decided another review pass is worthwhile
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** `CorpusController`'s DRIFT branch (Story 7.1) always returns a static `{noAnswer: true, reason: "DRIFT Search isn't implemented yet."}` placeholder — DRIFT cannot actually answer a question yet, so it never demonstrates its distinct retrieval mechanic.

**Approach:** Add a new `AnswerDriftSearch` use case in `graphrag-core` that reads Community summaries (like `AnswerGlobalSearch`), derives targeted sub-questions from the best-matching communities via a new `LlmPort` default method, answers each sub-question through the existing `AnswerLocalSearch` traversal, then synthesizes one final answer from the sub-answers. Wire `CorpusController`'s DRIFT branch to call it instead of the placeholder.

## Boundaries & Constraints

**Always:**
- `AnswerDriftSearch` lives in `graphrag-core`'s `usecase` package and depends only on `GraphStorePort` and `LlmPort` (same constructor-injection style as `AnswerGlobalSearch`/`AnswerLocalSearch`) — no new port, no direct dependency on `CorpusController` or web-layer types.
- The Community pass reuses `KeywordMatcher` scoring exactly like `AnswerGlobalSearch` does, and records one `RetrievalStep(Kind.COMMUNITY, ...)` per Community examined, in iteration order — the same shape `AnswerGlobalSearch` already produces, so this pass stays visually/behaviorally consistent with Global Search's own Community pass.
- Sub-question generation is dispatched through a new `LlmPort` default method (not inlined in the use case) so it stays swappable/provider-agnostic per this project's hexagonal-port convention; the default implementation must be fully deterministic (no network/LLM call) so tests and the demo stay reproducible, matching `summarizeCommunity`'s existing default-method pattern.
- Each derived sub-question is answered by calling the existing `AnswerLocalSearch(graphStorePort).answer(subQuestion, corpusId)` unchanged — DRIFT must not reimplement or fork Local Search's traversal logic.
- The response contract stays byte-for-byte compatible with what Story 7.1 already shipped: `CorpusController`'s DRIFT branch still returns the same wire shape as before (`answerId`/`traceId`/`traceStepCount` plus either `{noAnswer, reason}` or `{answer, mode: "DRIFT"}`) — only the *values* behind that shape change from the static placeholder to real results.
- No Community summaries exist yet (empty `communities(corpusId)`) is still the distinct `noAnswer` outcome (mirrors `GlobalSearchAnswer.noCommunitiesYet()`), with a DRIFT-specific reason naming that a Community pass hasn't run yet.
- Communities exist but none score a keyword match against the question means the Community pass yields zero viable sub-questions — per this story's own acceptance criteria (epics.md Story 7.2), this is also the distinct `noAnswer` outcome (a second, DRIFT-specific reason), not an ordinary answer. Only once at least one sub-question is actually derived and answered does every further outcome become an ordinary, successful answer — even when the spawned `AnswerLocalSearch` call finds no graph-grounded hop.
- Steps returned are a single flat, ordered list: the Community pass steps first, then each spawned sub-question's `AnswerLocalSearch` steps appended in spawn order — reusing only the existing `RetrievalStep.Kind` values (`COMMUNITY`, `ENTITY`, `RELATIONSHIP`). This one deterministic traversal order is what Story 7.3's branching trace capture and Story 7.4's tree replay will build on.

**Never:**
- Do not add a new `RetrievalStep.Kind` (e.g. a `sub-question-spawned` kind) or persist any DRIFT-specific trace structure beyond the flat steps list — that is Story 7.3's scope.
- Do not touch `CorpusController`'s trace-capture (`captureTrace`) mechanics, the branching tree replay UI, or `instrument.css`/`upload.js` — those are Story 7.3/7.4, out of scope here.
- Do not call a real external LLM/network API — the default `LlmPort` sub-question method must stay deterministic, exactly like every other default method on that port.
- Do not change `AnswerLocalSearch` or `AnswerGlobalSearch`'s own behavior, signatures, or response shapes — DRIFT only calls them, it does not modify them.
- Do not change the mode-choice UI, hint text, or CSS — Story 7.1 already shipped those; this story is backend-only.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| No Communities persisted yet | `mode: "DRIFT"` query, `graphStorePort.communities(corpusId)` empty | `200 OK`, `noAnswer: true`, DRIFT-specific reason naming that a Community pass hasn't run; zero steps | N/A — ordinary no-answer outcome |
| Communities exist but none match the question | Communities persisted, but every one scores 0 against the question's keywords | `200 OK`, `noAnswer: true`, DRIFT-specific reason naming that no sub-questions could be generated (epics.md Story 7.2 AC); steps contain only the Community-pass entries | N/A — ordinary no-answer outcome |
| A Community matches and its spawned sub-question finds a graph-grounded hop | Communities persisted, best-scoring Community's derived sub-question matches an Entity/Relationship via `AnswerLocalSearch` | `200 OK`, `answer` synthesizing the sub-question's graph-grounded finding, `mode: "DRIFT"`; steps = Community-pass steps + the winning sub-question's Local Search steps | N/A — happy path |
| A Community matches but its spawned sub-question finds no graph-grounded hop | Communities persisted and matched, but the derived sub-question's `AnswerLocalSearch` call returns `LocalSearchAnswer.noMatch()` | `200 OK`, ordinary `answer` stating DRIFT's community pass matched but the spawned sub-question found no graph-grounded local match; steps still include the Community-pass entries | N/A — ordinary answer outcome, never a fabricated hop |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/com/graphraglens/core/usecase/AnswerDriftSearch.java` (new) -- the use case itself: Community pass (mirrors `AnswerGlobalSearch.answer`, lines 34–58) → sub-question derivation via `LlmPort` → per-sub-question `AnswerLocalSearch` calls → synthesis. Constructor takes `(GraphStorePort, LlmPort)`.
- `graphrag-core/src/main/java/com/graphraglens/core/usecase/DriftSearchAnswer.java` (new) -- result record `(boolean noAnswer, String answer, String reason, List<RetrievalStep> steps)`, same shape/compact-constructor pattern as `GlobalSearchAnswer.java` (lines 1–41) and `LocalSearchAnswer.java` (lines 1–33).
- `graphrag-core/src/main/java/com/graphraglens/core/port/LlmPort.java` (lines 1–36) -- add a new default method, e.g. `List<String> deriveDriftSubQuestions(String question, Collection<Community> communities)`, deterministic like the existing `summarizeCommunity` default (lines 12–29): given the (already-scored, caller-selected) candidate Communities, produce one sub-question string per Community by combining the original question with that Community's summary text.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` (lines 141–150, the current `DRIFT` branch) -- replace the static placeholder with a call to a new `driftSearchResponse(question, corpus.id())` private method mirroring `globalSearchResponse` (lines 152–168): builds an `AnswerDriftSearch(graphStorePort, llmPort)`, calls `.answer(...)`, and maps `DriftSearchAnswer` onto the same wire shape `globalSearchResponse` already uses (`noAnswer`/`reason` vs `answer`/`mode: "DRIFT"`).
- `graphrag-core/src/test/java/com/graphraglens/core/usecase/AnswerDriftSearchTest.java` (new) -- mirrors `AnswerGlobalSearchTest.java`'s stub-`GraphStorePort` pattern (a `StubGraphStore implements GraphStorePort`), covering the four I/O matrix rows plus a steps-ordering assertion (Community steps before Local Search steps).
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` (lines 528–548, `driftSearchReturnsTheNoAnswerShapeUntilTheImplementationExists`) -- this test's shared `graphStorePort` fixture has no Communities persisted under `"ready-corpus"`, so DRIFT still resolves to `noAnswer`; rename the test to reflect the *real* no-answer path (not "not implemented yet") and update the expected `reason` text to the new DRIFT-specific no-Communities-yet wording.
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerGlobalSearchTest.java` (whole file) -- shows the pattern for an isolated, non-shared `InMemoryGraphStoreAdapter` test bypassing the shared Spring singleton fixture; use the same pattern for a new isolated DRIFT integration test that persists real Communities/Entities/Relationships and asserts a real synthesized answer end-to-end.

## Tasks & Acceptance

**Execution:**
- [x] `graphrag-core/src/main/java/com/graphraglens/core/usecase/DriftSearchAnswer.java` -- add the new result record with `matched(...)` and `noCommunitiesYet()` factory methods (mirroring `GlobalSearchAnswer`) -- gives `AnswerDriftSearch` a typed, trace-store-unaware result shape.
- [x] `graphrag-core/src/main/java/com/graphraglens/core/port/LlmPort.java` -- add the `deriveDriftSubQuestions(String question, Collection<Community> communities)` default method with a deterministic implementation -- keeps sub-question generation behind the port abstraction without requiring a real LLM.
- [x] `graphrag-core/src/main/java/com/graphraglens/core/usecase/AnswerDriftSearch.java` -- implement the Community pass, sub-question spawning, per-sub-question `AnswerLocalSearch` calls, and synthesis, producing a `DriftSearchAnswer` -- the story's core deliverable.
- [x] `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- wire the `DRIFT` branch to `AnswerDriftSearch` via a new `driftSearchResponse` method, removing the static placeholder -- makes DRIFT queries actually answer.
- [x] `graphrag-core/src/test/java/com/graphraglens/core/usecase/AnswerDriftSearchTest.java` -- unit tests covering all four I/O matrix rows plus step ordering -- proves the use case's contract in isolation.
- [x] `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` -- rename/update the existing DRIFT placeholder test to assert the real no-Communities-yet `noAnswer` path -- keeps this suite aligned with the new real behavior instead of asserting a placeholder that no longer exists.
- [x] `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerGlobalSearchTest.java`-adjacent new test file (e.g. `CorpusControllerDriftSearchTest.java`) -- an isolated end-to-end test persisting real Communities/Entities/Relationships and asserting DRIFT returns a real, graph-grounded synthesized answer -- covers the happy-path I/O row through the full web-layer wiring, not just the use case in isolation.

**Acceptance Criteria:**
- Given a corpus with no Communities detected yet, when a `DRIFT` query is submitted, then the response is `200 OK` with `noAnswer: true` and a reason naming that DRIFT's Community pass hasn't run yet — never the old "isn't implemented yet" text.
- Given a corpus with Communities but none matching the question's keywords, when a `DRIFT` query is submitted, then the response is `200 OK` with `noAnswer: true` and a DRIFT-specific reason naming that no sub-questions could be generated (epics.md Story 7.2 AC).
- Given a corpus whose best-matching Community's spawned sub-question resolves to a real graph-grounded hop via `AnswerLocalSearch`, when a `DRIFT` query is submitted, then the response is `200 OK` with `mode: "DRIFT"` and an `answer` reflecting that hop, and the trace's steps begin with the Community-pass steps followed by that hop's Local Search steps.
- Given the same setup but the spawned sub-question finds no graph-grounded hop, when a `DRIFT` query is submitted, then the response is still an ordinary `200 OK` answer (never a fabricated hop), stating the sub-question found no graph-grounded local match.

## Spec Change Log

- 2026-09-20 — Correction (self-caught during step-04 review, before the formal review layers ran): the initially drafted `<intent-contract>` mistakenly treated "Communities exist but none score a match" as an ordinary answer, mirroring `AnswerGlobalSearch`'s convention. This directly contradicted this story's own acceptance criteria in `epics.md` ("if the Community pass yields no viable sub-questions, the response is the distinct `noAnswer` shape, naming DRIFT specifically"). Amended the "Always" boundary, the I/O matrix's second row, and the Acceptance Criteria to require the distinct `noAnswer` shape (with a new `DriftSearchAnswer.noViableSubQuestions(...)` factory) whenever zero Communities score a match — not only when zero Communities exist at all. KEEP: everything else in the original design (Community-pass step recording, sub-question derivation via `LlmPort`, delegating to unmodified `AnswerLocalSearch`, the "first graph-grounded hop wins" synthesis rule, and treating "sub-questions were spawned but found no grounded hop" as an ordinary answer) was correct and is unchanged.

## Review Triage Log

- 2026-09-20 — `medium` — `graphrag-core/src/main/java/com/graphraglens/core/usecase/AnswerDriftSearch.java`: verified that the original success check accepted any non-`noMatch()` `LocalSearchAnswer`, including entity-only matches with no `RELATIONSHIP` step, so DRIFT could mislabel a non-hop result as graph-grounded; fixed by requiring a relationship step before synthesizing a grounded answer.
- 2026-09-20 — `medium` — `graphrag-core/src/main/java/com/graphraglens/core/usecase/AnswerDriftSearch.java`: verified that once a grounded sub-answer was chosen, later tied sub-questions still appended unrelated trace steps; fixed by stopping after the first grounded hop so the trace matches the surfaced answer.
- 2026-09-20 — `low` — `graphrag-core/src/main/java/com/graphraglens/core/usecase/AnswerDriftSearch.java`: verified that equal-score candidate Communities previously inherited whatever collection order the store returned, leaving spawn order under-specified across implementations; fixed by sorting tied candidates lexicographically by Community id before spawning sub-questions.
- 2026-09-20 — `low` — `graphrag-core/src/test/java/com/graphraglens/core/usecase/AnswerDriftSearchTest.java`: verified that the original DRIFT unit suite had no entity-only local-match case, so the mislabelled non-hop path could regress silently; fixed by adding `treatsEntityOnlyLocalMatchesAsNoGraphGroundedHop`.
- 2026-09-20 — `low` — `graphrag-core/src/test/java/com/graphraglens/core/usecase/AnswerDriftSearchTest.java`: verified that the original ordering coverage never exercised two successful spawned branches, so answer/trace divergence after the first success was untested; fixed by adding `stopsAfterTheFirstGraphGroundedSubQuestionSoTraceMatchesTheAnswer`.
- 2026-09-20 — `low` — `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerDriftSearchTest.java`: verified that the web layer lacked a DRIFT controller test for the "communities exist but none match" contract row; fixed by adding `driftSearchReturnsAnOrdinaryAnswerWhenCommunitiesExistButNoneMatch`.
- 2026-09-20 — `low` — `graphrag-web/src/test/java/com/graphraglens/web/ui/DriftModeChoiceUiTest.java`: verified that the updated UI assertion only checked for the substring `DRIFT`, which would still pass on fallback responses; fixed by asserting the rendered happy-path answer mentions `Irene Adler`.
- 2026-09-20 — `medium` — `graphrag-core/src/test/java/com/graphraglens/core/usecase/AnswerDriftSearchTest.java`: verified that no test proved DRIFT actually depends on Community-summary token enrichment to reach a local hop; fixed by adding `usesCommunitySummaryTokensToDeriveAnswerableSubQuestions`.
- 2026-09-20 — `medium` — `graphrag-core/src/test/java/com/graphraglens/core/usecase/AnswerDriftSearchTest.java`: verified that the earlier corpus-scoping test returned before local traversal, so a `null`/wrong `corpusId` leak into `AnswerLocalSearch` would not have failed; fixed by adding `keepsLocalTraversalScopedToTheRequestedCorpus`.

- 2026-09-20 — `medium` — `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerDriftSearchTest.java`: verification-gap review found the "communities exist but none match" DRIFT controller test asserted only `noAnswer`/`reason`, never the trace; a regression that reported `traceStepCount: 0` or dropped the trace entirely would have gone undetected; fixed by asserting `traceStepCount == 1` and the stored trace's single `COMMUNITY` step.
- 2026-09-20 — `medium` — `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerDriftSearchTest.java`: verification-gap review found no DRIFT controller test proved corpus scoping end-to-end (unlike GLOBAL's `CorpusControllerGlobalSearchTest.globalSearchUsesOnlyCommunitiesForTheSelectedCorpus`), so a wrong/leaked `corpusId` in the wiring would not have failed any test; fixed by adding `driftSearchUsesOnlyCommunitiesAndEntitiesForTheSelectedCorpus` with two corpora holding conflicting Communities/Entities.
- 2026-09-20 — `medium` — `graphrag-core/src/test/java/com/graphraglens/core/usecase/AnswerDriftSearchTest.java`: verification-gap review found tied top-scoring Communities' spawn order (lexicographic by id) was only exercised indirectly, through tests that override `deriveDriftSubQuestions` with a fixed list — none proved the *default* `LlmPort` implementation actually receives candidates in that order rather than store order; fixed by adding `ordersTiedCandidatesLexicographicallyByCommunityIdRegardlessOfStoreOrder`, which reverses store order vs. id order and asserts the lexicographically-first candidate's hop wins.
- 2026-09-20 — `defer` — blind-hunter/edge-case-hunter review noted `AnswerDriftSearch` does not null-guard `llmPort.deriveDriftSubQuestions(...)` returning `null`, nor a `Community` entry in the store's collection being `null`; the shipped default `LlmPort` and `InMemoryGraphStoreAdapter` never produce these, and no sibling use case (`AnswerGlobalSearch`) defends against them either — deferred as a general hardening concern for a future custom-port-implementation story, not a regression introduced here.
- 2026-09-20 — `defer` — blind-hunter review noted the default `LlmPort.deriveDriftSubQuestions` could emit duplicate sub-question text if two tied top-scoring Communities happen to share an identical summary, causing a redundant repeated local traversal; low-likelihood with this demo's distinct Community summaries and no observed behavior break — deferred as a polish item, not required by this story's acceptance criteria.
- 2026-09-20 — `reject` — blind-hunter review suggested `DriftSearchAnswer.noCommunitiesYet()`'s "wait for the Community pass to finish" reason text could mislead a `READY` corpus that has no Communities at all (rather than one still processing); rejected — this wording and condition were carried over unchanged from `GlobalSearchAnswer.noCommunitiesYet()`'s existing, already-shipped convention (Story 4.x/7.1), not something this story introduced or is in scope to revise.
- 2026-09-20 — `reject` — blind-hunter/verification-gap review flagged that COMMUNITY trace steps are recorded in store iteration order while sub-question spawn order is sorted lexicographically by Community id, and that "first grounded hop wins" surfaces spawn-order-dependent (not best-scored) answers; rejected — both are explicit, spec'd design decisions (`<intent-contract>`'s "records one step per Community examined, in iteration order" and the Design Notes' "prefer the first one, in spawn order" re-ranking rule), not defects.

## Auto Run Result

- **Story:** 7.2 — Implement AnswerDriftSearch (Epic 7, DRIFT Search v1.1).
- **Files changed:** `AnswerDriftSearch.java` (new), `DriftSearchAnswer.java` (new), `LlmPort.java` (added `deriveDriftSubQuestions` default method), `CorpusController.java` (DRIFT branch wired to the real use case), `AnswerDriftSearchTest.java` (new, 11 tests), `CorpusControllerDriftSearchTest.java` (new, 3 tests), `CorpusControllerTest.java` (updated placeholder test), `DriftModeChoiceUiTest.java` (updated to assert a real synthesized answer).
- **Findings breakdown:** 1 `intent_gap`-class defect self-corrected before formal review (the "Communities exist but none match" outcome was implemented as an ordinary answer, contradicting epics.md's explicit AC that it must be the distinct `noAnswer` shape naming DRIFT — corrected in code, tests, and the `<intent-contract>` itself, with a Spec Change Log entry recording the correction). Formal 4-layer review (blind-hunter, edge-case-hunter, verification-gap, intent-alignment) surfaced 3 genuine verification gaps (all `patch`ed — trace assertion on the no-match path, cross-corpus scoping coverage, tied-candidate default-order determinism), 2 `defer`red hardening nits (null-guards for a hypothetical custom `LlmPort`/`GraphStorePort`, duplicate-sub-question dedup), and 2 `reject`ed items (pre-existing message wording carried over from `AnswerGlobalSearch`; two by-design behaviors already documented in the intent-contract/Design Notes). Intent-alignment audit found no unresolved surface divergence beyond what the self-correction already addressed.
- **Follow-up review recommended:** false — all patch-routed findings were fixed and re-verified; deferred items are low-risk, out-of-scope hardening for hypothetical custom port implementations, not regressions.
- **Verification performed:** `mvn -pl graphrag-core,graphrag-web -am test -Dtest=AnswerDriftSearchTest,CorpusControllerTest,CorpusControllerDriftSearchTest,DriftModeChoiceUiTest` (41/41 passing) and a full `mvn test` across all modules (94/94 passing, 0 failures/errors).
- **Residual risks:** the default `LlmPort.deriveDriftSubQuestions` and its deterministic candidate ordering are demo-appropriate but not hardened against a real/custom LLM provider returning `null`, duplicate, or off-topic sub-questions — acceptable for this story's scope (Story 7.3/7.4 own the trace/UI layers this could later interact with).

## Design Notes

**Sub-question derivation stays deterministic and simple, matching the rest of the demo's provider-agnostic default methods.** `LlmPort.deriveDriftSubQuestions` should not attempt real natural-language sub-question synthesis in its default implementation — it should behave like `summarizeCommunity`'s default: a plain, reproducible string built from the inputs (e.g. combining the original question with the candidate Community's summary text), so `AnswerLocalSearch`'s existing keyword scorer has extra, Community-relevant tokens to match against. A real LLM adapter can override this method later without changing `AnswerDriftSearch`'s contract.

**Candidate-selection stays inside `AnswerDriftSearch`, not the port.** `AnswerDriftSearch` scores and picks candidate Communities itself (same `KeywordMatcher` scoring `AnswerGlobalSearch` already uses) before calling `llmPort.deriveDriftSubQuestions(question, candidateCommunities)` — the port method only turns already-chosen Communities into sub-question text, it does not do its own Community selection. This keeps the deterministic-scoring responsibility in one place, consistent with how `AnswerGlobalSearch`/`AnswerLocalSearch` already own their own scoring.

**Re-ranking rule for synthesis:** among the sub-answers produced (likely just 1–2, since this demo's Communities are small), prefer the first one (in spawn order) whose `LocalSearchAnswer.answer()` reflects a real graph-grounded hop — i.e. not equal to `LocalSearchAnswer.noMatch()`'s guidance text — as the final synthesized answer; if none matched, fall back to one ordinary explanatory answer naming that the community pass matched but no sub-question found a graph-grounded hop.

## Verification

**Commands:**
- `mvn -pl graphrag-core -am test -Dtest=AnswerDriftSearchTest` -- expected: all pass, covering all four I/O matrix rows.
- `mvn -pl graphrag-web -am test -Dtest=CorpusControllerTest,CorpusControllerDriftSearchTest` -- expected: all pass, including the renamed/updated placeholder test and the new isolated end-to-end DRIFT test.
