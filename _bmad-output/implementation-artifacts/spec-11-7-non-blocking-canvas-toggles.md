---
title: 'Make the Community-Formation and Color-Code-Entity-Types Toggles Non-Blocking'
type: 'bugfix'
created: '09-28-2026'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_revision: '1a32f72bcef630285a3f82972b68a99d6ec9260b'
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** GitHub #36 reports the community-formation-view and color-code-entity-types checkboxes can block/overlap the graph canvas. Investigation confirmed Story 10.1 already prevents these controls from overlapping *each other* (a flex-column stack, `.canvas-top-right-stack` in `instrument.css:810-827`), but did nothing to reserve canvas space for the stack itself — it remains a permanently-rendered `position: absolute` overlay that sits on top of whatever graph content renders underneath it, and its footprint has only grown as more controls (Story 10.3's entity-type toggle, and Story 11.6's still-to-be-built zoom controls) join it. This is the same class of bug as #20/Story 10.1 recurring one layer up: an ever-growing absolute overlay competing with canvas content, not a sibling-vs-sibling overlap (already solved).

**Approach:** Collapse the two view-option toggles (community-formation, color-code-entity-types) behind a single small, persistent settings icon-button that opens/closes a transient popover panel containing both — per the story's own design notes ("a collapsible/collapsed-icon state or a docked settings affordance, rather than more absolutely-positioned corner elements"). Collapsed, the affordance's footprint is small enough to never meaningfully obscure canvas content at any viewport width; expanded, it only covers content while the user is actively interacting with it. Entity-search stays a separate, always-visible control in its own slot (a distinct "jump to" interaction, not a view-option toggle) — out of this story's scope to redesign.

## Boundaries & Constraints

**Always:**
- Keep `#entity-search` as a separate, always-visible control in its own dedicated slot within `.canvas-top-right-stack` — do not fold it into the new settings popover.
- Keep the existing toggle behaviors (community-formation visualization on/off, entity-type color-coding on/off) and their existing ids (`#community-visualization-toggle`, `#entity-type-color-toggle`) unchanged — only their container/visibility mechanism changes, not their own semantics or the JS that reads their checked state.
- Keep the popover closable via an outside click and the Escape key, and keep the toggle button keyboard-operable (a native `<button>`), matching this app's existing "best-effort" keyboard reachability standard.
- The settings button and its popover must remain visible/clickable at any supported viewport width, including the existing 600x800 narrow case `EntitySearchUiTest` already covers.

**Never:**
- Do not touch `entity-search`'s own markup, styling, or JS logic beyond adjusting its position within the (now smaller) top-right stack.
- Do not change how `queueLayout()`/Cytoscape's own rendering works — this is a UI-chrome-only fix, not a graph-layout change (that was Story 11.5's separate scope).
- Do not add Story 11.6's zoom controls in this story — only build the settings-popover pattern so 11.6 (built afterward, per the epic's own sequencing) can dock its zoom buttons into a similarly small, non-growing slot rather than the old stack.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Settings collapsed (default) | Graph canvas showing, community toggle and entity-type toggle both present | A single small settings icon-button renders top-right; neither toggle's own controls render inline, so nothing but the small button's own bounding box can overlap canvas content | No error expected |
| Settings expanded | User clicks/activates the settings button | A popover opens showing both toggles; clicking either still toggles its corresponding view option exactly as before | No error expected |
| Click outside / Escape | Popover is open | Popover closes; settings button remains visible and re-clickable | No error expected |
| Narrow viewport (600x800, existing test) | Existing `EntitySearchUiTest` narrow-viewport scenario | Settings button and entity-search remain independently visible and clickable, non-overlapping | No error expected |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/templates/index.html:60-86` -- `.canvas-top-right-stack` markup: community-toggle-wrap, entity-type-toggle-wrap, entity-search, all currently always-rendered siblings; restructure so the two toggles nest inside a new popover, behind a new settings button
- `graphrag-web/src/main/resources/static/css/instrument.css:810-929` -- the flex-column stack rule and both toggles' styling (community-toggle, entity-type-toggle) to restructure into a button + popover pattern
- `graphrag-web/src/main/resources/static/css/instrument.css:1793-1800,1906-1908` -- narrow-viewport and tab-bar-clearance overrides for the stack; must be re-verified/adjusted for the new smaller collapsed footprint
- `graphrag-web/src/main/resources/static/js/upload.js` -- wherever `#community-toggle-wrap`/`#entity-type-toggle-wrap`'s `hidden` attribute is currently toggled (corpus-ready reveal, reset flow); add the new settings-button open/close wiring here, following the same pattern
- `graphrag-web/src/test/java/com/graphraglens/web/ui/EntitySearchUiTest.java:89-131` -- existing `assertControlsDoNotOverlap()` pattern and its three call sites; extend/adapt for the new settings button + entity-search pairing
- `graphrag-web/src/test/java/com/graphraglens/web/ui/EntityTypeColorToggleUiTest.java` -- exercises the entity-type toggle's on/off behavior; must keep passing once the toggle moves inside the popover (its id/semantics are unchanged, only its container)

## Tasks & Acceptance

**Execution:**
- `graphrag-web/src/main/resources/templates/index.html` -- add a new settings icon-button (e.g. `#canvas-settings-toggle`) as a sibling of `entity-search` inside `.canvas-top-right-stack`; nest `#community-toggle-wrap` and `#entity-type-toggle-wrap` inside a new popover container (e.g. `#canvas-settings-popover`, initially `hidden`) anchored to that button
- `graphrag-web/src/main/resources/static/css/instrument.css` -- style the settings button as a small (~32-40px) icon button matching the existing toggle controls' visual language (hairline border, instrument tone); style the popover to appear below/beside the button when open, `hidden` otherwise; adjust the narrow-viewport and tab-bar-clearance rules for the new, smaller collapsed footprint
- `graphrag-web/src/main/resources/static/js/upload.js` -- wire the settings button's click to toggle the popover's `hidden` attribute; add a document-level click-outside listener and an Escape-key listener that close the popover when open (following existing delegated-listener patterns already used elsewhere in this file, e.g. for `.compare-cta`)
- `graphrag-web/src/test/java/com/graphraglens/web/ui/CanvasSettingsPopoverUiTest.java` -- new test: assert the settings button is visible with the popover initially collapsed/hidden, opens the popover on click revealing both toggles, closes on outside click and on Escape, and that both toggles still function (checking/unchecking updates the graph exactly as `EntityTypeColorToggleUiTest`/existing community-toggle tests already verify) once inside the popover
- `graphrag-web/src/test/java/com/graphraglens/web/ui/EntitySearchUiTest.java` -- update `assertControlsDoNotOverlap()`'s call sites (or add a new one) to check the settings button (not the old always-visible toggle wraps) against `#entity-search`, at the existing narrow-viewport width

**Acceptance Criteria:**
- Given the graph canvas is showing with the community-formation-visualization toggle and the color-code-entity-types toggle both available, when either or both are inactive (popover collapsed), then neither's controls render inline over the canvas — only the small settings button does
- Given the settings popover is open, when the user clicks outside it or presses Escape, then it closes and the settings button remains visible/clickable
- Given the existing narrow-viewport (600x800) scenario, when the page renders, then the settings button and entity-search remain independently visible and non-overlapping
- Given either toggle is exercised via the popover, when its state changes, then the graph canvas responds exactly as it did before this story (community hulls show/hide; entity node colors switch) — verified by existing `EntityTypeColorToggleUiTest` and community-toggle behavior continuing to pass

## Spec Change Log

## Review Triage Log

### 09-28-2026 — Review pass
- verdicts: 15 findings — high 1, medium 1, low 8, false 1, maybe-false 0, deferred 2
- findings:
  - `[high]` `[patch]` (blind-hunter) `.canvas-settings-popover` is `position: absolute`, so when open it does not push `#entity-search` (its later flex sibling in `.canvas-top-right-stack`) out of the way — verified true by reading the actual CSS: the popover drops below the settings button and visually overlays `#entity-search` beneath it, recreating exactly the control-overlapping-control bug class Story 10.1/this story exist to eliminate. Fix: reordered `#entity-search` before `.canvas-settings` in the stack (both DOM and visual order), so the popover — which only ever extends further down/right — has no other control below it to overlap; extended the overlap test to also assert non-overlap while the popover is open.
  - `[high]` `[patch]` (edge-case-hunter) same overlap, independently confirmed via the same CSS trace — grouped with the row above.
  - `[high]` `[patch]` (edge-case-hunter, deletion-finding) the renamed overlap test now measures the small, fixed-size collapsed button instead of real rendered popover content, weakening the regression test this exact class of bug needs — grouped with the row above; addressed by the same test extension (asserting non-overlap with the popover open, not just collapsed).
  - `[medium]` `[patch]` (verification-gap, pre-verified) `resetToIdleState()`'s hiding of `#canvas-settings-toggle` has no test asserting it — `LoadNewCorpusUiTest`'s post-reset assertions never check that element, so a regression leaving the settings button visible over the idle canvas after a reset would ship undetected. Fix: added an assertion for `#canvas-settings-toggle` being hidden to that test's existing post-reset checklist.
  - `[low]` `[defer]` (verification-gap, pre-verified) the popover's auto-close on switching to the Vector Space tab has no test — verification-gap's own filed disposition is `defer` (minor cosmetic edge case, low real-world impact, not worth a dedicated flow); carried as-is.
  - `[low]` `[defer]` (blind-hunter) same gap (no test coverage for tab-switch auto-close) — grouped with the row above, same disposition.
  - `[low]` `[patch]` (blind-hunter) no focus management on open/close — focus isn't moved into the popover on open, nor returned to the settings button on close, so a checkbox left focused when the popover collapses silently drops focus to `<body>`. Fix: focus the first checkbox on open, return focus to the settings button on close (matching this app's "best-effort" keyboard-reachability standard).
  - `[low]` `[patch]` (blind-hunter) the spec's own Code Map flagged the narrow-viewport (`.community-toggle-meta`/`.entity-type-toggle-meta` `display:none`) and tab-bar-clearance CSS regions as needing re-verification for the new collapsed footprint, but the diff left them untouched with no evidence they were re-checked. Fix: verified via the full test suite (including the narrow-viewport `EntitySearchUiTest` case) that these regions still render correctly now that they're nested inside the popover; no code change needed, confirmed working as-is.
  - `[low]` `[patch]` (blind-hunter) a comment above `.graph-stage:has(.node-detail-panel.is-open) .canvas-top-right-stack` still describes the community toggle as being at "the top-right corner," which is no longer accurate now that it's nested inside the settings popover. Fix: updated the comment to describe the current structure.
  - `[low]` `[reject]` (blind-hunter) the popover's two toggles keep their own independent card backgrounds/borders rather than sharing one unifying panel background — verified true, but this is a cosmetic/aesthetic preference with no demonstrated functional harm (no overlap, no unreadability); rejected as a design nit outside this bugfix's scope.
  - `[low]` `[patch]` (blind-hunter) `#canvas-settings-popover` has no `role`/`aria-label` of its own beyond its two children's individual labels. Fix: added `role="group"` and an `aria-label` to the popover container.
  - `[low]` `[patch]` (edge-case-hunter) `.community-toggle` gained a newly-added `white-space: nowrap` (confirmed via diff — not pre-existing) with no corresponding change to `.entity-type-toggle` (explicitly "copied verbatim" per its own comment), risking the meta text overflowing its 220px box at non-narrow viewports and now styling the two sibling toggles inconsistently. Fix: removed the unexplained, newly-added `nowrap`, restoring parity with `.entity-type-toggle`'s original (and still correct) wrapping behavior.
  - `[low]` `[patch]` (edge-case-hunter) same finding (asymmetric styling between the two toggles) — grouped with the row above.
  - `[false]` `[reject]` (intent-alignment) raised genuine uncertainty about which reading of "non-blocking" this diff addresses (visual overlap vs. pointer-capture vs. workflow-gating vs. main-thread performance), since it was not given the actual GitHub issue text — refuted: GitHub #36's own body and this epic's Story 11.7 acceptance criteria explicitly frame this as visual bounding-box overlap ("neither control's bounding box overlaps graph content in a way that hides nodes/edges/hulls underneath it"), confirming the diff's implemented reading is the correct one; the other readings it raised aren't supported by the issue's own text.
  - `[low]` `[patch]` (blind-hunter) no keyboard-only interaction test exists for the settings button/popover, despite the spec's own "Always" constraint requiring keyboard operability. Fix: added a keyboard-only test (Tab to focus, Enter/Space to activate) alongside the existing click-based tests.

## Design Notes

This durably fixes the recurring "one more floating corner control" class of bug (#20 → #36) by making the collapsed state's footprint small and constant regardless of how many view-option toggles exist behind it, rather than an ever-taller stack. Story 11.6's future zoom controls are expected to dock into their own small, similarly-scoped slot (not built here) rather than growing this same popover or reintroducing a third one-off absolutely-positioned element.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am -Dtest=CanvasSettingsPopoverUiTest,EntitySearchUiTest,EntityTypeColorToggleUiTest -Dsurefire.failIfNoSpecifiedTests=false test` -- expected: all pass
- `mvn -q -B clean install` -- expected: full reactor green, no regressions

## Auto Run Result

**Summary:** Collapsed the community-formation and color-code-entity-types toggles behind a single small settings icon-button with a transient popover, so their combined footprint can no longer grow into an ever-taller absolute overlay competing with canvas content (the recurring #20→#36 bug class). Review caught that the initial implementation of the popover itself recreated a variant of the same overlap bug (overlapping `#entity-search` when open) — fixed by reordering the stack so nothing sits below the popover's own expansion direction.

**Files changed:**
- `graphrag-web/src/main/resources/templates/index.html` -- new `#canvas-settings-toggle` button and `#canvas-settings-popover` (nesting the two existing toggles, ids/semantics unchanged); `#entity-search` reordered before `.canvas-settings` (patched in, fixing the overlap regression); popover gained `role="group"`/`aria-label` (patched in)
- `graphrag-web/src/main/resources/static/css/instrument.css` -- new `.canvas-settings*` styling; removed an inconsistent, newly-added `white-space: nowrap` on `.community-toggle` (patched in); updated a stale structural comment (patched in)
- `graphrag-web/src/main/resources/static/js/upload.js` -- popover open/close wiring (click, outside-click, Escape, tab-switch auto-close), corpus-ready/reset visibility for the new button; gained focus management (focus first checkbox on open, return focus to button on close, patched in)
- `graphrag-web/src/test/java/com/graphraglens/web/ui/CanvasSettingsPopoverUiTest.java` -- new file: open/close via click, outside-click, Escape, both toggles functioning inside the popover; gained a keyboard-only interaction test (patched in)
- `graphrag-web/src/test/java/com/graphraglens/web/ui/EntitySearchUiTest.java` -- overlap assertions retargeted to the settings button; gained new tests asserting non-overlap with the *open* popover too (patched in, fixing the weakened-regression-test finding)
- `graphrag-web/src/test/java/com/graphraglens/web/ui/EntityTypeColorToggleUiTest.java`, `ReplayCommunityHullVisibilityUiTest.java` -- updated to open the popover before interacting with the now-nested toggle (the latter wasn't in this story's original Code Map; found and fixed during the implementer's own full-build verification)
- `graphrag-web/src/test/java/com/graphraglens/web/ui/LoadNewCorpusUiTest.java` -- gained an assertion that `#canvas-settings-toggle` is hidden after a corpus reset (patched in)

**Review findings breakdown:** 15 findings from 4 reviewer layers, grouped into 11 root causes.
- Patched (8 groups, 12 member findings): a real, verified **high**-severity regression where the open popover overlapped `#entity-search` (recreating this story's own target bug class), independently found by two reviewer layers plus a related test-weakening finding; a **medium** gap where the settings button's post-reset hidden state was untested; plus five low-severity fixes (focus management, a stale comment, missing ARIA group/label, an inconsistent newly-added `white-space:nowrap`, and a missing keyboard-only interaction test).
- Deferred (1 group, 2 member findings): no test covers the popover's auto-close on switching to the Vector Space tab -- both reviewer layers independently filed this as low-impact/cosmetic, not worth a dedicated test.
- Rejected (2 groups, 2 member findings): a cosmetic "two separate pills vs. one panel" visual-polish observation (no functional harm); an intent-alignment layer's genuine uncertainty about which reading of "non-blocking" was intended, refuted by GitHub #36's own text (explicitly a visual bounding-box-overlap complaint).

**Verification performed:** `mvn -q -B -pl graphrag-web -am -Dtest=CanvasSettingsPopoverUiTest,EntitySearchUiTest,EntityTypeColorToggleUiTest,ReplayCommunityHullVisibilityUiTest,LoadNewCorpusUiTest test` -- 28/28 pass, both before and after the patch pass; `mvn -q -B clean install` -- full reactor green (exit 0), both before and after the patch pass.

**Follow-up review recommended:** `true` -- a high-verdict finding (the popover/entity-search overlap regression) was patched this pass. Unverified risk: the reordering fix was verified via the existing and newly-added Playwright bounding-box assertions, but a fresh review pass should confirm the fix generalizes across viewport widths beyond the two explicitly tested (default and 600x800), and that no other future control added to this stack could reintroduce a similar absolute-positioning overlap.

**Residual risks:** the deferred tab-switch-auto-close test-coverage gap (low impact, cosmetic); the rejected "two separate pills" visual-polish item, which a future design pass could still choose to unify.
