---
title: 'Scale the Main Screen to Fill the Viewport'
type: 'bugfix'
created: '09-27-2026'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_revision: '2ea7dc2f6338752716cd4354c4eecbd7f0a0ce6b'
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The main screen (chat panel + graph canvas inside the "browser" frame) is capped at `.app-frame { max-width: 1180px; }` in `instrument.css`, so on any wider monitor it leaves large unused margins instead of filling the viewport; the Cytoscape.js graph canvas also never re-fits after its container's box changes (no `cy.resize()`/`cy.fit()` tied to size changes anywhere), so it can stay stale even once the frame is widened (Story 11.1, GitHub #30).

**Approach:** Raise `.app-frame`'s width cap to a generous, non-cropping bound (with a sane min-width floor so the two existing mobile breakpoints still hold), and add a `ResizeObserver` on the graph canvas container in `graph-canvas.js` that calls `cy.resize()` + a debounced `cy.fit()` whenever the observed box changes — covering both window resizes and layout-only changes (e.g. the node-detail panel opening/closing) that don't fire a window `resize` event.

## Boundaries & Constraints

**Always:**
- Keep `.chat-panel`'s existing fixed-width design (340px / 280px at narrow breakpoints) — only `.app-frame`'s outer cap and `.graph-stage`'s resulting width change.
- Keep the two existing mobile breakpoints (900px, 640px) and their `min-height` floors working; do not regress `EntitySearchUiTest`'s narrow-viewport (600x800) non-overlap assertion.
- Guard the new `ResizeObserver` callback against `cy` being `null` (no corpus loaded yet / Cytoscape not initialized) — no-op until `init()` has set `cy`.
- Debounce the resize-triggered `cy.fit()` (a raw per-frame `ResizeObserver` callback firing `cy.fit()` synchronously is wasteful and can fight in-progress pan/zoom) — `cy.resize()` itself is cheap and can run un-debounced.

**Never:**
- Do not remove the `.app-frame` max-width cap entirely (unbounded width on an ultrawide monitor is still undesirable) — raise it to a large sane bound instead (e.g. 1900px).
- Do not touch `.replay-scrubber`, `.drift-tree`, `.node-detail-panel`, `.vector-space-panel`, `.canvas-tab-bar`, or `.canvas-top-right-stack` positioning rules — these already scale correctly with `.graph-stage`'s width per investigation and are out of scope for this story.
- Do not add a dedicated backend endpoint or introduce a bundler/npm build step — this stays plain CSS + vanilla JS.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Wide viewport | Browser window >= 1900px wide | `.app-frame` grows past 1180px (up to its new cap), chat panel stays 340px, graph canvas fills the remaining width | No error expected |
| Narrow viewport | Browser window resized to 600x800 (existing `EntitySearchUiTest` case) | Mobile breakpoint layout still applies; community-toggle and entity-search controls still don't overlap | No error expected |
| Post-load viewport resize | Corpus already `READY`, Cytoscape initialized, then `page.setViewportSize(...)` changes the window | `cy.width()`/`cy.height()` (or a rendered node's screen position) update to match the new container box shortly after the resize | No error expected |
| Node-detail panel opens while canvas visible | User clicks an Entity, `.node-detail-panel.is-open` narrows `.graph-stage`'s effective width via CSS (no window resize event) | Cytoscape still resizes/re-fits since the `ResizeObserver` is attached to the container itself, not `window` | No error expected |
| No corpus loaded yet | `graph-canvas.js` loaded but `init()` hasn't run / `cy` is `null` | `ResizeObserver` callback no-ops safely, no thrown error | Guard clause, no console error |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/static/css/instrument.css:135-147` -- `.app-frame` rule holding the `max-width: 1180px` cap to raise, plus its `calc(100vh - ...)` height (already correct, leave as-is)
- `graphrag-web/src/main/resources/static/css/instrument.css:1709-1825` -- existing 900px/640px mobile breakpoints; must keep passing after the cap change, add a `min-width` guard if needed
- `graphrag-web/src/main/resources/static/js/graph-canvas.js:134-419` -- Cytoscape `init()`; add the `ResizeObserver` wiring here, observing the `#graph-canvas` container (or its `.graph-stage` parent)
- `graphrag-web/src/main/resources/static/js/graph-canvas.js:968-985` -- `queueLayout()`, the only existing place a full `fit: true` layout runs today; new resize-triggered `cy.fit()` should be a lighter debounced call, not a full re-layout
- `graphrag-web/src/test/java/com/graphraglens/web/ui/EntitySearchUiTest.java:120-131` -- existing narrow-viewport (600x800) non-overlap test to re-verify unaffected; pattern to follow for new viewport tests (`page.setViewportSize(...)`, `boundingBox()`)

## Tasks & Acceptance

**Execution:**
- `graphrag-web/src/main/resources/static/css/instrument.css` -- raise `.app-frame`'s `max-width` from `1180px` to `1900px` and add a `min-width: 960px` floor (above the two mobile breakpoints, which already override with their own narrower rules) -- lets the frame use wide-monitor space while keeping an upper sane bound and not breaking the narrow layouts
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` -- in `init()`, attach a `ResizeObserver` to the `#graph-canvas` container that calls `cy.resize()` immediately and a debounced (~120ms) `cy.fit()` on each observed size change, guarded by `if (!cy) return;` -- makes the canvas track its container's actual box instead of going stale after a CSS width change, window resize, or the node-detail panel opening/closing
- `graphrag-web/src/test/java/com/graphraglens/web/ui/MainScreenLayoutUiTest.java` -- new Playwright test file: (a) assert `.app-frame`'s bounding-box width scales up at a wide viewport (e.g. 2000x1100) and stays capped at/below the new max-width, (b) load a corpus, then resize the viewport and assert Cytoscape's rendered container dimensions (via a JS `page.evaluate` reading `cy.width()`/`cy.height()` through an exposed test hook, or a node's on-screen bounding box) change accordingly within a short wait -- gives this story explicit regression coverage since none existed before
- `graphrag-web/src/test/java/com/graphraglens/web/ui/EntitySearchUiTest.java` -- re-run unchanged to confirm the existing narrow-viewport non-overlap assertion still passes after the CSS change -- guards against a regression in the exact class of bug Story 10.1 already fixed once

**Acceptance Criteria:**
- Given the browser window is at least 1900px wide, when the main screen renders, then `.app-frame` fills width up to its new cap (visibly wider than the previous 1180px limit) and height per the existing `calc(100vh - ...)` rule, with no large unused horizontal margins beyond generous outer padding
- Given a Corpus is already `READY` and the graph canvas has rendered nodes, when the browser viewport is resized, then the Cytoscape canvas resizes/re-fits to its new container box without requiring any other user action (e.g. clicking a node) to trigger it
- Given the existing 600x800 narrow-viewport scenario from `EntitySearchUiTest`, when the page renders at that width, then the community-toggle and entity-search controls still do not overlap

## Spec Change Log

## Review Triage Log

### 09-27-2026 — Review pass
- verdicts: 14 findings — high 0, medium 2, low 6, false 4, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind-hunter) `.app-frame`'s `min-width: 960px` base rule isn't covered by the `max-width: 900px` breakpoint's `min-width: 0` override for viewports 901-959px wide — verified: causes forced horizontal overflow in that band. Fix: aligned the breakpoint threshold with the min-width floor.
  - `[medium]` `[patch]` (edge-case-hunter) same finding as above, same location (instrument.css:135-138,1710) — grouped with the blind-hunter row.
  - `[low]` `[patch]` (blind-hunter) Tests only exercise `window` resize; the node-detail-panel CSS-only-resize scenario cited as this fix's core justification is never exercised.
  - `[low]` `[patch]` (edge-case-hunter) Claim check: `.node-detail-panel.is-open` narrowing `.graph-stage` "with no window resize event" doesn't occur — both `#graph-canvas` and `.node-detail-panel` are `position: absolute` inside `.graph-stage`, so opening the panel never changes `.graph-stage`'s or `#graph-canvas`'s own box; the `ResizeObserver` never fires for this case either way — verified false premise in the design rationale. Fix: corrected the misleading code comments to state the real, verified justification (window resizes, and robustness against any future container-size CSS change) instead of the disproven node-detail-panel case.
  - `[low]` `[patch]` (verification-gap, "Other findings") same claim, independently confirmed by tracing every other `.graph-stage` overlay (`.replay-scrubber`, `.drift-tree`, `.canvas-top-right-stack`, `.graph-legend`) — all absolutely positioned, none affect the container box; grouped with the two rows above.
  - `[low]` `[patch]` (intent-alignment) "Node-detail panel opens" I/O-matrix row is implemented but not tested — grouped with the three rows above; same root cause (the scenario doesn't occur, so there was nothing valid to test).
  - `[low]` `[patch]` (blind-hunter) `watchContainerResize(container)` is only called on `init()`'s success path, after the `!container` and cytoscape-undefined early returns — verified: a previously attached `resizeObserver` from an earlier successful `init()` is never disconnected if a later `init()` call hits either early return. Fix: moved the disconnect/reset of `resizeObserver`/`resizeFitTimer` to the top of `init()`, unconditionally, before any early return.
  - `[medium]` `[patch]` (verification-gap, pre-verified) The debounced `cy.fit()` re-centering — the half of this fix that actually re-centers/re-zooms the stale canvas — has zero test coverage; the existing test only asserts `cy.width()`/`cy.height()`, which the un-debounced `cy.resize()` call alone fully explains, so deleting `cy.fit()` entirely would leave the test passing. Fix: added a `viewState()` test-support hook exposing `cy.zoom()`/`cy.pan()`, extended the test to wait past `RESIZE_FIT_DEBOUNCE_MS` and assert the fit actually changed.
  - `[medium]` `[patch]` (blind-hunter) same finding as above ("nothing verifies that the debounced cy.fit() actually fires") — grouped with the verification-gap row.
  - `[medium]` `[patch]` (intent-alignment) "resizes/re-fits" compound acceptance wording only half-verified — grouped with the two rows above; same root cause.
  - `[low]` `[reject]` (blind-hunter) `ResizeObserver` fires once immediately on `observe()`, layering an extra debounced `cy.fit()` on top of `queueLayout()`'s own `fit:true` cose layout during initial ingestion — possible "double-snap" interaction, untested. Verified as plausible but low-probability (requires a user resize mid-animation during active ingestion) and the fix (coordinating two independent fit/layout paths with a mutex or shared debounce) is more than a direct correction — rejected per the low-finding-with-nontrivial-fix rule.
  - `[false]` `[reject]` (blind-hunter) No evidence in the diff that `EntitySearchUiTest` was re-run to confirm no regression — refuted: this build's own Verify step ran `mvn -q -B -pl graphrag-web -am -Dtest=MainScreenLayoutUiTest,EntitySearchUiTest`, confirming 7/7 `EntitySearchUiTest` tests and 2/2 new tests passed, plus a full `mvn -q -B clean install` (exit 0). Evidence lives in this build's verification record, outside the diff, exactly where the spec's own Verification section places it.
  - `[false]` `[reject]` (intent-alignment) Regression-safety reading (R4) "addressed by omission, not by a new assertion in this diff" — same refutation as the row above; grouped together.
  - `[false]` `[reject]` (intent-alignment) `window.GraphCanvas.dimensions()` expands the module's public surface solely to serve a test need — verified: this matches this codebase's existing, established pattern of exposing narrow test-support hooks on `window.GraphCanvas` (e.g. `elementHasClass`, `entityNodeFillColor`, `entityNodeBorderColor`, all pre-existing and used the same way by other UI tests in this package) — no harm demonstrated beyond the pattern already in use throughout this file.

## Design Notes

The bug is really two independent root causes that both need fixing for the AC to hold: a CSS width cap (visible-margins symptom) and a missing resize-observation mechanism (stale-canvas symptom, which would persist even after the CSS fix on any subsequent resize/panel-open). `ResizeObserver` on the container itself — not a `window` `resize` listener — is required because the node-detail panel's `.node-detail-panel.is-open` class changes `.graph-stage`'s effective width purely via CSS, with no corresponding `window` resize event.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am test -Dtest=MainScreenLayoutUiTest` -- expected: new tests pass
- `mvn -q -B -pl graphrag-web -am test -Dtest=EntitySearchUiTest` -- expected: existing narrow-viewport non-overlap test still passes
- `mvn -q -B clean install` -- expected: full reactor green, no regressions

## Auto Run Result

**Summary:** Raised `.app-frame`'s width cap from 1180px to 1900px (with a 960px floor aligned to a matching breakpoint) so the main screen fills wide viewports, and added a container `ResizeObserver` in `graph-canvas.js` that keeps the Cytoscape canvas resized/re-fit after any post-load container-size change, closing both the visible-margin and stale-canvas halves of GitHub #30.

**Files changed:**
- `graphrag-web/src/main/resources/static/css/instrument.css` -- raised `.app-frame` `max-width` to 1900px, added a `min-width: 960px` floor aligned with a matching `@media (max-width: 960px)` breakpoint (patched from an initial 900px mismatch)
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` -- added a container `ResizeObserver` (`watchContainerResize`) calling `cy.resize()` un-debounced and a debounced `cy.fit()`; disconnect/reset now happens unconditionally at the top of `init()` (patched from a leak on early-return paths); corrected misleading justification comments; added `dimensions()` and `viewState()` test-support hooks on `window.GraphCanvas`
- `graphrag-web/src/test/java/com/graphraglens/web/ui/MainScreenLayoutUiTest.java` -- new file: wide-viewport width-cap test, and a post-load resize test asserting both `cy.resize()` (cached width/height) and the debounced `cy.fit()` (zoom/pan via `viewState()`, patched in — previously unverified) actually ran

**Review findings breakdown:** 14 findings from 4 reviewer layers, grouped into 7 root causes.
- Patched (4 groups, 9 member findings): CSS breakpoint/min-width gap (medium); misleading `ResizeObserver`-justification comments citing a node-detail-panel scenario that doesn't actually occur (low); a stale-`resizeObserver` leak on `init()`'s early-return paths (low); the debounced `cy.fit()` re-center having zero test coverage (medium).
- Rejected (3 groups, 5 member findings): a low-probability, non-trivial-to-fix "double-snap" interaction between the resize-triggered fit and the initial cose layout (low, rejected); no-evidence-of-regression-test claim, refuted by this build's own passing `EntitySearchUiTest` re-run (false); the new `window.GraphCanvas.dimensions()` test hook, refuted as matching this file's existing test-support-hook pattern (false).

**Verification performed:** `mvn -q -B -pl graphrag-web -am -Dtest=MainScreenLayoutUiTest,EntitySearchUiTest test` — 2/2 new + 7/7 existing pass, both before and after the patch pass; `mvn -q -B clean install` — full reactor green (exit 0), both before and after the patch pass.

**Follow-up review recommended:** `true` — two medium-verdict findings (the breakpoint gap and the untested debounced-fit) were both patched in this same pass. Unverified risk: the patches themselves (the 960px breakpoint alignment, and the new `viewState()`-based zoom/pan assertion) were verified only by this build's own re-run, not by an independent review pass — a follow-up pass should confirm neither patch introduced its own gap.

**Residual risks:** none rated high; the rejected "double-snap" interaction (resize-triggered `cy.fit()` colliding with an in-progress initial cose layout) remains theoretically possible but low-probability and untested.
