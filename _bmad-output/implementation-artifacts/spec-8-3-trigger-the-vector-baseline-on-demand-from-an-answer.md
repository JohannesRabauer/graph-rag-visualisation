---
title: 'Trigger the Vector Baseline On Demand from an Answer'
type: 'feature'
created: '2026-09-20'
status: 'done'
route: 'oneshot'
baseline_revision: '80e99b92c4d6b089350ffd18104fbd922b728581'
baseline_commit: '80e99b92c4d6b089350ffd18104fbd922b728581'
review_loop_iteration: 1
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The `VECTOR` query mode exists in the backend (Story 8-2) but there is no way for a user to trigger it from the UI. The UI also has no canvas tab to house the Vector Space view.

**Approach:** Add a "Compare with Vector Search" CTA button below each GraphRAG answer (LOCAL/GLOBAL/DRIFT) — clicking it fires `POST /api/corpora/{id}/query` with `mode: "VECTOR"` for the same question, then displays the vector answer in a new message. Simultaneously, add a tab bar above the graph-stage with two tabs: "Knowledge Graph" (always present, default active) and "Vector Space" (revealed only once the user first triggers a vector comparison). The Vector Space tab shows a placeholder panel with the vector baseline answer text and a note that the full embedding-space scatter plot arrives in Story 8-5. Both the Compare CTA and the Vector Space tab must be keyboard-reachable.

## Boundaries & Constraints

**Always:**
- The Compare CTA renders on every answer message that has a `data-question` and `data-corpus-id` attribute (both set by `appendAnswer`). It appears below the Replay CTA (if present) or below the answer text (if no Replay CTA). It is a `<button type="button" class="compare-cta">` reading "↻ Compare with Vector Search".
- `appendAnswer` must set `message.dataset.question = question` and `message.dataset.corpusId = activeCorpusId` so the Compare CTA handler can retrieve them via `event.target.closest('.message.answer')`.
- The Compare CTA is shown on LOCAL, GLOBAL, and DRIFT answers. It is not shown on answers that are themselves VECTOR answers (no nesting — a VECTOR answer does not get its own Compare CTA).
- Clicking the Compare CTA: (1) disables itself with `aria-busy="true"` and sets text to "Comparing…", (2) POSTs to `/api/corpora/{corpusId}/query` with `{question, mode: "VECTOR"}`, (3) on success: appends a VECTOR answer message (same `appendAnswer` path, `mode="VECTOR"`) and reveals the Vector Space tab, (4) on failure: shows the error banner and re-enables the button, (5) always re-enables the button in `finally`.
- The canvas tab bar (`#canvas-tab-bar`) sits at the top of `.graph-stage`, above `#graph-canvas` and all other overlays. It contains two tab buttons: `#tab-knowledge-graph` (text "Knowledge Graph") and `#tab-vector-space` (text "Vector Space"). The tab bar itself is hidden (`hidden` attribute) until a corpus is loaded — it appears at the same time the graph canvas becomes visible. The Vector Space tab is also hidden (`hidden` attribute on the tab button itself) until the first vector comparison is triggered; once revealed it stays visible.
- The active tab is indicated by `aria-selected="true"` on the active tab button and `aria-selected="false"` on the inactive one. Switching tabs: Knowledge Graph tab shows `#graph-canvas` area (existing behavior), Vector Space tab shows `#vector-space-panel`. Tab buttons use `role="tab"` and `tabindex` for keyboard navigation (active tab `tabindex="0"`, inactive `tabindex="-1"`), following the ARIA tabs pattern.
- `#vector-space-panel` is a new `<div>` inside `.graph-stage`, initially hidden. It shows the most recent vector baseline answer text in a readable layout. No scatter plot yet — Story 8-5 owns that. A brief label "Vector Space — answer ready" serves as the eyebrow; the answer text is displayed below.
- CSS for `.compare-cta`, `.canvas-tab-bar`, `.canvas-tab`, `#vector-space-panel` follows the existing design token vocabulary (no new color variables).

**Never:**
- Do not add a VECTOR option to the mode radio group — `VECTOR` is on-demand only, not a standing search mode.
- Do not show the Compare CTA on VECTOR answer messages themselves.
- Do not auto-trigger the vector comparison — it is strictly on-demand.
- Do not break existing replay, drift-tree, or graph-canvas behavior.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| User clicks Compare CTA on a LOCAL answer | Valid corpus, vector index ready | VECTOR answer appended to chat; Vector Space tab revealed | N/A |
| User clicks Compare CTA when vector index not ready | `noAnswer: true` from API | VECTOR "no-answer" message appended (using `reason`); Vector Space tab still revealed | N/A — handled via normal appendAnswer path |
| User clicks Compare CTA twice | Second click while first is in flight | Button disabled during fetch; two separate VECTOR answers on completion | N/A |
| API returns an error | Network failure or 4xx/5xx | Error banner shown; Compare CTA re-enabled | Re-enable in `finally` |
| Tab switch: Knowledge Graph → Vector Space | Vector Space tab previously revealed | `#graph-canvas` area hidden, `#vector-space-panel` shown | N/A |
| Tab switch: Vector Space → Knowledge Graph | Active tab is Vector Space | `#vector-space-panel` hidden, `#graph-canvas` area shown | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-web/src/main/resources/templates/index.html` — add `#canvas-tab-bar` div (with two tab buttons) at top of `.graph-stage`; add `#vector-space-panel` div inside `.graph-stage` (initially hidden); set `data-question` and `data-corpus-id` are set in JS, HTML structure unchanged
- `graphrag-web/src/main/resources/static/css/instrument.css` — add `.canvas-tab-bar`, `.canvas-tab`, `#vector-space-panel`, `.compare-cta` rules
- `graphrag-web/src/main/resources/static/js/upload.js` — update `appendAnswer` to set `dataset.question`/`dataset.corpusId`, add Compare CTA button for non-VECTOR answers; add delegated `click` handler for `.compare-cta`; add `revealVectorSpaceTab()` helper; wire tab switching for `#tab-knowledge-graph` / `#tab-vector-space`; show/hide `#canvas-tab-bar` alongside existing canvas-show logic
- `graphrag-web/src/test/java/com/graphraglens/web/ui/VectorBaselineTriggerUiTest.java` (new) — Playwright test: load demo dataset, ask a LOCAL question, wait for `.compare-cta`, click it, assert VECTOR answer message appears and `#tab-vector-space` is visible; assert tab switching works

## Tasks & Acceptance

**Execution:**
- [x] `index.html` — add tab bar and vector-space panel elements
- [x] `instrument.css` — add CSS for tab bar, compare CTA, vector space panel
- [x] `upload.js` — update `appendAnswer`, add Compare CTA handler, tab switching
- [x] `VectorBaselineTriggerUiTest.java` (new) — Playwright coverage

**Acceptance Criteria:**
- Given a GraphRAG answer (LOCAL/GLOBAL/DRIFT) is displayed, when the user sees the answer message, then a "↻ Compare with Vector Search" button is present and keyboard-focusable.
- Given the Compare CTA is clicked, when the API returns a VECTOR answer, then a new VECTOR answer message is appended to chat and the "Vector Space" tab is revealed in the canvas tab bar.
- Given the Vector Space tab is revealed, when the user clicks it, then `#vector-space-panel` is shown and the Knowledge Graph canvas is hidden; clicking "Knowledge Graph" reverses this.
- Given a VECTOR answer message, when the user looks for a Compare CTA, then no Compare CTA is present on that message (no nesting).
- Given the canvas tab bar is present, when the user tabs to the "Vector Space" tab button, then it is keyboard-reachable and activatable.

## Implementation Notes

## Spec Change Log

- 2026-09-20 — spec authored and implementation delivered directly by the user (Compare CTA, canvas tab bar, Vector Space panel, tab-switching logic, initial Playwright coverage) outside this session; picked up here for review/finalization against the frozen intent-contract.
- 2026-09-21 — review pass found and patched two tab-exclusivity/mislabeling gaps (Replay/DriftTree bypassing the Vector Space tab; VECTOR trace-replay captions falling through to "matched entity") plus a fallback-text inconsistency and an auto-switch-on-every-comparison UX defect; added four Playwright tests.

## Review Triage Log

### 2026-09-21 — Review pass
- verdicts: 10 findings — high 0, medium 2, low 2, false 3, reject/defer 2, subsumed 1
- findings:
  - `[medium]` `[patch]` blind-hunter + edge-case-hunter + verification-gap (three-way convergence): `replay.js`'s `open()` set `scrubber.hidden = false` unconditionally, bypassing `switchCanvasTab`'s tab-exclusivity tracking — opening a Replay or DriftTree trace while the Vector Space tab was active left both `#vector-space-panel` and `#replay-scrubber`/`#drift-tree-container` visible simultaneously, with `aria-selected` left pointing at Vector Space. Fixed: `open()` now calls `window.CanvasTabs.switchTo('knowledge-graph')` (new hook exposed by `upload.js`) before revealing the scrubber; this also fixes DriftTree since `DriftTree.build()` always precedes `open()` on the same code path. Verified via new test `openingReplayWhileVectorSpaceTabIsActiveSwitchesBackToKnowledgeGraphTab`.
  - `[medium]` `[patch]` blind-hunter + edge-case-hunter (independently found the same root cause): `replay.js`'s `captionFor()` had no case for the `VECTOR_QUERY_EMBEDDED`/`VECTOR_CHUNK` `RetrievalStep.Kind` values introduced in Story 8-2, silently falling through to the "matched entity" default — reachable for the first time because Story 8-3 is what lets a `mode='VECTOR'` answer reach chat's pre-existing, unconditional Replay CTA block. Fixed: added `'embedded query'`/`'retrieved chunk'` cases before the default. Verified via new test `replayingAVectorAnswerLabelsStepsAsEmbeddedQueryAndRetrievedChunkNotMatchedEntity`.
  - `[low]` `[patch]` edge-case-hunter: `revealVectorSpaceTab(answerText)` passed the raw (possibly falsy) `answerText` straight to `vectorSpaceAnswer.textContent`, defaulting to `''`, while the chat message's `appendAnswer` defaults to `'No answer was returned.'` — an inconsistent empty-answer fallback between the two surfaces. Fixed: the Compare CTA success handler now resolves `answerText` the same way `appendAnswer` does before calling `revealVectorSpaceTab`.
  - `[low]` `[patch]` intent-alignment (Reading A vs Reading B analysis — a genuinely defensible-either-way design point, patched via a minimal guard): `switchCanvasTab('vector-space')` fired unconditionally on every successful Compare click, not just the first reveal, yanking the user back to Vector Space even if they had manually switched to Knowledge Graph after the first comparison. Fixed: `revealVectorSpaceTab` now only auto-switches when the tab was previously `hidden` (first reveal). Verified via new test `repeatedComparisonsDoNotForceTheUserBackToVectorSpaceTabOnceRevealed`.
  - `[false]` blind-hunter: claimed an `aria-hidden` inconsistency on the inactive tab panel is an accessibility bug. Verified false — the native `hidden` attribute alone removes an element from the accessibility tree regardless of any `aria-hidden` value, so no assistive-technology-facing consequence exists.
  - `[false]` blind-hunter: claimed the entity-detail-panel could get out of sync when switching away from Knowledge Graph mid-interaction. Verified false — `#graph-canvas` is also hidden while the Vector Space tab is active, so no new node-click interaction can occur; the panel simply (correctly) preserves its exact prior state across the tab switch.
  - `[false]` edge-case-hunter: claimed `#graph-legend` could visually overlap the vector-space-panel. Verified false — traced `renderLegend()`'s only unconditional caller (`init()`) to ingestion-time graph construction (always completes before any Compare interaction is possible); its other two callers are gated behind the community-toggle checkbox, itself hidden while the Vector Space tab is active.
  - `[low]` `[defer]` verification-gap: no dedicated UI-level Playwright test for the vector-index-not-ready (`noAnswer`) response path. Deferred — this exact contract (`noAnswer: true`, `reason` present, `mode`/`answer` absent) is already fully unit-tested at the controller level in `CorpusControllerVectorBaselineTest.java` (Story 8-2), and the UI's existing `||` fallback logic was traced to confirm it already handles this response shape correctly; a dedicated UI simulation was judged low-value/hard-to-simulate-reliably relative to the existing coverage.
  - `[low]` `[reject]` intent-alignment: missing Compare CTA tests specifically for GLOBAL/DRIFT modes (only LOCAL is exercised). Rejected — the CTA's rendering condition (`activeMode !== 'VECTOR' && question && activeCorpusId`) has no mode-specific branching, so LOCAL coverage is structurally representative; not worth duplicating.
  - `[subsumed]` verification-gap: raised the tab-exclusivity gap independently with the same root cause as the blind-hunter/edge-case-hunter finding above — folded into that single patch rather than tracked separately.
  - Also added, beyond the specific findings above: an API-error + Compare-CTA-re-enable test (`aFailedComparisonShowsTheErrorBannerAndReEnablesTheCompareCTA`, following the existing `page.route(...)` interception pattern from `DriftTreeReplayUiTest.java`) and a keyboard-reachability test (`vectorSpaceTabIsKeyboardReachableFromTheKnowledgeGraphTab`, ArrowRight/ArrowLeft roving-tabindex) — both closing pre-existing coverage gaps in the story's own AC ("keyboard-reachable") rather than review-layer findings per se.

## Design Notes

**Why `window.CanvasTabs = { switchTo: switchCanvasTab }` instead of having `replay.js` reach into `upload.js`'s DOM directly:** the codebase's existing convention for cross-module coordination is each module exposing itself as `window.ModuleName = { methodName: fn }` (`window.Replay`, `window.DriftTree`, `window.GraphCanvas` already follow this) rather than modules poking at each other's internal elements — this keeps `switchCanvasTab`'s tab-exclusivity bookkeeping (the single source of truth for which tab is active) inside `upload.js`, with `replay.js` only calling the one sanctioned entry point.

**Why the fix lives in `replay.js`'s shared `open()` rather than in `drift-tree.js` separately:** `open()` is the single entry point both the Retrieval Trace scrubber and (indirectly, via the trace-fetch success handler calling `DriftTree.build(steps)` immediately before `open()`) the DriftTree view funnel through — one fix in `open()` covers both surfaces without duplicating the tab-switch call in `drift-tree.js`.

## Auto Run Result

**Summary:** Story 8-3 (Compare CTA, canvas tab bar, Vector Space panel) was implemented directly by the user outside this session. This session picked it up mid-flight: verified the implementation against the frozen intent-contract and Code Map, ran a full 4-layer automated review (blind-hunter, edge-case-hunter, verification-gap, intent-alignment) cross-checked against the actual source, patched 2 medium and 2 low findings (tab-exclusivity bypass for Replay/DriftTree, VECTOR caption mislabeling, answer-text fallback inconsistency, and an auto-switch-every-time UX defect), added 4 new Playwright tests closing both review-found gaps and pre-existing AC coverage gaps (API-error re-enable, keyboard reachability), and removed a stray build-log artifact (`test-out.txt`) that had been accidentally committed.

**Files changed:**
- `graphrag-web/src/main/resources/static/js/upload.js` — exposed `window.CanvasTabs = { switchTo: switchCanvasTab }`; `revealVectorSpaceTab` now only auto-switches tabs on first reveal; Compare CTA success handler resolves the answer-text fallback consistently with `appendAnswer`.
- `graphrag-web/src/main/resources/static/js/replay.js` — `open()` now switches back to the Knowledge Graph tab before revealing the scrubber; `captionFor()` gained `VECTOR_QUERY_EMBEDDED`/`VECTOR_CHUNK` cases.
- `graphrag-web/src/test/java/com/graphraglens/web/ui/VectorBaselineTriggerUiTest.java` — added 4 new tests (Replay/Vector-tab exclusivity, no-yank-back-on-repeat-comparison, error-banner + CTA re-enable, keyboard reachability) and 1 caption-labeling test.
- `test-out.txt` — removed (stray build-log artifact, not part of the intended change set).
- `_bmad-output/implementation-artifacts/spec-8-3-trigger-the-vector-baseline-on-demand-from-an-answer.md` — this spec (frontmatter, change log, triage log, design notes, this section).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — 8-2 and 8-3 entries corrected/updated to `done`.

**Review findings breakdown (10 findings, 4 layers):**
- Patched (4: 2 medium, 2 low): Replay/DriftTree tab-exclusivity bypass; VECTOR trace-replay caption mislabeling; Vector Space answer-text fallback inconsistency; auto-switch-on-every-comparison UX defect.
- Deferred (1, low): dedicated UI-level test for the vector-index-not-ready (`noAnswer`) path — already covered at the controller level (Story 8-2).
- Rejected (1, low): missing Compare CTA tests for GLOBAL/DRIFT specifically — CTA rendering condition has no mode-specific branching, LOCAL coverage is representative.
- Verified false (3): aria-hidden inconsistency (native `hidden` alone suffices), entity-detail-panel state drift (no new interactions possible while hidden), graph-legend overlap (unreachable given call-graph gating).
- Subsumed (1): a duplicate root-cause finding folded into the tab-exclusivity patch above.

**Follow-up review recommendation:** `false` — no `high`-severity findings; all `medium`/`low` patchable findings were fixed and covered by new tests; the one deferred item is low-severity and already covered at a different test layer.

**Verification performed:**
- Independently read every changed file's diff against the baseline commit (`80e99b92c4d6b089350ffd18104fbd922b728581`) and the spec's frozen Code Map/Boundaries before running any review layer.
- Cross-verified every one of the 4 review layers' findings directly in the source (`upload.js`, `replay.js`, `drift-tree.js`, `graph-canvas.js`, `index.html`) rather than trusting reviewer claims at face value — this is what surfaced the 3 `false` verdicts.
- Ran the full `graphrag-web` test suite before and after patching: 11 test classes, 58 tests, 0 failures/0 errors both times, confirmed via `target/surefire-reports/*.txt` (not console tail — console tail shows only benign Spring Boot startup/shutdown noise and expected `AsyncRequestTimeoutException` warnings from the long-poll-style test harness).
- `VectorBaselineTriggerUiTest` specifically: 8 tests (3 pre-existing + 5 new), 0 failures/0 errors.

**Residual risks:** none identified beyond the one explicitly deferred low-severity item (already mitigated by existing controller-level coverage).

## Verification

**Commands:**
- `mvn "-pl" "graphrag-web" "-am" "test" "-Dtest=VectorBaselineTriggerUiTest" "-Dsurefire.failIfNoSpecifiedTests=false"` -- expected: all pass
