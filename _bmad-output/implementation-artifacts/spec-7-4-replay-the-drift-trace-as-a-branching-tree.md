---
title: 'Replay the DRIFT Trace as a Branching Tree'
type: 'feature' # feature | bugfix | refactor | chore
created: '2026-09-20'
status: 'done' # draft | ready-for-dev | in-progress | in-review | done | blocked
baseline_revision: '390a990d53e9f5f1e13b31c448fec4ed4adc1672'
baseline_commit: '390a990d53e9f5f1e13b31c448fec4ed4adc1672'
review_loop_iteration: 0 # incremented by step-04 before each review loopback
followup_review_recommended: true # set by step-04 on status: done — true if the LLM decided another review pass is worthwhile
context: []
warnings: ['oversized']
deferred:
  - summary: >-
      Replay's showFetchError() clears the new DriftTree overlay but not window.GraphCanvas's
      node/edge highlights, so a failed trace re-fetch can leave stale highlights on the canvas.
    evidence: |-
      Confirmed against baseline commit 390a990: showFetchError() never called
      GraphCanvas.clearStepHighlights() before this story either — closeReplay() does, but
      showFetchError() does not, in both the pre- and post-story versions. This story only added
      DriftTree.clear() to showFetchError(); it did not introduce or worsen the gap, so it is
      pre-existing and out of this story's scope.
    location: >-
      graphrag-web/src/main/resources/static/js/replay.js:129-141 (showFetchError)
    severity: medium

---

<intent-contract>

## Intent

**Problem:** A DRIFT answer's Replay currently plays back through the existing linear step scrubber exactly like Local/Global Search — it never shows DRIFT's actual community-pass → fanned-sub-questions → convergence shape, and the two new Story 7.3 step kinds (`SUB_QUESTION_SPAWNED`, `SYNTHESIS`) render with the wrong generic caption ("matched entity") and don't resolve to anything on the main Cytoscape canvas.

**Approach:** Add a dedicated branching-tree view (`drift-tree.js` + new markup/CSS) that only activates for a DRIFT trace (one containing `SUB_QUESTION_SPAWNED` steps): one community-pass root, a fan of branch lines to one node per spawned sub-question (in spawn order), and one converging final node. The **existing** transport controls (step-forward/back, play/pause, tick-drag) and their step-index model in `replay.js` are reused completely unchanged — only what gets rendered in response to the current step index changes. `replay.js`'s caption text and `graph-canvas.js`'s per-step Cytoscape highlighting also gain explicit handling for the two new kinds, closing the gap Story 7.3's review deferred to this story.

## Boundaries & Constraints

**Always:**
- The tree is built from the same flat, ordered `RetrievalStep[]` Story 7.3 already produces — no backend, `AnswerDriftSearch`, or `RetrievalStep` changes. A branch's extent is delimited purely by step position: from its `SUB_QUESTION_SPAWNED` step (inclusive) up to (exclusive) the next `SUB_QUESTION_SPAWNED` step, or the `SYNTHESIS` step, or the end of the trace.
- `replay.js` keeps its existing step-index state machine (`currentIndex`, `goToStep`, `stepForward/Back`, `togglePlay`, tick-drag) completely unchanged — stepping through a DRIFT trace still advances one `RetrievalStep` at a time, in the exact same order that arrived from the backend (community pass steps, then each branch's own steps in spawn order, then the synthesis step). "Community pass → each branch in spawn order → convergence" is achieved by the tree simply mapping each existing step index to the tree region it belongs to, not by any new traversal logic.
- A trace with zero `SUB_QUESTION_SPAWNED` steps (Local/Global, or the two DRIFT `noAnswer` shapes) never shows the tree — the tree container stays `hidden` and the existing single Cytoscape canvas behavior is 100% unaffected, byte-for-byte, from before this story.
- When the tree is visible, the existing Cytoscape canvas keeps rendering alongside it (per `AnswerLocalSearch`'s ENTITY/RELATIONSHIP steps inside each branch) — this story does not hide, replace, or resize the main canvas.
- Visual identity uses the existing design tokens already present in `instrument.css` (`--drift`, `--drift-soft`, `--line-strong`, `--ink-600`, `--active`, `--active-soft`) — no new color values invented. Root: `--drift` border, always. Final/convergence node: `--drift-soft` background + `--drift` border/text, always. A sub-question node not yet reached: `--line-strong` border + `--ink-600` text (default). The currently-active sub-question node: `--active` border + `--active-soft` background. A branch fully passed keeps its default styling plus a small "✓ resolved" caption. A branch line for a not-yet-reached branch renders dashed (matching the existing `edge-upcoming` dashed convention used on the main canvas); once the branch starts (its `SUB_QUESTION_SPAWNED` step is reached), its line becomes solid and stays solid.
- `replay.js`'s `captionFor()` gains two more cases: `SUB_QUESTION_SPAWNED` → `"spawned sub-question"`, `SYNTHESIS` → `"synthesized answer"` (same `verb + label` sentence shape as the existing three cases).
- `graph-canvas.js`'s `stepNodeId()`/`highlightRetrievalStep()` treat `SUB_QUESTION_SPAWNED` and `SYNTHESIS` as no-ops on the main canvas (return `null`/return early) — their identifier is a Community id with no corresponding Cytoscape node of their own kind, and their real visual representation is now the tree, not the entity graph. This also keeps the upcoming-edge preview loop from attempting (harmlessly, but incorrectly) to bridge them as if they were graph nodes.

**Never:**
- Do not change `AnswerDriftSearch`, `RetrievalStep`, `RetrievalTrace`, `CorpusController`, or any `/api/traces/{traceId}` wire shape — this story is `graphrag-web`-only (JS/CSS/HTML + one UI test).
- Do not change `replay.js`'s transport-control step-index semantics, `buildTicks()`, or the tick track — the linear scrubber below the tree stays exactly as-is per DESIGN.md ("The linear scrubber sits below unchanged").
- Do not change Local/Global Search's replay rendering, captions (beyond adding the two new cases, which those traces never trigger), or the main canvas's existing ENTITY/RELATIONSHIP/COMMUNITY highlighting behavior.
- Do not introduce a second Cytoscape instance, a charting library, or any new runtime dependency — build the tree from plain DOM elements/CSS, consistent with how `replay.js` already builds its tick track from plain `<button>`s.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| DRIFT trace with 2+ spawned branches, currently on a mid-branch step | Steps: `COMMUNITY(s)`, `SUB_QUESTION_SPAWNED`, branch-1 steps, `SUB_QUESTION_SPAWNED`, branch-2 steps, `SYNTHESIS`; `currentIndex` inside branch 1 | Tree shows root (static), branch 1 node `is-current` (active styling), branch 1's line solid, branch 2 node/line default (not yet reached, dashed line), final node static/not yet emphasized | N/A — happy path |
| Same trace, `currentIndex` on the `SYNTHESIS` step | as above | Both branch nodes show the "✓ resolved" caption, both lines solid, final node is the current position | N/A — happy path |
| DRIFT trace on a `COMMUNITY` step (before any branch spawned) | `currentIndex` < first `SUB_QUESTION_SPAWNED` index | Tree shows root, all branch lines dashed/not-yet-reached, no branch or final node active yet | N/A — happy path |
| LOCAL or GLOBAL trace (no `SUB_QUESTION_SPAWNED` steps at all) | any steps, none of kind `SUB_QUESTION_SPAWNED` | Tree container stays `hidden`; existing Cytoscape-only replay behavior is unchanged | N/A — unchanged from before this story |
| Replay closed or trace fetch fails | `closeReplay()` / fetch error path | Tree container is cleared/hidden along with the rest of the scrubber reset | N/A — mirrors existing `GraphCanvas.clearStepHighlights()` reset |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/static/js/replay.js` -- new file `drift-tree.js` is the tree owner; `replay.js` only needs three call sites added: in `openReplay`'s success path (after `steps = ...`) call `window.DriftTree.build(steps)`; in `renderStep()` (alongside the existing `window.GraphCanvas.highlightStep(...)` call) call `window.DriftTree.highlightStep(steps, currentIndex)`; in `closeReplay()` and `showFetchError()` (alongside `window.GraphCanvas.clearStepHighlights()`) call `window.DriftTree.clear()`. Also extend `captionFor(step, index, total)`'s ternary chain with the two new kinds (see Boundaries).
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` -- `stepNodeId(step)` (around line 655): return `null` early for `step.kind === 'SUB_QUESTION_SPAWNED' || step.kind === 'SYNTHESIS'`, before the existing `COMMUNITY` check. `highlightRetrievalStep(step, nodeClass)` (around line 745): return immediately (no-op) for those same two kinds, before doing the generic `cy.getElementById(stepNodeId(step))` lookup — mirrors the existing early-return pattern already used for the `RELATIONSHIP` kind in that function.
- `graphrag-web/src/main/resources/static/js/drift-tree.js` (new) -- exposes `window.DriftTree = { build(steps), highlightStep(steps, currentIndex), clear() }`. `build` computes branch boundaries by scanning for `SUB_QUESTION_SPAWNED`/`SYNTHESIS` indices (see Boundaries' "Always" #1) and (re)creates the DOM: `.drift-tree-root`, one `.drift-tree-branch` (containing `.drift-tree-line` + `.drift-tree-node` + a hidden-by-default `.drift-tree-resolved` caption span) per spawned sub-question in spawn order, and `.drift-tree-final`. Shows/hides the container (`#drift-tree`) based on whether any branch was found. `highlightStep` recomputes which phase `currentIndex` falls into (root / branch N / final, per the same boundary scan) and toggles `is-current` / `is-resolved` / `is-upcoming` classes accordingly; it is idempotent and safe to call every `renderStep()`. `clear()` empties the container and hides it.
- `graphrag-web/src/main/resources/templates/index.html` -- add `<div class="drift-tree" id="drift-tree" hidden aria-hidden="true"></div>` inside `.graph-stage`, placed after `#graph-canvas` and before `.replay-scrubber` (matches DESIGN.md: "the branching tree view in the same replay area beneath the graph canvas... the linear scrubber sits below unchanged"). Add `<script th:src="@{/js/drift-tree.js}" src="/js/drift-tree.js"></script>` before `replay.js`'s `<script>` tag (load-order dependency: `replay.js` calls `window.DriftTree` on `openReplay`/`renderStep`).
- `graphrag-web/src/main/resources/static/css/instrument.css` -- new rule block for `.drift-tree` and its descendants, using only the existing custom properties named in Boundaries (`--drift`, `--drift-soft`, `--line-strong`, `--ink-600`, `--active`, `--active-soft`). Follow the file's existing custom-property-driven, BEM-ish class-naming convention (e.g. `.replay-tick`, `.replay-tick.is-done`, `.replay-tick.is-now` at lines ~472-495) for the new `.drift-tree-node.is-current` / `.is-resolved`, `.drift-tree-line.is-upcoming` modifier classes.
- `graphrag-web/src/test/java/com/graphraglens/web/ui/DriftTreeReplayUiTest.java` (new) -- follow the existing `ReplayCommunityHullVisibilityUiTest`/`ReplayRelationshipEdgeHighlightUiTest` pattern (`UiTestSupport`, `loadDemoDatasetAndWaitReady()`, click the DRIFT mode option, submit a question known to spawn 2+ sub-questions against the demo corpus, click `.replay-cta`).

## Tasks & Acceptance

**Execution:**
- [x] `graphrag-web/src/main/resources/static/js/drift-tree.js` -- create the module (`build`/`highlightStep`/`clear`) -- the story's core deliverable: builds and updates the branching tree DOM from the existing flat trace.
- [x] `graphrag-web/src/main/resources/static/js/replay.js` -- wire the three `DriftTree` call sites into `openReplay`/`renderStep`/`closeReplay`/`showFetchError`, and add the two new `captionFor` cases -- integrates the tree into the existing replay lifecycle and fixes the mislabeled captions Story 7.3's review deferred here.
- [x] `graphrag-web/src/main/resources/static/js/graph-canvas.js` -- make `stepNodeId`/`highlightRetrievalStep` no-op for the two new kinds -- fixes the main-canvas mis-highlighting Story 7.3's review also deferred here.
- [x] `graphrag-web/src/main/resources/templates/index.html` -- add the `#drift-tree` container and its `<script>` tag -- gives the tree a place to render and loads its script before `replay.js` needs it.
- [x] `graphrag-web/src/main/resources/static/css/instrument.css` -- add the `.drift-tree*` rule block -- gives the tree DESIGN.md's specified visual identity (root/branch/final colors, upcoming dashed lines, resolved caption).
- [x] `graphrag-web/src/test/java/com/graphraglens/web/ui/DriftTreeReplayUiTest.java` -- add end-to-end coverage for the I/O matrix's DRIFT-trace scenarios -- proves the tree renders, phases advance correctly, and a non-DRIFT trace never shows it.

**Acceptance Criteria:**
- Given a DRIFT answer's Replay CTA is clicked, when the trace loads, then the canvas renders one root node, one node per spawned sub-question (in spawn order) joined by branch lines, and one final convergence node.
- Given the replay is stepped forward/back through a DRIFT trace via the existing transport controls, when `currentIndex` moves, then the tree's active node and each branch's upcoming/resolved state update to match — root while on `COMMUNITY` steps, that branch's node while on its own `SUB_QUESTION_SPAWNED`/local steps, the final node on `SYNTHESIS` — with no change to the transport controls' own behavior.
- Given a branch has been fully stepped past, when a later step is current, then that branch keeps a small "✓ resolved" caption; a branch not yet reached keeps its line dashed.
- Given a Local or Global Search trace is replayed, when it loads, then the tree container stays hidden and the existing single-canvas replay behavior is unchanged.
- Given a `SUB_QUESTION_SPAWNED` or `SYNTHESIS` step is current, when the replay caption renders, then it reads "spawned sub-question ..." or "synthesized answer ..." respectively, never the generic "matched entity" — and neither step causes any mis-highlighted node on the main Cytoscape canvas.

## Spec Change Log

- 2026-09-20 — implemented the branching DRIFT replay tree as a new DOM/CSS module, wired it into replay lifecycle/caption handling, and added Playwright coverage using the deterministic demo-corpus DRIFT query `Tell me about Holmes.` to force a multi-branch trace in CI.

## Review Triage Log

### 2026-09-20 — Review pass
- verdicts: 14 findings — high 0, medium 9, low 2, false 3, maybe-false 0
- findings:
  - `[medium]` `[defer]` blind-hunter: `showFetchError()` clears `DriftTree` but not `window.GraphCanvas` highlights, so stale node/edge highlights can survive a failed trace re-fetch — verified real via baseline diff (`390a990`), but the asymmetry predates this story (baseline `showFetchError` never cleared canvas highlights either); this story only added the `DriftTree.clear()` call, it did not introduce or worsen the gap.
  - `[false]` `[reject]` blind-hunter: shared `.drift-tree-branch-rail`/`.drift-tree-converge-rail` stay solid instead of dashed for upcoming branches — the AC's "upcoming edge treatment" is satisfied by each branch's own `.drift-tree-line.is-upcoming` (verified in `drift-tree.js` `highlightStep`); the shared rails are structural chrome common to all branches, not a per-branch state indicator.
  - `[low]` `[reject]` blind-hunter: full spawned sub-question text in a 210px-max branch card may wrap awkwardly — speculative (no overflow observed in tests), CSS already sets `overflow-wrap: anywhere`; a truncation/tooltip redesign is more than a direct fix and not warranted without evidence of an actual regression.
  - `[low]` `[reject]` blind-hunter: branch nodes don't show which community a spawned sub-question came from — not required by the epics.md AC (root/fan/converge/upcoming/resolved-caption only); adding provenance mapping is a feature addition, not a direct fix.
  - `[false]` `[reject]` blind-hunter: `DriftTreeReplayUiTest` only asserts the hidden-tree case for LOCAL, not GLOBAL — verified in `drift-tree.js`: visibility is driven solely by `branches.length === 0` (absence of `SUB_QUESTION_SPAWNED` steps), the same code path GLOBAL would hit; the LOCAL test already exercises that exact branch.
  - `[medium]` `[patch]` blind-hunter: new test never asserts the "✓ resolved" caption text, dashed upcoming connectors, or branch label/order — fixed: added spawned-label/order assertion (`containsExactlyElementsOf`), an explicit `is-upcoming` class check on a branch line, and a visible "✓ resolved" text assertion.
  - `[medium]` `[patch]` blind-hunter: the two `while (!caption...) { stepForward.click(); }` loops have no bound, risking a hang/timeout instead of a crisp failure if the caption never appears — fixed: replaced with a step-budget-bounded `advanceUntilCaptionContains` helper that also stops on a disabled button and asserts the caption explicitly.
  - `[medium]` `[patch]` blind-hunter: `assertSpawnOrSynthesisDoesNotHighlightCommunityNode` only proves the two new step kinds don't wrongly highlight a community node; it doesn't prove ENTITY/RELATIONSHIP highlighting still works for ordinary branch steps — fixed (merged with the matching verification-gap finding below): added `assertBranchZeroFirstEntityStillHighlightsOnCanvas`, which fetches the trace, locates the first `ENTITY` step inside branch 0, and asserts `GraphCanvas.elementHasClass(id, 'step-active')` while `#drift-tree` stays visible.
  - `[medium]` `[defer]` edge-case-hunter: same `showFetchError`/`GraphCanvas` highlight-clearing gap as above (carried, same verdict/route).
  - `[false]` `[reject]` edge-case-hunter: same shared-rail-stays-solid claim as above (carried, same verdict/route).
  - `[medium]` `[patch]` edge-case-hunter: same unbounded-loop hang risk as above (carried, same verdict/route — fixed by `advanceUntilCaptionContains`).
  - `[medium]` `[patch]` verification-gap: no test advances into a DRIFT branch's ENTITY/RELATIONSHIP step and asserts the main canvas still highlights it while the tree is visible — pre-verified by the layer (existing tests only cover LOCAL edge-highlighting and the DRIFT no-op cases); fixed via `assertBranchZeroFirstEntityStillHighlightsOnCanvas` (see above).
  - `[medium]` `[patch]` verification-gap: no test pins branch-node text/order against the trace's `SUB_QUESTION_SPAWNED` labels or asserts the visible resolved-caption text, so a regression to fallback/alphabetical labels or a dropped caption would pass silently — fixed via the spawned-label/order and resolved-caption-text assertions (see above).
  - `[medium]` `[patch]` verification-gap: no UI test forces a trace-fetch failure to confirm `showFetchError()`'s new `DriftTree.clear()` actually hides a previously-rendered tree — fixed: added `failedTraceFetchClearsAnyPreviouslyRenderedDriftTree`, which opens a real DRIFT replay, closes it, then routes `**/api/traces/**` to a 404 and asserts the tree stays hidden and the error caption renders.

## Design Notes

**Why the tree is derived from step *position*, not a new backend-carried branch id:** Story 7.3 deliberately kept `RetrievalStep`'s shape untouched (`kind, identifier, label`) — there is no explicit "branch index" field. Every `SUB_QUESTION_SPAWNED` step already marks exactly where a new branch starts and every subsequent step belongs to it until the next `SUB_QUESTION_SPAWNED` or the `SYNTHESIS` step — so position-based partitioning is exact and needs no backend change, matching this epic's "additive, not a rework" constraint.

**Why the main canvas keeps rendering too:** DESIGN.md places the tree "beneath the graph canvas," not instead of it — each branch's own `AnswerLocalSearch` steps are real ENTITY/RELATIONSHIP touches on the actual knowledge graph, and hiding that would regress the very thing Local Search's replay already teaches well. The tree adds the missing "what shape did DRIFT take" view; it doesn't replace the existing "what did it touch" view.

## Auto Run Result

**Summary:** Added a new `drift-tree.js` module that derives a branching-tree visualization purely from `RetrievalStep` position (root = community pass, one branch per `SUB_QUESTION_SPAWNED` step through the next spawn/`SYNTHESIS`, final = convergence) and renders it in a new `#drift-tree` container beneath the replay stage. Wired it into `replay.js`'s existing transport-control lifecycle (`openReplay`, `renderStep`, `closeReplay`, `showFetchError`) with no changes to the step-index state machine itself. Added two new `captionFor()` cases for the Story 7.3 step kinds and made `graph-canvas.js` treat those two kinds as no-ops so they never mis-resolve to a Cytoscape node. Added a new Playwright UI test suite and one CSS rule block reusing only pre-existing design tokens.

**Files changed:**
- `graphrag-web/src/main/resources/static/js/drift-tree.js` (new) — branch-position analysis, DOM construction, and phase-based highlight state (`is-current`/`is-resolved`/`is-upcoming`) for the tree.
- `graphrag-web/src/main/resources/static/js/replay.js` — builds/clears the tree at the right lifecycle points; adds "spawned sub-question"/"synthesized answer" captions.
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` — `stepNodeId`/`highlightRetrievalStep` now no-op for `SUB_QUESTION_SPAWNED`/`SYNTHESIS` steps, with null-safe guards in the edge-bridging loops.
- `graphrag-web/src/main/resources/static/css/instrument.css` — new `.drift-tree*` rule block (root/branch/final states, responsive breakpoints), reusing existing color/spacing/typography tokens only.
- `graphrag-web/src/main/resources/templates/index.html` — `#drift-tree` container plus its `<script>` tag.
- `graphrag-web/src/test/java/com/graphraglens/web/ui/DriftTreeReplayUiTest.java` (new) — covers tree visibility/hidden states, phase transitions, branch label/order, resolved caption, canvas no-op/still-highlights behavior, and fetch-error cleanup (3 test methods after review patches).
- `_bmad-output/implementation-artifacts/spec-7-4-replay-the-drift-trace-as-a-branching-tree.md` — this spec (status, change log, triage log, deferred list, this section).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — story entry updated.

**Review findings breakdown (14 findings, 4 layers):**
- Patched (7, all medium, grouped into 4 fixes): missing assertions for resolved-caption text/dashed connectors/branch label-and-order; unbounded `while` loops risking a hang instead of a crisp test failure; no coverage that ordinary ENTITY/RELATIONSHIP canvas highlighting still works during a DRIFT branch step; no coverage that a failed trace fetch clears a previously-rendered tree. All fixed directly in `DriftTreeReplayUiTest.java`; full target verification suite re-run and passing after the patch.
- Deferred (1, medium): `replay.js`'s `showFetchError()` clears the new tree but not `GraphCanvas`'s node/edge highlights — confirmed pre-existing (baseline `390a990` had the same gap before this story), so out of this story's scope; recorded in frontmatter `deferred`.
- Rejected (3 false, 2 low): shared fan/converge rails staying solid for upcoming branches (per-branch line already carries that state per the AC); a GLOBAL-mode hidden-tree test (identical code path to the already-tested LOCAL case); branch-card text-wrap readability (speculative, cosmetic); missing community-provenance labeling on branches (not required by the AC).

**Follow-up review recommendation:** `true` — two or more medium-severity findings were patched on this first pass. Specific unverified risk: the GLOBAL-mode "tree stays hidden" behavior was verified by code inspection (same `branches.length === 0` path the LOCAL test already exercises), not by a dedicated GLOBAL UI test; a future GLOBAL-specific replay-wiring change could silently diverge from that assumption without a test catching it.

**Verification performed:**
- Independently re-read every changed file's diff against the spec's Code Map/Boundaries before any review layer ran.
- Ran `mvn -pl graphrag-web -am test -Dtest=DriftTreeReplayUiTest,DriftModeChoiceUiTest,ReplayCommunityHullVisibilityUiTest,ReplayRelationshipEdgeHighlightUiTest,CorpusControllerTest` twice — once before review patches (32 tests, 0 failures) and once after (33 tests, 0 failures) — confirmed via `target/surefire-reports/*.txt`, not console tail.
- Confirmed all 5 I/O-matrix rows are exercised: mid-branch state, synthesis/convergence state, pre-branch/community-pass state, non-DRIFT-trace hidden state, and replay-close/fetch-error reset.

**Residual risks:** the GLOBAL-mode hidden-tree path (see follow-up note above); the pre-existing `showFetchError()` canvas-highlight-clearing gap (deferred, unrelated to this story's surface).

## Verification

**Commands:**
- Manual/UI: `mvn -pl graphrag-web -am test -Dtest=DriftTreeReplayUiTest,DriftModeChoiceUiTest,ReplayCommunityHullVisibilityUiTest,ReplayRelationshipEdgeHighlightUiTest,CorpusControllerTest` -- expected: all pass, confirming the new tree behavior and that Local/Global/DRIFT-answer/entity-highlight replay behavior is unaffected.
