---
title: 'Color-Code Entity Tags by Type, Toggleable'
type: 'feature'
created: '09-27-2026'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: [oversized]
deferred:
  - summary: >-
      No test drives "load corpus, toggle color-coding off, load a second
      corpus" to confirm the toggle resets to ON on the new corpus.
    evidence: |-
      showCorpusChip's reset (checkbox.checked = true, plus
      setEntityTypeColoringEnabled(true)) is unconditional, not gated on
      first-vs-subsequent load, so it is verified correct by direct code
      reading. A dedicated end-to-end test for this is entangled with Story
      10.4 (Allow Loading a New Corpus After One Is Already Active), which
      is not yet built — #workflow-restart-button is still a confirmed
      no-op. Revisit once 10.4 ships a real multi-load UI affordance to
      drive the test through.
    location: >-
      graphrag-web/src/main/resources/static/js/upload.js (showCorpusChip)
    severity: low
  - summary: >-
      Neither .entity-type-toggle-input nor .community-toggle-input (the
      pattern it was copied from) defines :focus/:focus-visible styling.
    evidence: |-
      Verified by reading instrument.css: both toggles rely entirely on the
      browser's unstyled default focus ring, which is easy to lose against
      the toggle's own rgba(255,255,255,0.9) card background. Pre-existing
      on the Community toggle already shipped in an earlier story; this
      diff just extends the same gap symmetrically rather than introducing
      a new one.
    location: >-
      graphrag-web/src/main/resources/static/css/instrument.css:852-891
    severity: low
baseline_revision: 'f7db8db69288c48a57b7c68d445a3862f6c978b6'
---

<intent-contract>

## Intent

**Problem:** Every Entity's Tag chip (detail panel) and graph node currently render in one flat neutral style regardless of `type`, so entities of different types (Person, Location, Concept, …) are not visually distinguishable at a glance (GitHub #22).

**Approach:** Add a toggle (default ON, matching the community-visualization toggle's own convention) that, when on, colors both the detail panel's Tag chip and that Entity's canvas node fill/border deterministically per distinct `type` value — same type always the same color, across Entities and sessions. When off, both revert to today's flat neutral style. A single new `graph-canvas.js` function computes the color for a given `type` (same deterministic-hash technique `communityColorIndex` already uses for Communities); `upload.js`'s Tag-chip renderer calls it too, so chip and node can never disagree on a type's color.

## Boundaries & Constraints

**Always:** New palette tokens (`--entity-type-1..6` fill + `-label` border, in `instrument.css`) must be distinct hex values from every existing token (`--community-*`, `--accent`, `--global`, `--drift`, `--active`) — a deliberately deeper/dustier tint band (not the light pastel Community band) so the two categorical languages read as visually separate even where hue ranges loosely overlap. The new toggle must be a real `<input type="checkbox">` inside a `<label>` (native keyboard operability, no custom JS), matching `.community-toggle`'s exact markup/CSS pattern. Toggling live-recolors *already-rendered* nodes and, if the detail panel is currently open, its Tag chip too — not just future ones. The new node-coloring style selector must sit before `.step-active`/`.step-previous` in `graph-canvas.js`'s style array so Replay's own border override still wins, exactly like the existing precedence for the base `node` selector.

**Never:** Do not add a legend for types (AC only requires the Tag chip + canvas node + toggle — a legend is scope creep, unlike Community's, which a separate existing AC required). Do not touch Community-hull colors/logic, Replay highlighting, or DRIFT/Vector Space UI. Do not persist the toggle's on/off state across a page reload (no existing toggle in this app does that either — out of scope, matches `communityVisualizationToggle`'s own unpersisted reset-per-corpus behavior).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh corpus loads | New corpus just became active | Toggle checkbox is checked (ON) | No error expected |
| Toggle ON, panel open for an Entity | User opens detail panel for a Person-typed Entity | Tag chip shows the deterministic Person-type color; that Entity's canvas node fill/border shows the same color | No error expected |
| Toggle turned OFF while panel is open | User unchecks the toggle with the panel showing a colored chip | Chip and all canvas nodes revert to flat neutral immediately, no re-open needed | No error expected |
| Same type across sessions | Two different Entities share `type` "Person" | Both always render the identical color (deterministic hash of the type string) | No error expected |
| Unknown/missing type | Entity's `type` is `null`/empty (`Entity` defaults it to `"Unknown"`) | Still receives a deterministic color for the literal string `"Unknown"` — no crash, no blank swatch | No error expected |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/static/js/graph-canvas.js` — model the new logic directly on the existing Community-color mechanism:
  - `communityColorIndex`/`communityColors` (~lines 69-87): copy this exact hash-then-`readCssVar` pattern into new `entityTypeColorIndex(type)` / `entityTypeColors(type)` functions, keyed on `--entity-type-N` (6 slots, not 10 — reuse `COMMUNITY_TOKEN_COUNT`'s pattern as `ENTITY_TYPE_TOKEN_COUNT = 6`).
  - `addEntity` (~line 618-641): on both the "upgrade placeholder" and "create new" branches, set `node.data('typeFill', colors.fill)` / `node.data('typeBorder', colors.labelColor)` from `entityTypeColors(type)`, and `node.toggleClass('type-colored', entityTypeColoringEnabled)` (new module var, default `true`).
  - `setHullsVisible` (~line 699-707): model a new `setEntityTypeColoringEnabled(enabled)` the same way — set the module var, then `cy.nodes(':not(.community-hull)').toggleClass('type-colored', enabled)` so already-rendered nodes recolor immediately (no `renderLegend()`-equivalent needed — no legend, see Never).
  - Style array inside `init` (~lines 160-175 base `node` selector, ~187 `.community-hull`, ~218 `.step-active:not(.community-hull)`): insert one new selector `node.type-colored:not(.community-hull)` — `{'background-color': 'data(typeFill)', 'border-color': 'data(typeBorder)'}` — placed after the base `node`/`edge` selectors and before `.step-active`/`.step-previous`, so Replay's border override still wins (matches this array's existing precedence-by-position convention).
  - `window.GraphCanvas` export object (~line 994-1011): add `setEntityTypeColoringEnabled`, `entityTypeColors` (needed by `upload.js`'s Tag-chip renderer — single source of truth, not a second copy of the hash), and test-support `entityNodeFillColor(identity)`/`entityNodeBorderColor(identity)` (model directly on `communityHullOpacity`, ~line 950-959: `node.style('background-color')` / `node.style('border-color')`).
- `graphrag-web/src/main/resources/templates/index.html` — inside `#canvas-top-right-stack` (~line 59-68, after the existing `.community-toggle-wrap`, both installed by spec-10-1): add a new `.entity-type-toggle-wrap` block with `<input id="entity-type-color-toggle" class="entity-type-toggle-input" type="checkbox" checked>`, copying `.community-toggle`'s exact label/text/label/meta structure and class-naming convention.
- `graphrag-web/src/main/resources/static/css/instrument.css` — `:root` block (~lines 37-56, right after `--community-10-label`): add `--entity-type-1` through `--entity-type-6` (fill) and their `-label` (border) pairs, deeper/dustier than the Community pastels (see Always). Copy `.community-toggle`/`.community-toggle-input`/`.community-toggle-text`/`-label`/`-meta` (~lines 781-825) into `.entity-type-toggle`/-`input`/-`text`/-`label`/-`meta` equivalents (same visual treatment, new class names). `.node-detail-tag` (~line 1572-1579) currently has no `border` property at all — add `border: 1px solid transparent;` so an inline `border-color` override (set only when type-colored) actually renders.
- `graphrag-web/src/main/resources/static/js/upload.js` — `renderEntityDetailTags(type)` (~line 305-316): when the new `#entity-type-color-toggle` checkbox is checked, call `window.GraphCanvas.entityTypeColors(type)` and set the chip's `style.background`/`style.borderColor`/`style.color` inline (mirrors the legend swatch's own `swatch.style.background = entry.fill` pattern, ~line 738-739 in `graph-canvas.js`); when unchecked, clear those three inline styles so the chip falls back to `.node-detail-tag`'s neutral CSS. Wire a `change` listener on the new checkbox (model directly on `communityVisualizationToggle`'s, ~line 357-365): call `window.GraphCanvas.setEntityTypeColoringEnabled(checked)`, and if `selectedEntityIdentity` is currently set (panel open), also re-run `renderEntityDetailTags(selectedEntityType)` so an already-open chip recolors live. Add a new `selectedEntityType` module var (alongside `selectedEntityIdentity`, ~line 49), set in `openEntityDetailPanel` (~line 318-330) next to where `selectedEntityIdentity` is already set. In `showCorpusChip` (~lines 653-668, next to `communityVisualizationToggle.checked = true`): also reset the new checkbox to `checked = true` and call `window.GraphCanvas.setEntityTypeColoringEnabled(true)` for the fresh-corpus default-ON requirement.
- `graphrag-web/src/test/java/com/graphraglens/web/ui/MainScreenDetailPanelUiTest.java` — reference pattern for opening the detail panel via `window.GraphCanvas.simulateTap(identity)` and reading `#entity-detail-name`/`#entity-detail-type`; the offline demo dataset deterministically types `sherlock holmes::person` as `"Person"` (`LangChain4jLlmPort.inferType`, matches/contains "holmes"/"watson"/"morstan") and any other extracted name as `"Concept"` — usable directly for exact-color assertions without any new fixture.

## Tasks & Acceptance

**Execution:**
- `instrument.css` -- add `--entity-type-1..6`/`-label` tokens, `.entity-type-toggle*` classes (copied from `.community-toggle*`), `.node-detail-tag` `border: 1px solid transparent` -- new deterministic palette + toggle chrome + makes the chip's border-color override actually visible
- `graph-canvas.js` -- add `entityTypeColorIndex`/`entityTypeColors`, `entityTypeColoringEnabled` state + `setEntityTypeColoringEnabled`, wire `addEntity`'s two branches to set `typeFill`/`typeBorder` data + the `type-colored` class, insert the new style selector, export the two new API functions + two test-support getters -- canvas-side coloring, toggleable, Replay-precedence-safe
- `index.html` -- add `.entity-type-toggle-wrap` with a real checked-by-default checkbox, inside `#canvas-top-right-stack` -- discoverable, keyboard-operable toggle next to the existing Community one
- `upload.js` -- wire the new checkbox's `change` listener, update `renderEntityDetailTags` to color/neutralize the chip via `window.GraphCanvas.entityTypeColors`, add `selectedEntityType`, reset both the checkbox and canvas state to ON in `showCorpusChip` -- single source of truth for the color, live re-render on toggle, correct fresh-corpus default
- `graphrag-web/src/test/java/com/graphraglens/web/ui/EntityTypeColorToggleUiTest.java` (new) -- Playwright UI tests covering every I/O Matrix row: toggle defaults checked on a fresh Demo Dataset load; opening the Person-typed `sherlock holmes::person` panel colors the chip and its canvas node identically (via `entityNodeFillColor`/`entityNodeBorderColor` test hooks vs. the chip's own resolved style); unchecking the toggle while the panel is open reverts both immediately; two different Entities sharing the same `type` string resolve to the same color -- proves the deterministic-and-toggleable behavior end-to-end, not just unit-level

**Acceptance Criteria:**
- Given a fresh Corpus's first run, when the main screen loads, then the Tag color-coding toggle defaults to ON
- Given the entity detail panel is open for any Entity and the toggle is ON, when its Tag chip renders, then the chip's color is assigned deterministically per distinct `type` value (same type → same color, across Entities and sessions), and that Entity's canvas node fill/border shows the identical color
- Given the toggle is OFF, when any Tag chip or graph node renders (or a previously-colored one is already on screen), then it shows the flat neutral style — live, not requiring a reload or re-open
- Given the toggle, when reached via keyboard alone (Tab/Space), then it is fully operable without a mouse

## Spec Change Log

## Review Triage Log

### 09-27-2026 — Review pass
- verdicts: 16 findings — high 0, medium 5, low 1, false 4, maybe-false 0, reject/defer-only 6 (rejects/defers below carry their own verdict; this line summarizes the severity column across all 16)
- findings:
  - `[medium]` `[patch]` Blind Hunter: `entity-type-N`/`-N-label` pairs are used as the Tag chip's own `background`/`color` (text-on-fill), not just border/background — verified via WCAG relative-luminance calc on pair 1 (`#7A8C6B` fill / `#4F5C42` text): contrast ratio ≈ 1.97:1, far below the ~4.5:1 floor for legible body text; the other five pairs follow the same close-lightness-band pattern. Patch: stop using `labelColor` as the chip's text `color` — keep chip text at the existing neutral `--ink-900` always, only `background`/`borderColor` carry the type color.
  - `[low]` `[reject]` Blind Hunter: `.node-detail-tag` unconditionally gains `border: 1px solid transparent`, adding ~2px to the chip's box even when color-coding is off/unused — real but imperceptible in normal use (2px), no layout invariant depends on the chip's exact size; not worth a code change.
  - `[medium]` `[patch]` Blind Hunter: `entityTypeColorIndex` hashes the raw `type` string case-sensitively with no normalization, so the AC's own "same type always the same color" guarantee would silently break the moment two Entities' `type` differ only in case — plausible in practice since the real (non-stub) `OpenAiLlmPort` lets an LLM free-form the `type` string per call, unlike the deterministic offline stub. Patch: lowercase-normalize `type` before hashing in `entityTypeColorIndex` (one line, single source of truth already shared by chip and node).
  - `[reject]` Blind Hunter: new test hardcodes demo-dataset identities (`holmes::person`, `king::concept`) without asserting they exist — matches the exact same already-accepted convention `MainScreenDetailPanelUiTest` uses for `sherlock holmes::person`; not a new defect introduced by this diff.
  - `[false]` `[reject]` Blind Hunter: "reset to ON" implemented in three places (module var, checkbox, explicit `setEntityTypeColoringEnabled(true)` call) — verified the explicit call in `showCorpusChip` is redundant (`GraphCanvas.init()`, called earlier in the same function, already resets the module var to `true`), but redundant-and-harmless is not a bad outcome — no incorrect behavior results.
  - `[low]` `[defer]` Blind Hunter: no test drives "load corpus → toggle off → load a second corpus → toggle resets to ON" — the reset code itself is verified correct by inspection (`showCorpusChip`'s reset is unconditional, not gated on first-vs-subsequent load), but a dedicated multi-load test is entangled with Story 10.4's not-yet-built "load a new corpus" affordance (currently a no-op per its own spec) — not worth adding until that lands.
  - `[low]` `[defer]` Blind Hunter: neither the new `.entity-type-toggle-input` nor its `.community-toggle-input` model defines `:focus`/`:focus-visible` styling — real, but pre-existing (copied verbatim from the already-shipped Community toggle, which has the identical gap) and not caused by this story.
  - `[reject]` Blind Hunter: `## Spec Change Log`/`## Review Triage Log` were empty and `warnings: [oversized]` had no accompanying note — fix would edit this build's spec; excluded from patch/defer per triage rules.
  - `[medium]` `[patch]` Edge Case Hunter: a placeholder node (`ensureNode`, created when a Relationship/Community event references an identity before its own `entity-extracted` event arrives) has no `type` set, yet `cy.on('tap', 'node', ...)` doesn't exclude placeholders, so tapping one calls `renderEntityDetailTags(undefined)` — which, with the toggle ON, still calls `entityTypeColors(undefined)` and colors the chip from a hash of `''`, while the canvas node itself (never touched by `addEntity`) stays plain/neutral. Verified reachable: the placeholder path is pre-existing, already-anticipated codebase behavior (see `ensureNode`'s own comments), not hypothetical. Patch: guard chip coloring on `type` being truthy (`if (type && entityTypeColorToggle.checked && ...)`), matching the edge-case-hunter's own suggested guard.
  - `[medium]` `[patch]` Verification-gap: the new style selector `node.type-colored:not(.community-hull)` is deliberately positioned before `.step-active`/`.step-previous` so Replay's border override still wins — verified correct by inspection, but no test drives an actual Replay step onto a type-colored Entity node to confirm the precedence holds at runtime; a future style-array reorder could silently break Replay's visual step indicator on the common (type-coloring-ON-by-default) case with nothing failing. Patch: add a test that steps Replay onto an Entity node (reusing `DriftTreeReplayUiTest`'s trace-stepping pattern) and asserts `entityNodeBorderColor` resolves to the active/previous ring color, not the entity's own type border.
  - `[false]` `[reject]` Verification-gap (Other findings): the border-color assertion in `openingAPersonTypedEntityColorsTheChipAndItsCanvasNodeIdentically` deliberately checks against a different, never-tapped same-type sibling node rather than the tapped node itself — already documented in the test's own comments as a correct workaround for the pre-existing, out-of-scope keyboard-focus-ring override; the layer itself flagged this as non-reportable, not a gap.
  - `[false]` `[reject]` Intent-alignment-auditor: claimed no test independently verifies ambient (non-panel-dependent) node coloring — refuted: `twoDifferentEntitiesSharingATypeResolveToTheSameColor` already reads `entityNodeFillColor`/`entityNodeBorderColor` directly via `page.evaluate`, for two Entities neither of which is ever tapped in that test.
  - `[medium]` `[patch]` Intent-alignment-auditor: the spec's own AC ("reached via keyboard alone (Tab/Space)... fully operable") is asserted only by markup choice (a native, unmodified `<input type="checkbox">`) — no test drives the toggle via `page.keyboard()` to confirm it. Patch: add a Playwright test that Tabs to `#entity-type-color-toggle` and presses Space, asserting its checked state flips and the effect applies.
  - `[medium]` `[patch]` Intent-alignment-auditor: same root cause as the Verification-gap Replay-precedence finding above (grouped, shares its `patch` route) — the "reconciled visually... so none of the three color languages become ambiguous" requirement is argued only in prose/comments, never exercised by a test that renders Replay's active-state alongside type-coloring.
  - `[medium]` `[patch]` Intent-alignment-auditor: same root cause as the Edge Case Hunter's placeholder-node finding above (grouped, shares its `patch` route) — the spec's own "Unknown/missing type" I/O-matrix row ("no crash, no blank swatch") has no test exercising an Entity whose `type` is actually null/blank.
  - `[false]` `[reject]` Intent-alignment-auditor: claimed the AC's "across... sessions" phrasing is untested — not needed: the coloring is a pure deterministic function of `type` with no stored/randomized state, so consistency across sessions holds by construction, the same way the pre-existing `communityColorIndex` is trusted without a reload-based test.
- patch outcomes: all 5 patch-routed findings fixed and verified —
  - low-contrast chip text: `chip.style.color` no longer set to `labelColor`; chip text stays at the neutral `--ink-900` from `.node-detail-tag`'s own CSS, only `background`/`borderColor` carry the type color.
  - case-sensitive hash: `entityTypeColorIndex` now lowercases `type` before hashing; both callers (chip and node) share the one function, so both benefit automatically.
  - placeholder/null-type parity: `renderEntityDetailTags` now guards on `type` being truthy before coloring, falling through to the existing neutral-style branch otherwise — matches a placeholder node's untouched neutral canvas node.
  - Replay-precedence test: added `replayingATraceOverridesTheEntityTypeBorderWithTheActiveStepRing` — steps Replay onto an `ENTITY` step and asserts `entityNodeBorderColor` resolves to `--active`, not the entity's own type border.
  - keyboard-operability test: added `toggleIsFullyOperableViaKeyboardAlone` — drives the toggle via real `Tab`/`Space` key presses and asserts both the checkbox and the open chip/node revert live.
  - Re-verified: `mvn -q -B -pl graphrag-web -am test -Dtest=EntityTypeColorToggleUiTest` — 6/6 pass; `mvn -q -B clean install` — full reactor, 89/89 tests, 0 failures/errors.

## Design Notes

The new `--entity-type-N`/`-label` palette is a deliberately deeper/dustier tint band than the Community hulls' light pastels (e.g. dusty sage/steel/mauve/tan vs. Community's airy orange/blue/green/purple/gold) — with the design system's hue wheel already this saturated across Community + mode-accent + Drift + active-state colors, exact hue novelty isn't achievable; visual separation instead comes from (a) genuinely distinct hex values from every existing token, (b) a different lightness/saturation band read as its own "language," and (c) style-array position guaranteeing Replay's border override always wins over a type-colored border, exactly as it already wins over the base node border today.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am test -Dtest=EntityTypeColorToggleUiTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: all new tests pass
- `mvn -q -B clean install` -- expected: full reactor green (every module, every Playwright UI test)

**Manual checks (if no CLI):**
- Load the app, load the Demo Dataset, open a few different Entities' detail panels: confirm same-type Entities always show the same Tag chip color and matching node color, then toggle off and confirm everything reverts to neutral immediately.

## Auto Run Result

- Status: **done**
- Summary: Added a deterministic, toggleable (default ON) per-type color-coding of Entity Tag chips and their matching canvas nodes. `graph-canvas.js` gained `entityTypeColorIndex`/`entityTypeColors` (same hash-then-`readCssVar` technique `communityColorIndex` already uses, case-normalized) as the single source of truth, shared by both the canvas node styling and `upload.js`'s Tag-chip renderer. A new checkbox next to the existing Community-visualization toggle controls it live, resets to ON on every fresh corpus, and is reachable/operable via keyboard alone.
- Files changed:
  - `graphrag-web/src/main/resources/static/css/instrument.css` — 6 new `--entity-type-N`/`-label` token pairs (a deeper/dustier band, distinct from every existing token) and `.entity-type-toggle*` classes copied from `.community-toggle*`; `.node-detail-tag` gained a transparent 1px border so an inline border-color override renders.
  - `graphrag-web/src/main/resources/static/js/graph-canvas.js` — `entityTypeColorIndex`/`entityTypeColors`, `entityTypeColoringEnabled` state + `setEntityTypeColoringEnabled`, `addEntity` now sets `typeFill`/`typeBorder` data + toggles the `type-colored` class, a new style selector positioned before Replay's `.step-active`/`.step-previous` so Replay's border override still wins, plus test-support `entityNodeFillColor`/`entityNodeBorderColor`.
  - `graphrag-web/src/main/resources/templates/index.html` — new `.entity-type-toggle-wrap` (checked-by-default checkbox) inside `#canvas-top-right-stack`.
  - `graphrag-web/src/main/resources/static/js/upload.js` — wired the new checkbox, `renderEntityDetailTags` now colors/neutralizes the chip via the shared `entityTypeColors` function, `selectedEntityType` added for live re-render on toggle, `showCorpusChip` resets both the checkbox and canvas state to ON per fresh corpus.
  - `graphrag-web/src/test/java/com/graphraglens/web/ui/EntityTypeColorToggleUiTest.java` (new) — 6 Playwright UI tests covering every I/O Matrix row plus the two runtime-precedence/keyboard patches.
- Review findings breakdown:
  - Patched (5, all medium): low-contrast chip text; case-sensitive type hash breaking the "same type same color" guarantee for real LLM-driven casing; a placeholder node's chip/node color-parity break; an untested Replay-border-precedence runtime guarantee; an untested keyboard-operability AC. All fixed and reverified with new/adjusted tests.
  - Deferred (2, both low): no end-to-end test for "toggle off → load a second corpus → resets to ON" (entangled with Story 10.4's not-yet-built multi-load affordance); no `:focus`/`:focus-visible` styling on the new toggle (pre-existing gap, copied symmetrically from the already-shipped Community toggle).
  - Rejected (6): unconditional `border: 1px solid transparent` on `.node-detail-tag` (imperceptible 2px, no layout invariant depends on it); test hardcoding demo-dataset identities (matches existing sibling-test convention); a redundant-but-harmless third reset call-site; empty Spec Change Log/Review Triage Log placeholders (fix would edit the spec); the deliberate sibling-node border-color test comparison (already documented as a correct workaround, flagged non-reportable by its own reviewer); the "ambient node coloring untested" claim (refuted — an existing test already reads node color with no tap involved); the "across sessions" claim (satisfied by construction, no test needed).
- Follow-up review recommendation: **true** — two or more `medium` entries were patched on this first pass. Named residual risk: the new Replay-precedence test needed an explicit wait for the async trace/scrubber population to avoid a race (per the implementation subagent's own note); this class of timing-sensitive Playwright wait has a history of needing follow-up tuning in this codebase and is worth a second look for flakiness under load.
- Verification performed: `mvn -q -B -pl graphrag-web -am test -Dtest=EntityTypeColorToggleUiTest` (6/6 pass, before and after patching — 4/4 then 6/6) and `mvn -q -B clean install` (full reactor, 89/89 tests, 0 failures/errors, after patching).
- Residual risks: the named Replay-test timing sensitivity above; the two deferred low-severity items (multi-load reset coverage, toggle focus styling).
