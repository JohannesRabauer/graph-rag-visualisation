---
title: 'Fix Retrieval Trace Replay Not Rendering or Highlighting'
type: 'bugfix'
created: '09-27-2026'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '8fc83517164c1730851a53af0cc225d08791a379'
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** GitHub #32 reports the Replay scrubber sometimes doesn't render after an answer, and playback no longer visibly highlights/traverses the graph. A thorough investigation (tracing `replay.js`'s render/gating logic, `upload.js`'s answer-render wiring, and every Epic 9/10 commit that touched adjacent code) found no currently-reproducible regression: the three existing Replay Playwright tests (`ReplayRelationshipEdgeHighlightUiTest`, `ReplayCommunityHullVisibilityUiTest`, `DriftTreeReplayUiTest`) all pass unmodified today. One real fragility was identified though: `replay.js`'s init guard (`if (!scrubber || !stepBackButton || !playPauseButton || !stepForwardButton) { return; }`, `replay.js:27`) aborts the entire module silently with no console error if any of those four elements is ever missing from the DOM — this is the one mechanism that could produce exactly "scrubber never appears" with zero diagnostics, and it currently has no test coverage, nor coverage for Replay working correctly after the two most structurally invasive recent changes (Story 10.4's load-new-corpus reset flow, Story 11.1's viewport-resize handling).

**Approach:** Since no live regression is reproducible, this story is verification-and-hardening, not a speculative rewrite: add regression coverage for Replay working after a corpus reset (Story 10.4's flow) and after a viewport resize (Story 11.1's `ResizeObserver`), and make the silent-abort guard in `replay.js` fail loudly (a `console.error`) instead of silently, so a future regression of this exact shape is immediately diagnosable instead of reproducing #32's original "no error, just doesn't work" symptom.

## Boundaries & Constraints

**Always:**
- Verify claims against the actual running app via Playwright tests, not static reasoning alone — this story's own investigation already showed static review can miss real bugs (see Story 11.2's `.canvas` finding) and can also wrongly assume a bug when none exists.
- Keep the `if (!scrubber || ...) return;` guard's early-return behavior itself unchanged — only add a diagnostic `console.error` before returning, do not change when it returns.
- Reuse the existing Replay test pattern (open a corpus, ask a question, click `.replay-cta`, step/play, assert `graph-canvas.js` highlight classes) already established in `ReplayRelationshipEdgeHighlightUiTest`/`ReplayCommunityHullVisibilityUiTest`.

**Never:**
- Do not modify `replay.js`'s core state machine, `graph-canvas.js`/`drift-tree.js`/`vector-space.js`'s highlight rendering, or any Cytoscape stylesheet ordering — investigation found these correct; do not touch working code with no failing test justifying a change.
- Do not file or speculatively patch anything about the DRIFT (Epic 7) or Vector (Epic 8) replay renderers beyond confirming (via a passing test) that a shared regression, if any existed, would be visible the same way — no code changes to those renderers.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Replay after corpus reset | User loads a Corpus, asks a question, replays it, then uses "Start over with a new corpus" (Story 10.4) to load a second Corpus and asks another question | The Replay CTA appears and opens correctly for the second Corpus's answer, with working step/play highlighting | No error expected |
| Replay after viewport resize | User loads a Corpus, asks a question, resizes the browser viewport (triggering Story 11.1's `ResizeObserver`), then replays the trace | Replay still opens and highlights correctly; the resize does not tear down or break the scrubber/highlight state | No error expected |
| Scrubber elements missing from DOM | One of `#replay-scrubber`/`#replay-step-back`/`#replay-play-pause`/`#replay-step-forward` is absent (simulated via a direct DOM removal in a test) | `replay.js`'s init guard logs a `console.error` naming which element is missing, instead of failing silently | Diagnostic error logged, no thrown exception |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/static/js/replay.js:27` -- the silent-abort init guard to harden with a diagnostic `console.error`
- `graphrag-web/src/main/resources/static/js/replay.js:297-327` -- `renderStep()`, dispatches to `GraphCanvas.highlightStep`/`DriftTree.highlightStep`/`VectorSpace.highlightStep` depending on trace kind (confirmed correct, no change needed)
- `graphrag-web/src/main/resources/static/js/upload.js` -- `resetToIdleState()` (Story 10.4) and `appendAnswer()`'s Replay CTA wiring (~line 965-998); read-only reference for the new reset-then-replay test
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` -- `watchContainerResize` (Story 11.1); read-only reference for the new resize-then-replay test
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ReplayRelationshipEdgeHighlightUiTest.java` -- existing pattern to extend/follow for the new tests (open corpus, ask question, click `.replay-cta`, step/play, assert highlight classes)
- `graphrag-web/src/test/java/com/graphraglens/web/ui/LoadNewCorpusUiTest.java` -- existing pattern for driving the Story 10.4 reset flow in a test

## Tasks & Acceptance

**Execution:**
- `graphrag-web/src/main/resources/static/js/replay.js` -- change the init guard at line 27 to log `console.error('[replay] missing required DOM element(s): ...')` naming which of the four elements is null/missing, before its existing early return -- turns a silent, undiagnosable failure mode into an immediately visible one, directly addressing the "scrubber sometimes doesn't render" symptom's most likely mechanism
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ReplayAfterCorpusResetUiTest.java` -- new test: load a corpus, ask a question, open Replay and confirm it works, use the "Start over with a new corpus" flow (per `LoadNewCorpusUiTest`'s pattern) to load a second corpus, ask a question, and assert the Replay CTA appears and opens correctly with working step highlighting for this second corpus -- covers the one untested intersection between Story 10.4's most invasive recent reset logic and Replay
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ReplayAfterViewportResizeUiTest.java` -- new test: load a corpus, ask a question, resize the viewport (per `MainScreenLayoutUiTest`'s pattern), then open Replay and confirm step/play highlighting still works -- covers the one untested intersection between Story 11.1's `ResizeObserver` and Replay

**Acceptance Criteria:**
- Given an answer has just been generated with a captured Retrieval Trace, when the answer renders, then the Replay scrubber appears (verified via the existing and new Playwright tests, all passing)
- Given the Replay scrubber is shown, when stepping through or playing the trace, then the graph canvas visibly transitions node/edge visual states and the Step badge updates in step (verified via existing tests, unchanged)
- Given one of the scrubber's required DOM elements is missing, when `replay.js` initializes, then a diagnostic console error is logged instead of a silent no-op

## Spec Change Log

## Review Triage Log

### 09-27-2026 — Review pass
- verdicts: 12 findings — high 0, medium 1, low 8, false 2, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind-hunter) the diff should make explicit (commit message) that this is a diagnostics-and-regression-coverage change, not a behavioral rendering fix, since no reproducible regression was found — otherwise reviewers may assume a real rendering bug was fixed. Fix: the finalize commit message states this explicitly.
  - `[medium]` `[patch]` (verification-gap, pre-verified) the new `console.error` diagnostic in `replay.js`'s init guard — the one concrete, spec-mandated behavioral change in this diff — has zero test coverage; nothing removes a required scrubber element from the DOM or asserts on console output. Fix: added a new test that strips one of the four guarded elements and asserts the correct `console.error` fires via Playwright's console-message capture.
  - `[medium]` `[patch]` (blind-hunter) same gap ("no test exercises the newly added logging path itself") — grouped with the row above.
  - `[medium]` `[patch]` (blind-hunter) same gap again ("neither new test asserts on the content of the console diagnostic") — grouped with the row above.
  - `[medium]` `[patch]` (intent-alignment) the spec's own I/O matrix promises this scenario be "verified via the existing and new Playwright tests," but no test in the diff covers it — grouped with the row above; same root cause.
  - `[low]` `[patch]` (blind-hunter) `assertReplayHighlightsAStepOnTheCanvas()`-equivalent logic is duplicated near-verbatim across the two new test files (and reportedly two existing ones). Fix: hoisted the shared step-forward-until-highlighted helper into `UiTestSupport`.
  - `[low]` `[patch]` (blind-hunter) `ReplayAfterViewportResizeUiTest` uses fully-qualified `assertThat` names instead of the static imports used in the sibling file added in the same diff. Fix: added the missing static imports for consistency.
  - `[low]` `[patch]` (blind-hunter) `ReplayAfterViewportResizeUiTest` waits a fixed `page.waitForTimeout(300)` after resizing instead of polling an observable condition — a classic flaky-test pattern. Fix: replaced with a `page.waitForFunction` polling condition, matching the pattern already used in `MainScreenLayoutUiTest`.
  - `[low]` `[reject]` (blind-hunter) the hardcoded chat question `"Tell me about Irene Adler."` is duplicated across the two new test files — verified real but this exact duplication is already a pre-existing, widespread pattern across many other Replay/chat UI tests in this suite, not something introduced or worsened meaningfully by this diff alone; a suite-wide extraction is out of scope for this bugfix.
  - `[false]` `[reject]` (blind-hunter) "no update to the spec document to record the investigation's conclusion" — refuted: the spec file (`spec-11-3-fix-retrieval-trace-replay-not-rendering.md`) already documents this conclusion in its own Intent and Design Notes sections; it's a companion artifact committed alongside the code diff at finalization, not part of the code change under review, consistent with prior stories' triage precedent.
  - `[false]` `[reject]` (intent-alignment) "if the issue's premise (a real functional regression) is accurate, this diff doesn't address it" — not a defect: this is the intended, already-justified verification-first approach documented in the spec's own Design Notes, following the exact same correct-methodology pattern established in Story 11.2's triage (verify before speculatively patching).
  - `[false]` `[reject]` (intent-alignment) "the premise is unverifiable from the diff alone" — same by-design methodology observation as the row above; grouped together.

## Design Notes

This story deliberately does not "fix" anything in the replay rendering path itself, because the investigation found nothing broken there — three existing tests already exercise exactly the behavior GitHub #32 describes as missing, and all pass. The value this story adds is: (1) a diagnostic breadcrumb for the one silent-failure mechanism that could produce #32's exact symptom in the future, and (2) regression coverage for the two most structurally invasive recent changes (corpus reset, viewport resize) that hadn't been tested against Replay before, closing the most plausible-but-unconfirmed risk areas the investigation surfaced.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am -Dtest=ReplayAfterCorpusResetUiTest,ReplayAfterViewportResizeUiTest,ReplayRelationshipEdgeHighlightUiTest,ReplayCommunityHullVisibilityUiTest,DriftTreeReplayUiTest -Dsurefire.failIfNoSpecifiedTests=false test` -- expected: all pass
- `mvn -q -B clean install` -- expected: full reactor green, no regressions

## Auto Run Result

**Summary:** GitHub #32 could not be reproduced: all three pre-existing Replay Playwright tests already passed unmodified, and a full trace of `replay.js`/`upload.js`/the Epic 9-10 commit history found no rendering or highlighting regression. This story is diagnostics-and-hardening only, not a behavioral rendering fix: `replay.js`'s one silent-failure mechanism (its init guard aborting with no console output if a required scrubber element is missing) now logs a diagnostic naming the missing element, and new regression tests cover Replay working correctly after the two most structurally invasive recent changes (Story 10.4's corpus-reset flow, Story 11.1's viewport-resize handling) that had no prior coverage against Replay.

**Files changed:**
- `graphrag-web/src/main/resources/static/js/replay.js` -- init guard now logs `console.error('[replay] missing required DOM element(s): ...')` naming the specific missing element(s) before its unchanged early return
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ReplayMissingScrubberElementUiTest.java` -- new file (patched in): parameterized test over all four guarded elements, asserting the diagnostic console error fires and names the removed element
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ReplayAfterCorpusResetUiTest.java` -- new file: Replay works correctly for a second Corpus loaded via Story 10.4's reset flow
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ReplayAfterViewportResizeUiTest.java` -- new file: Replay works correctly after a post-load viewport resize (waits on an observable resize-complete condition, patched in from an initial fixed sleep)
- `graphrag-web/src/test/java/com/graphraglens/web/ui/UiTestSupport.java` -- gained a shared `assertReplayHighlightsAStepOnTheCanvas()`-equivalent helper (patched in, de-duplicating logic from the two new test files)

**Review findings breakdown:** 12 findings from 4 reviewer layers, grouped into 8 root causes.
- Patched (5 groups, 8 member findings): the diagnostic `console.error` path having zero test coverage (medium, the one behavioral change this diff makes); duplicated step-highlight assertion logic across new tests (low); an assertion-style inconsistency (low); a fixed-sleep flaky-test pattern (low); and a commit-message clarification that this is diagnostics/coverage, not a rendering fix (low).
- Rejected (3 groups, 4 member findings): a pre-existing, widespread test-data duplication pattern out of scope for this bugfix (low); a claim that the investigation's conclusion isn't documented, refuted by the spec's own Intent/Design Notes sections (false); two intent-alignment observations about the verification-first methodology itself, which is the deliberate, already-justified approach (false, x2, following the same precedent as Story 11.2).

**Verification performed:** `mvn -q -B -pl graphrag-web -am -Dtest=ReplayMissingScrubberElementUiTest,ReplayAfterCorpusResetUiTest,ReplayAfterViewportResizeUiTest,ReplayRelationshipEdgeHighlightUiTest,ReplayCommunityHullVisibilityUiTest,DriftTreeReplayUiTest test` -- 11/11 pass, both before and after the patch pass; `mvn -q -B clean install` -- full reactor green (exit 0), both before and after the patch pass.

**Follow-up review recommended:** `false` -- only one medium-verdict entry was patched this pass (the diagnostic test-coverage gap), and no high-verdict entry was patched; the follow-up-review trigger rule is not met.

**Residual risks:** none rated high or medium. GitHub #32's original symptom, if it was ever real, remains unreproduced and therefore unconfirmed-fixed by this story — the story's diagnostic logging and new regression coverage reduce the risk of a *silent* recurrence of this class of failure, but do not rule out an as-yet-unidentified cause outside the areas investigated.
