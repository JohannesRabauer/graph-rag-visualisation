---
title: 'Fix entity search / community toggle visual overlap'
type: 'bugfix'
created: '09-27-2026'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: false
context: []
warnings: []
deferred: []
baseline_revision: 'a13c94ae591b335e2d9b9dd1995707b091ba2029'
---

<intent-contract>

## Intent

**Problem:** `#entity-search` and `#community-toggle-wrap` both anchor `position: absolute` to the same top-right corner of `.graph-stage` with independent, eyeballed pixel offsets (`top: 46px` vs `top: var(--space-3)`) and no shared awareness of each other's height, so the entity-search box overlaps the community toggle whenever the toggle's real rendered height (its meta text wraps across several lines) exceeds 46px — which it does (GitHub #20).

**Approach:** Wrap both controls in one new flex-column container (`.canvas-top-right-stack`) that owns the single top-right anchor; each child flows below the previous one via `gap`, so they can never overlap regardless of either one's content length, instead of two independently-guessed fixed offsets.

## Boundaries & Constraints

**Always:** Preserve both controls' existing ids/classes and independent `hidden` toggling from `upload.js` (unaffected by DOM nesting — everything is referenced by `getElementById`). Preserve `.entity-search`'s `position: relative` so `.entity-search-results`' `top: calc(100% + 4px)` still anchors to the input box specifically, not the whole stack. Retarget the three existing responsive/state overrides that moved `.community-toggle-wrap` (detail-panel-open, narrow-viewport, tab-bar-visible) onto the new `.canvas-top-right-stack` selector instead, since it is now the positioned element — this is a required consequence of the fix, not scope creep, and it also fixes entity-search's own previously-nonexistent participation in those three responsive adjustments.

**Never:** Do not hardcode a second guessed pixel offset in place of the first — the fix must be robust to either control's content length changing later. Do not touch DRIFT/Vector Space/other unrelated canvas UI.

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/templates/index.html` — `.entity-search` (previously ~line 59) and `.community-toggle-wrap` (previously ~line 95, duplicated markup removed from its old location) are now both children of a new `#canvas-top-right-stack` div placed where `.entity-search` used to sit, right after `#canvas-tab-bar`.
- `graphrag-web/src/main/resources/static/css/instrument.css` — new `.canvas-top-right-stack` rule (flex column, single `position: absolute` anchor, owns `z-index: 5`); `.community-toggle-wrap`'s own `position/top/right/z-index` removed (kept only its `[hidden]` rule); `.entity-search` changed from `position: absolute` + fixed `top`/`right`/`z-index` to `position: relative` (dropdown anchor only) + `width: 220px`. Three existing overrides (`.graph-stage:has(.node-detail-panel.is-open) .community-toggle-wrap`, the narrow-viewport media query's `.community-toggle-wrap { right: ... }`, `.graph-stage:has(#canvas-tab-bar:not([hidden])) .community-toggle-wrap`) retargeted to `.canvas-top-right-stack`.
- `graphrag-web/src/main/resources/static/js/upload.js` — no change needed; both elements are already read via `getElementById` and already both present in the `kgEls` tab-visibility array (lines ~185/188), unaffected by DOM nesting.
- `graphrag-web/src/test/java/com/graphraglens/web/ui/EntitySearchUiTest.java` — added `entitySearchNeverOverlapsTheCommunityToggle()`, a geometry-based regression test using real `boundingBox()` values (not CSS source values), matching the issue's own suggested verification approach.

## Tasks & Acceptance

**Execution:**
- `index.html` -- wrap `.entity-search` and `.community-toggle-wrap` in `#canvas-top-right-stack` -- single shared positioning anchor instead of two independent guesses
- `instrument.css` -- add `.canvas-top-right-stack`, strip per-child absolute positioning, retarget the three state-based overrides -- makes the stack (and therefore both controls) participate correctly in detail-panel-open/narrow-viewport/tab-bar-visible adjustments
- `EntitySearchUiTest.java` -- add a bounding-box overlap regression test -- catches this class of bug the way source-level review missed it the first time

**Acceptance Criteria:**
- Given a Corpus is loaded and ready, when both controls are visible, then their rendered bounding boxes never overlap (verified via Playwright `boundingBox()`, not CSS values)
- Given the entity detail panel is open (shifting the stack left) or the viewport is narrow (tighter right margin) or the canvas tab bar is visible (pushed down to clear it), when the stack repositions, then both controls move together and still don't overlap
- Given all existing `MainControllerTest`/`EntitySearchUiTest` assertions on element ids/classes, when this DOM restructuring lands, then none of them break (ids/classes unchanged, only nesting changed)

## Spec Change Log

## Review Triage Log

### 09-27-2026 — Review pass
- verdicts: 12 findings — high 0, medium 2, low 1, false 9, maybe-false 0
- findings:
  - `[medium]` `[patch]` Blind Hunter: stale doc comment above `.entity-search` still describes the old absolute-positioning offsets ("Floats top-right of the graph stage... clear of both the tab bar... and the eyebrow") — no longer accurate now that positioning is owned by `.canvas-top-right-stack`. Patch: rewrite the comment to describe the new `position: relative` role (dropdown anchor only).
  - `[medium]` `[patch]` Blind Hunter #3/#4 + Verification-gap + Intent-alignment: the new regression test only checks the default/base-viewport state; it doesn't assert no-overlap when the detail panel is open, the viewport is narrow, or the tab bar is visible — all three are explicit Acceptance Criteria in this spec. Patch: add bounding-box assertions for panel-open and narrow-viewport states to `EntitySearchUiTest`.
  - `[low]` `[patch]` Blind Hunter: `.community-toggle-wrap[hidden] { display: none; }` is now a standalone rule with no comment explaining why it survives alone (its position/z-index rules moved to `.canvas-top-right-stack`) — could read as dead/orphaned CSS to a future reader. Patch: add a one-line comment.
  - `[false]` Blind Hunter: z-index tie between `.canvas-top-right-stack` (5) and `.node-detail-panel` — same z-index as the prior `.entity-search` value it replaces, no new tie introduced; both were already 5 before this change where they'd coexist.
  - `[false]` Blind Hunter: mixed `assertThat`/`org.assertj.core.api.Assertions.assertThat` import styles in the new test — matches the existing file's own convention (Playwright's `assertThat` is statically imported for locator assertions; AssertJ's is fully-qualified for value assertions) — not a defect.
  - `[false]` Blind Hunter: nesting `.community-toggle-wrap`/`.entity-search` under `.canvas-top-right-stack` changes how `upload.js`'s `kgEls` per-child `hidden` toggling interacts with the DOM — verified `getElementById` + `hidden` attribute toggling is unaffected by ancestor nesting; standard flex + `display:none` behavior confirmed by the passing UI test suite.
  - `[false]` Blind Hunter: new `id="canvas-top-right-stack"` on the wrapper div is unused (no JS/test references it) — not a defect; ids on structural wrappers are normal and harmless even when nothing currently selects them.
  - `[false]` Blind Hunter: "independently clickable" acceptance criterion tested only via bounding-box geometry, not simulated clicks — rejected as a required fix; no-overlap via `boundingBox()` is a sufficient proxy for independent clickability given both controls are simple, unobscured rectangles with no complex z-stacking between them.
  - `[false]` Blind Hunter: concern that `.entity-search-results` dropdown could visually overlap `.community-toggle-wrap` when expanded — refuted by actual render order: the toggle sits above the search box in the stack, and the dropdown extends downward/away from the toggle, not toward it.
  - `[false]` Edge-case-hunter: (no findings reported).
  - `[false]` Verification-gap: duplicate of the panel-open/narrow-viewport coverage gap already grouped above under the medium patch row — not a distinct finding.
  - `[false]` Intent-alignment-auditor: duplicate of the same coverage-gap concern, already grouped above.
- patch outcomes: all 3 patch-routed findings fixed and verified —
  - stale `.entity-search` doc comment rewritten to describe the current `position: relative` role.
  - `entitySearchNeverOverlapsTheCommunityToggle` split into a base-state test plus two new tests, `entitySearchNeverOverlapsTheCommunityToggleWithTheDetailPanelOpen` (opens the panel via `GraphCanvas.simulateTap`) and `entitySearchNeverOverlapsTheCommunityToggleAtNarrowViewportWidth` (600×800 viewport), sharing a new `assertControlsDoNotOverlap()` helper.
  - `.community-toggle-wrap[hidden]` given a one-line comment explaining why it survives as a standalone rule.
  - Re-verified: `mvn -q -B -pl graphrag-web -am test -Dtest=MainControllerTest,EntitySearchUiTest` — 7/7 and 8/8 pass; `mvn -q -B clean install` — full reactor, 181/181 tests pass, 0 failures/errors.

## Design Notes

Considered computing a fixed second offset (e.g. `top: 90px`) instead of a flex column — rejected: fragile, breaks again the moment the toggle's copy or font metrics change, and was exactly the class of bug being fixed. A shared flex-column anchor is self-adjusting by construction.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am test -Dtest=MainControllerTest,EntitySearchUiTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: all pass, including the new overlap regression test
- `mvn -q -B clean install` -- expected: full reactor green (every module, every Playwright UI test)

**Manual checks (if no CLI):**
- Load the app, load the Demo Dataset, confirm the community toggle and entity-search box render stacked with visible spacing between them, not overlapping, at default and narrow viewport widths, and with the entity detail panel open.

## Auto Run Result

- Status: **done**
- Implementation: `.canvas-top-right-stack` flex column added; both controls moved under it in `index.html`; `instrument.css` updated (new stack rule, stripped per-child positioning, 3 overrides retargeted); regression coverage added to `EntitySearchUiTest`.
- Review: 4 parallel reviewer layers (blind-hunter, edge-case-hunter, verification-gap, intent-alignment-auditor) ran against the staged diff. 12 raw findings triaged to 2 medium + 1 low patch-routed, 9 false/duplicate. All 3 patches applied and verified in a second pass.
- Verification: `mvn -q -B -pl graphrag-web -am test -Dtest=MainControllerTest,EntitySearchUiTest` (7+8 pass) and `mvn -q -B clean install` (full reactor, 181/181 tests, 0 failures/errors) both green after patches.
- `followup_review_recommended`: **false** — all findings from the single review pass were resolved (patched+reverified) or correctly rejected as false; no unresolved risk remains.
