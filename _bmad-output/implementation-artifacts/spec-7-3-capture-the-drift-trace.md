---
title: 'Capture the DRIFT Trace'
type: 'feature' # feature | bugfix | refactor | chore
created: '2026-09-20'
status: 'done' # draft | ready-for-dev | in-progress | in-review | done | blocked
baseline_revision: '2237fd011be3e86e84c6d04979494a1ef3a33874'
baseline_commit: '2237fd011be3e86e84c6d04979494a1ef3a33874'
review_loop_iteration: 0 # incremented by step-04 before each review loopback
followup_review_recommended: false # set by step-04 on status: done — true if the LLM decided another review pass is worthwhile
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** `AnswerDriftSearch` (Story 7.2) stops evaluating spawned sub-questions as soon as one finds a graph-grounded hop, so the captured trace never shows DRIFT's real branching shape (every spawned sub-question, not just the winning one), and there is no dedicated step recording the final re-rank/synthesize outcome.

**Approach:** Add two `RetrievalStep.Kind` values (`SUB_QUESTION_SPAWNED`, `SYNTHESIS`); change `AnswerDriftSearch` to always evaluate every spawned sub-question (recording one `SUB_QUESTION_SPAWNED` step plus that branch's own `AnswerLocalSearch` steps for each), then append exactly one final `SYNTHESIS` step after all branches, still selecting the first grounded hop (in spawn order) as the answer — this is a required, epics.md-mandated change to Story 7.2's control flow, not new scope invention.

## Boundaries & Constraints

**Always:**
- `RetrievalStep.Kind` gains exactly two new constants: `SUB_QUESTION_SPAWNED` and `SYNTHESIS`, reusing the existing `(kind, identifier, label)` shape — no new fields on `RetrievalStep`/`RetrievalTrace`. `identifier`/`label` carry the extra data: a `SUB_QUESTION_SPAWNED` step's `identifier` is its parent candidate Community's `id()` and `label` is the exact spawned sub-question text; a `SYNTHESIS` step's `identifier` is the winning branch's parent Community id (or `""` if no branch grounded) and `label` is the final synthesized/fallback answer text.
- `AnswerDriftSearch` must evaluate **every** spawned sub-question once bestScore > 0 (never stop early): for each sub-question (by index against the same-index candidate from the already-sorted `candidates` list; if `subQuestions` is longer than `candidates`, extra entries get `identifier = ""` since they cannot be attributed to a candidate), append one `SUB_QUESTION_SPAWNED` step, call `AnswerLocalSearch` unchanged, and append that branch's own steps in order immediately beneath it — mirrors epics.md Story 7.3 AC's "each sub-question's own Local Search steps are appended in order beneath it".
- After all branches are recorded, append exactly one final `SYNTHESIS` step, then return one `DriftSearchAnswer`. The final answer/synthesis logic itself is unchanged from Story 7.2: the first branch (in spawn order) whose `LocalSearchAnswer` has a `RELATIONSHIP` step wins; if none do, the existing "did not find a graph-grounded local match yet" fallback text is used — only when this step is recorded, and how many branches got explored beforehand, changes.
- The whole sequence (Community-pass steps, then each branch's `SUB_QUESTION_SPAWNED` + Local Search steps, then the one `SYNTHESIS` step) stays a single flat, ordered `List<RetrievalStep>`, addressed by one `traceId` exactly like today — no change to `RetrievalTrace`, `RetrievalTraceStore`, or `CorpusController`'s trace capture/serialization (`captureTrace`, `/api/traces/{traceId}`, `stepPayload`), since those already handle any `RetrievalStep.Kind` generically via `step.kind().name()`.
- The two `noAnswer` outcomes from Story 7.2 (`noCommunitiesYet()`, `noViableSubQuestions(...)`) are unaffected: no sub-question is ever spawned in either case, so neither new step kind ever appears in those traces.

**Never:**
- Do not change `RetrievalStep`'s or `RetrievalTrace`'s record shape (no new fields, no new type) — the two new `Kind` values are the entire extension.
- Do not change `CorpusController`, `RetrievalTraceStore`, or any HTTP wire shape — this story is `graphrag-core`-only (`AnswerDriftSearch`, `RetrievalStep.Kind`, and their tests).
- Do not change the re-ranking rule itself (first grounded hop in spawn order wins) — Story 7.3 is only about recording every branch and adding the synthesis step, not about picking a different or "better" answer.
- Do not touch the branching-tree replay UI, `graph-canvas.js`, or `instrument.css` — that is Story 7.4, out of scope here.
- Do not change `AnswerLocalSearch`, `AnswerGlobalSearch`, `LlmPort.deriveDriftSubQuestions`, or the LOCAL/GLOBAL trace shapes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| One community matches, its sub-question grounds | 1 candidate Community, 1 spawned sub-question resolves via `AnswerLocalSearch` to a `RELATIONSHIP` hop | Steps: `COMMUNITY`, `SUB_QUESTION_SPAWNED`, the branch's own steps (e.g. `ENTITY, RELATIONSHIP, ENTITY`), `SYNTHESIS`; answer reflects the grounded hop | N/A — happy path |
| Two tied communities; the first-spawned branch grounds, the second does not | 2 candidates, 2 sub-questions; branch 1 finds a `RELATIONSHIP` hop, branch 2 finds none | Steps include **both** branches' `SUB_QUESTION_SPAWNED` + Local Search steps (branch 2's local steps may be empty on `noMatch()`), then one final `SYNTHESIS`; answer/synthesis `identifier` names branch 1's Community even though branch 2 was still fully traced | N/A — every branch traced regardless of which one wins |
| No community scores a match (Story 7.2's `noViableSubQuestions`) | `bestScore <= 0` | Steps: only the `COMMUNITY` pass entries — no `SUB_QUESTION_SPAWNED`/`SYNTHESIS` steps, since no sub-question is ever spawned | N/A — unchanged from Story 7.2 |
| No Communities persisted yet (Story 7.2's `noCommunitiesYet`) | empty `communities(corpusId)` | Zero steps, `noAnswer: true` — unchanged from Story 7.2 | N/A — unchanged from Story 7.2 |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/com/graphraglens/core/domain/RetrievalStep.java` (lines 24–28, the `Kind` enum) -- add `SUB_QUESTION_SPAWNED` and `SYNTHESIS` alongside `ENTITY`, `RELATIONSHIP`, `COMMUNITY`. Update the class Javadoc's kind list.
- `graphrag-core/src/main/java/com/graphraglens/core/usecase/AnswerDriftSearch.java` (the `bestScore > 0` branch, currently the `for (String subQuestion : subQuestions)` loop with an early `break`) -- replace the loop: for each `subQuestion` at index `i`, resolve `parentId = i < candidates.size() ? candidates.get(i).id() : ""`, append `new RetrievalStep(Kind.SUB_QUESTION_SPAWNED, parentId, subQuestion)`, call `answerLocalSearch.answer(...)`, append its steps, and record `(parentId, subAnswer)` pairs in a list — never `break`. After the loop, scan that recorded list in order for the first entry with `hasRelationshipHop(subAnswer)`; build the final answer text exactly as Story 7.2 did (grounded text or the existing fallback text), append one `new RetrievalStep(Kind.SYNTHESIS, winningParentId or "", finalAnswerText)`, then `return DriftSearchAnswer.matched(finalAnswerText, steps)`.
- `graphrag-core/src/test/java/com/graphraglens/core/usecase/AnswerDriftSearchTest.java` -- update every test that asserts an exact `RetrievalStep.Kind` sequence to include the new `SUB_QUESTION_SPAWNED`/`SYNTHESIS` steps (`treatsEntityOnlyLocalMatchesAsNoGraphGroundedHop`, `keepsCommunityStepsBeforeEverySpawnedLocalSearchStep`); rewrite `stopsAfterTheFirstGraphGroundedSubQuestionSoTraceMatchesTheAnswer` (rename to reflect that every branch is now traced, e.g. `tracesEveryBranchButSynthesizesFromTheFirstGroundedHop`) to assert both branches' steps appear in the trace while the answer/synthesis step still reflects only the first grounded one; add one new test asserting the `SUB_QUESTION_SPAWNED` step's `identifier`/`label` carry the parent Community id and sub-question text. Tests that only assert answer text (not step-kind sequences) need no change.
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerDriftSearchTest.java` (`driftSearchReturnsARealSynthesizedAnswerAndStoresItsTrace`) -- update the expected `traceStepCount` (4 → 6) and the exact kind sequence (`COMMUNITY, ENTITY, RELATIONSHIP, ENTITY` → `COMMUNITY, SUB_QUESTION_SPAWNED, ENTITY, RELATIONSHIP, ENTITY, SYNTHESIS`) to match the new steps. The other three tests in this file (no-Communities-yet, no-match, cross-corpus scoping) are unaffected since they never reach the sub-question-spawning branch or only assert answer text.

## Tasks & Acceptance

**Execution:**
- [x] `graphrag-core/src/main/java/com/graphraglens/core/domain/RetrievalStep.java` -- add `SUB_QUESTION_SPAWNED` and `SYNTHESIS` to the `Kind` enum -- gives DRIFT its two new trace-step kinds without changing the record shape.
- [x] `graphrag-core/src/main/java/com/graphraglens/core/usecase/AnswerDriftSearch.java` -- remove the early `break`, always evaluate every spawned sub-question, record a `SUB_QUESTION_SPAWNED` step per branch, and append one final `SYNTHESIS` step after all branches -- the story's core deliverable, satisfying epics.md Story 7.3's AC.
- [x] `graphrag-core/src/test/java/com/graphraglens/core/usecase/AnswerDriftSearchTest.java` -- update the step-sequence assertions listed in the Code Map and add the new `SUB_QUESTION_SPAWNED` identifier/label test -- proves the new trace shape in isolation.
- [x] `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerDriftSearchTest.java` -- update `driftSearchReturnsARealSynthesizedAnswerAndStoresItsTrace`'s expected `traceStepCount` and kind sequence -- keeps the end-to-end web test aligned with the new trace shape.

**Acceptance Criteria:**
- Given `AnswerDriftSearch` spawns 2+ sub-questions from tied top-scoring Communities, when it runs, then the trace contains one `SUB_QUESTION_SPAWNED` step (carrying that sub-question's text and parent Community id) plus that branch's own Local Search steps, for **every** spawned sub-question — not only the one that ends up grounded.
- Given at least one sub-question was spawned, when `AnswerDriftSearch` finishes, then exactly one `SYNTHESIS` step is appended as the last step of the trace, and the returned answer matches that step's `label`.
- Given the Community pass yields zero viable sub-questions or zero Communities exist, when `AnswerDriftSearch` runs, then no `SUB_QUESTION_SPAWNED` or `SYNTHESIS` step ever appears (both Story 7.2 `noAnswer` outcomes are unchanged).
- Given the same corpus and question as before this story, when a DRIFT query is submitted via `CorpusController`, then the final synthesized `answer` text is unchanged from Story 7.2 — only the trace's step count and shape grow to reflect every branch.

## Spec Change Log

## Review Triage Log

## Design Notes

**Why remove the early `break`, and why is this in Story 7.3's scope, not scope creep:** Story 7.2 shipped a "first grounded hop wins, and stops looking" optimization so the trace exactly matched the surfaced answer (its own review triage log even records this as an intentional fix). Epics.md's Story 7.3 AC requires "each sub-question's own Local Search steps are appended in order beneath it" for every spawned sub-question, and Story 7.4's AC requires the replay UI to render "a fan of branch lines to each sub-question node" — both are impossible if only the first grounded branch is ever traced. This story corrects that: the re-ranking *decision* (first grounded hop wins) is preserved exactly, but the *trace* now always reflects every branch that was spawned, which is what "capture the trace" in this story's title actually means.

**Zipping sub-questions to their parent Community by index:** `LlmPort.deriveDriftSubQuestions(question, candidates)` returns a `List<String>` with no explicit per-question parent reference. The default implementation always returns one sub-question per candidate, in the same order, so index-based zipping (`subQuestions.get(i)` ↔ `candidates.get(i)`) is exact for the shipped default. A custom `LlmPort` returning a different-length list degrades gracefully: any sub-question index beyond `candidates.size() - 1` gets `identifier = ""` on its `SUB_QUESTION_SPAWNED` step (a known, accepted limitation — matches this codebase's existing "no defensive guard against custom-port misbehavior" convention noted in Story 7.2's review).

## Verification

**Commands:**
- `mvn -pl graphrag-core -am test -Dtest=AnswerDriftSearchTest` -- expected: all pass, including the updated step-sequence assertions and the new `SUB_QUESTION_SPAWNED` identifier/label test.
- `mvn -pl graphrag-web -am test -Dtest=CorpusControllerDriftSearchTest,CorpusControllerTest,DriftModeChoiceUiTest` -- expected: all pass, including the updated `traceStepCount`/kind-sequence assertions.
