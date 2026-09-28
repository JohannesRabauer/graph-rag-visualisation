---
title: 'Add Zoom In/Out Controls for the Graph Canvas'
type: 'feature'
created: '09-28-2026'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: 'f0735bbf8454dd365ed1a59fb1e42f58169a65fe'
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** GitHub #35 wants visible zoom in/out (and fit-to-view/reset) buttons on the graph canvas — currently the only way to zoom is the mouse wheel (`userZoomingEnabled: interactive` in `graph-canvas.js:211`, at Cytoscape's untuned default sensitivity). Per this story's own cross-story sequencing (it was deliberately built after Story 11.7), the new controls must dock into a similarly durable, non-growing slot rather than reintroducing a one-off absolutely-positioned corner element — Story 11.7's own spec explicitly reserved this: zoom controls should reuse the existing `.canvas-top-right-stack` flex-column mechanism as a third sibling, not grow the settings popover or invent a new corner.

**Approach:** Add a small zoom in/out/fit-to-view button cluster as a new sibling inside `#canvas-top-right-stack` (after entity-search and the settings button), styled identically to the existing `.canvas-settings-toggle` icon-button pattern. Wire zoom in/out to `cy.animate({ zoom: ... }, { duration, easing })`, following the exact eased-animation pattern already established by `focusEntity()`'s `cy.animate({..zoom..})` call (`graph-canvas.js:558-575`) — not a hard `cy.zoom()` jump — so button-driven zoom feels identical to existing interactions. Fit-to-view uses `cy.fit()`. Mouse-wheel zoom (`userZoomingEnabled`) is left enabled and untouched.

## Boundaries & Constraints

**Always:**
- Add the new zoom-controls cluster as a third sibling inside the existing `#canvas-top-right-stack` flex column (after entity-search, after canvas-settings) — this reuses the proven non-overlap mechanism (including its free detail-panel-open shift and narrow-viewport override) rather than adding new absolute-positioning code.
- Style the new buttons to match `.canvas-settings-toggle`'s exact visual recipe (34x34px, `rgba(255,255,255,0.9)` background, hairline border, `var(--radius)`) — the "Instrument" design tokens this app already uses.
- Use `cy.animate({ zoom: ... })` (eased, matching `focusEntity()`'s existing 300ms precedent) for zoom in/out, not an instant `cy.zoom()` jump.
- Gate the new buttons' visibility the same way `#canvas-settings-toggle` is gated in `upload.js` (hidden until a corpus is ready/interactive, hidden again on reset) — an ungated button visible over the idle canvas would be the same class of bug Story 11.7 just fixed.
- Keep mouse-wheel zoom (`userZoomingEnabled: interactive`) working exactly as before, unchanged.

**Never:**
- Do not place the new controls in a bottom corner — `.replay-scrubber` is a full-width bottom bar (`left:0; right:0`) shown during Replay, so any bottom-docked control would collide with it whenever Replay is active, reproducing the exact bug class Story 11.7 just fixed.
- Do not nest the zoom controls inside `.canvas-settings-popover` — Story 11.7's own spec explicitly reserved that popover for view-option toggles only, not this story's controls.
- Do not touch `userZoomingEnabled`/`userPanningEnabled` or any other existing Cytoscape interaction config beyond what's needed to add `wheelSensitivity`/`minZoom`/`maxZoom` bounds (see Design Notes) if tuning is warranted.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Zoom in/out button clicked | Graph canvas showing, corpus ready | Canvas zooms in/out with a smooth eased transition, same visual character as mouse-wheel zoom | No error expected |
| Fit-to-view button clicked | Graph canvas showing, corpus ready | Canvas re-fits to show the full graph extent | No error expected |
| Mouse-wheel zoom | Graph canvas showing | Continues to work exactly as before, unaffected by the new buttons | No error expected |
| Idle state (no corpus loaded) | Before any corpus is uploaded/ready | Zoom controls are hidden, same as the settings button and entity-search | No error expected |
| Narrow viewport (600x800, existing test) | Existing `EntitySearchUiTest` narrow-viewport scenario | The new zoom-controls cluster does not overlap entity-search or the settings button | No error expected |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/static/js/graph-canvas.js:211-212` -- `userZoomingEnabled`/`userPanningEnabled`, both gated by `interactive`; leave unchanged
- `graphrag-web/src/main/resources/static/js/graph-canvas.js:558-575` -- `focusEntity()`'s existing `cy.animate({ center, zoom }, { duration: 300 })` call; the precedent pattern to copy for zoom in/out's eased transition
- `graphrag-web/src/main/resources/templates/index.html:60-96` -- `#canvas-top-right-stack` with its two current siblings (`#entity-search`, `#canvas-settings`); add the new zoom-controls cluster as a third sibling here
- `graphrag-web/src/main/resources/static/css/instrument.css:842-855` -- `.canvas-settings-toggle`'s icon-button styling to copy for the new zoom buttons
- `graphrag-web/src/main/resources/static/js/upload.js` -- wherever `#canvas-settings-toggle`'s `hidden` attribute is toggled (corpus-ready reveal, reset flow); mirror the same show/hide wiring for the new zoom-controls cluster
- `graphrag-web/src/test/java/com/graphraglens/web/ui/EntitySearchUiTest.java` -- existing `assertControlsDoNotOverlap(Locator, Locator)` helper and its default/narrow-viewport/detail-panel-open call sites; extend for the new cluster
- `graphrag-web/src/test/java/com/graphraglens/web/ui/MainScreenLayoutUiTest.java` -- has an existing `cy.fit()`-related resize-debounce test; re-verify it still passes since this story also calls `cy.fit()`

## Tasks & Acceptance

**Execution:**
- `graphrag-web/src/main/resources/templates/index.html` -- add `#canvas-zoom-controls` (a `role="group" aria-label="Zoom controls"` container with three buttons: zoom-in, zoom-out, fit-to-view) as a third sibling inside `#canvas-top-right-stack`, initially `hidden`
- `graphrag-web/src/main/resources/static/css/instrument.css` -- style `.canvas-zoom-button` matching `.canvas-settings-toggle`'s icon-button recipe, laid out as a small flex row
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` -- add zoom-in/zoom-out functions using `cy.animate({ zoom: cy.zoom() * factor }, { duration, easing })` (a ~1.25x step, matching `focusEntity`'s 300ms duration/easing) and a fit-to-view function using `cy.fit()`; export them on `window.GraphCanvas` for `upload.js` to wire to the buttons
- `graphrag-web/src/main/resources/static/js/upload.js` -- wire the three buttons' clicks to the new `GraphCanvas` functions; show/hide `#canvas-zoom-controls` alongside `#canvas-settings-toggle`'s existing corpus-ready/reset visibility wiring
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ZoomControlsUiTest.java` -- new test: buttons hidden in idle state, visible once a corpus is ready; clicking zoom-in/zoom-out changes `cy.zoom()` in the expected direction; clicking fit-to-view resets it; mouse-wheel zoom still works; the cluster doesn't overlap `#entity-search`/`#canvas-settings-toggle` at default and 600x800 narrow viewports
- `graphrag-web/src/test/java/com/graphraglens/web/ui/MainScreenLayoutUiTest.java` -- re-run unchanged to confirm the existing resize/`cy.fit()`-debounce test isn't affected by this story's own `cy.fit()` usage

**Acceptance Criteria:**
- Given the graph canvas is showing (corpus ready), when the user looks at the canvas, then dedicated zoom-in, zoom-out, and fit-to-view controls are visible and clickable, styled per the existing Instrument design tokens
- Given the user clicks a zoom control, when it activates, then the canvas zooms with the same eased/animated transition character as existing mouse-wheel zoom
- Given mouse-wheel zoom continues to work, when the user scrolls over the canvas, then zoom behavior is unchanged from before this story
- Given the existing narrow-viewport (600x800) scenario, when the page renders, then the zoom-controls cluster, entity-search, and settings button all remain independently visible and non-overlapping

## Spec Change Log

## Review Triage Log

### 09-28-2026 — Review pass
- verdicts: 15 findings — high 0, medium 1, low 12, false 1, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind-hunter) `zoomIn`/`zoomOut` read `cy.zoom()` at click time and start a new 300ms `cy.animate()` with no guard against overlapping animations — rapid double-clicks read a mid-animation value rather than the prior target, so the step silently collapses instead of compounding. Fix: added an in-progress flag so a click during an active zoom animation is a no-op until it completes.
  - `[medium]` `[patch]` (edge-case-hunter) same race, independently confirmed — grouped with the row above.
  - `[low]` `[patch]` (edge-case-hunter) `fitToView` clicked while a `zoomIn`/`zoomOut` animation is still in flight can have its result silently overridden when the queued animation finishes after `cy.fit()` runs. Fix: `fitToView` now calls `cy.stop(true, true)` before `cy.fit()` to clear any in-flight animation first.
  - `[low]` `[patch]` (blind-hunter) no `minZoom`/`maxZoom` bounds are set anywhere, and the code comment's claim that "Cytoscape ignores an out-of-range target" is untested. Fix: added sane `minZoom`/`maxZoom` bounds to the Cytoscape init options, preventing degenerate zoom from repeated clicks regardless of the untested claim.
  - `[low]` `[patch]` (blind-hunter) no keyboard-only interaction test exists for the three zoom buttons, despite `:focus-visible` styling being added for them. Fix: added a keyboard-only test (Tab to focus, Enter/Space to activate), matching the pattern already established for the settings button in Story 11.7.
  - `[low]` `[patch]` (blind-hunter) `.canvas-zoom-button` duplicates `.canvas-settings-toggle`'s entire icon-button declaration verbatim rather than sharing a class, so a future visual tweak needs two edits. Fix: extracted the shared properties into one class both buttons use.
  - `[low]` `[patch]` (blind-hunter) `clickingFitToViewResetsTheZoom` only asserts the zoom value changed, not that it converges to the graph's actual fitted extent — a tautological check. Fix: strengthened the assertion to compare against a freshly-computed `cy.fit()` baseline.
  - `[low]` `[patch]` (intent-alignment) same test-weakness observation — grouped with the row above.
  - `[low]` `[reject]` (blind-hunter) overlap tests only check the zoom cluster against `#entity-search`/`#canvas-settings-toggle`, not `#replay-scrubber`/the detail panel — verified the container-level CSS rules (detail-panel-open shift, narrow-viewport override) already apply automatically to any stack child, and this narrower test scope matches the precedent already established by Story 11.7's own overlap tests, not a new gap unique to this diff.
  - `[low]` `[reject]` (blind-hunter) no test covers the zoom controls being hidden during other states (e.g. an upload error mid-flight) beyond the two toggle points already mirrored from the settings button — rejected: the settings button itself has no such dedicated test either; this diff correctly mirrors existing (equally untested) behavior, not a new gap.
  - `[low]` `[reject]` (blind-hunter) no unit-level (non-Playwright) test guards the `if (!cy) return` early-exit branches — rejected: this codebase has no JS unit-test harness at all (confirmed during Story 11.5's investigation), so this isn't a gap this diff introduces.
  - `[low]` `[reject]` (intent-alignment) "smoothness" is only verified by a numeric before/after zoom check, never animation quality itself — rejected: Playwright cannot meaningfully assert visual easing; verifying the `cy.animate()` call shape (duration/easing) matches the existing `focusEntity()` precedent via code inspection, as verification-gap already did, is this codebase's established practice for this class of claim.
  - `[false]` `[reject]` (intent-alignment) the epic's zoom-sensitivity "tuning investigation" was resolved by a code comment rather than a test — refuted: this story's own spec explicitly made that tuning conditional on finding real evidence of a mismatch, and instructed against speculative changes; no evidence was found, so leaving it untouched is the spec's own correct, directed outcome, not an oversight.
  - `[low]` `[patch]` (intent-alignment) the code comment claims one zoom-in followed by one zoom-out returns to the original zoom level, but no test verifies this round-trip symmetry. Fix: added an assertion checking the zoom value after an in/out round-trip is approximately equal to the starting value.
  - `[low]` `[patch]` (intent-alignment) several tests use fixed `page.waitForTimeout(...)` calls rather than polling a deterministic condition, acknowledged in the diff's own test comments as timing-sensitive. Fix: replaced the fixed timeouts with `page.waitForFunction` polling on the observed zoom value actually changing, matching the pattern already established in `MainScreenLayoutUiTest`.

## Design Notes

This story deliberately reuses Story 11.7's just-established flex-column stack mechanism rather than adding new positioning code, per that story's own explicit forward-looking design intent ("Story 11.6's future zoom controls are expected to dock into their own small, similarly-scoped slot... rather than growing this same popover or reintroducing a third one-off absolutely-positioned element"). Cytoscape's zoom defaults (`wheelSensitivity`, `minZoom`/`maxZoom`) are currently untuned; if a step-size mismatch between button-driven and wheel-driven zoom is found during implementation, tune `wheelSensitivity` (not the button step factor) so both feel consistent — but only if evidence (a real rendered test) shows a mismatch, not speculatively.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am -Dtest=ZoomControlsUiTest,EntitySearchUiTest,MainScreenLayoutUiTest -Dsurefire.failIfNoSpecifiedTests=false test` -- expected: all pass
- `mvn -q -B clean install` -- expected: full reactor green, no regressions

## Auto Run Result

**Summary:** Added visible zoom in/out/fit-to-view buttons to the graph canvas, docking into the flex-column stack pattern Story 11.7 just established (a third sibling in `#canvas-top-right-stack`) rather than a one-off floating element, per this story's own explicit cross-story sequencing. Zoom in/out use `cy.animate()`, matching the existing eased-transition precedent from `focusEntity()`; mouse-wheel zoom is untouched. Review caught and fixed a real animation-race condition (rapid clicks silently collapsing instead of compounding), independently confirmed by two reviewer layers.

**Files changed:**
- `graphrag-web/src/main/resources/templates/index.html` -- new `#canvas-zoom-controls` cluster (three buttons) as a third stack sibling; shared `canvas-icon-button` class applied (patched in) alongside the settings toggle
- `graphrag-web/src/main/resources/static/css/instrument.css` -- new `.canvas-zoom-controls`/`.canvas-zoom-button` styling; shared icon-button properties extracted into `.canvas-icon-button` (patched in, deduplicating from `.canvas-settings-toggle`)
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` -- new `zoomIn()`/`zoomOut()`/`fitToView()` using `cy.animate()`/`cy.fit()`; gained an animation-in-progress guard against rapid-click races (patched in, medium severity), a `cy.stop()` call before fit to prevent an in-flight zoom animation from overriding it (patched in), and `minZoom`/`maxZoom` bounds (patched in)
- `graphrag-web/src/main/resources/static/js/upload.js` -- wired the three buttons; visibility gated identically to the settings button (corpus-ready reveal, reset, tab-switch)
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ZoomControlsUiTest.java` -- new file: visibility gating, zoom in/out direction, fit-to-view, wheel-zoom coexistence, non-overlap at default/narrow viewports; gained a keyboard-only test, a strengthened fit-to-view assertion, a round-trip symmetry assertion, and deterministic polling in place of fixed sleeps (all patched in)

**Review findings breakdown:** 15 findings from 4 reviewer layers, grouped into 13 root causes.
- Patched (8 groups, 10 member findings): a medium-severity rapid-click animation race (independently found by two layers); a related fit-to-view-vs-in-flight-animation override; missing zoom bounds; a missing keyboard-interaction test; duplicated icon-button CSS; a tautological fit-to-view test assertion; an untested round-trip-symmetry claim; and fixed-sleep tests replaced with deterministic polling.
- Rejected (5 groups, 5 member findings): narrower overlap-test scope matching Story 11.7's own established precedent; an untested hidden-during-error state that mirrors the equally-untested settings button; no JS unit-test coverage (this codebase has no JS unit-test harness at all); "smoothness" verified only numerically (Playwright's inherent limit, matching established practice); and a refuted claim that the epic's zoom-sensitivity tuning ask was skipped without justification -- it was explicitly conditional on finding evidence of a mismatch, and none was found.

**Verification performed:** `mvn -q -B -pl graphrag-web -am -Dtest=ZoomControlsUiTest,CanvasSettingsPopoverUiTest,MainScreenLayoutUiTest,EntitySearchUiTest test` -- 25/25 pass, both before and after the patch pass; `mvn -q -B clean install` -- full reactor green (exit 0), both before and after the patch pass.

**Follow-up review recommended:** `false` -- only one medium-verdict entry was patched this pass (the rapid-click race); the follow-up-review trigger rule (a patched `high`, or two-or-more patched `medium`) is not met.

**Residual risks:** none rated high or medium. The rejected findings above (untested error-state hiding, no JS unit tests) are pre-existing patterns in this codebase, not new risks introduced by this story.

This is the last story in Epic 11 (issues #30-#36) and completes the "build all open issues" run: 5 already-merged-but-unclosed issues were closed (#21, #22, #23, #24, #29), and 7 new stories (11.1-11.7) were planned, implemented, and reviewed in this session.
