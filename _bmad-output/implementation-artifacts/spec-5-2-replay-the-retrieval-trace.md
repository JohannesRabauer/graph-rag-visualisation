---
title: 'Replay the Retrieval Trace'
type: 'feature'
created: '2026-09-20'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '75375ecf4048fcde6181dc6349cfb58b2b356cc9'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 5.1 makes every query's Retrieval Trace fetchable via `GET /api/traces/{traceId}`, but nothing in the UI ever fetches or plays one back. Worse, the chat never even offers the entry point: Story 3.3's own AC required "a Replay CTA appears, offering the step-by-step Retrieval Trace," but the shipped chat rendering (`appendAnswer` in `upload.js`) never added it, despite Story 3.3 being marked done — verified by grep, no "Replay"/CTA markup exists anywhere in the frontend today.

**Approach:** Add the missing Replay CTA to each chat answer (using the `traceId`/`traceStepCount` Story 5.1's query response already returns — no backend change needed), and build the scrubber: transport controls (step-back/play-pause/step-forward) docked below the graph canvas, a discrete step tick track, a step counter, and a plain-language step-badge caption. Clicking a step (or autoplay/step press) highlights the corresponding node on the Cytoscape canvas (Story 4.3) — the current step's node gets the active/warm state, the previous step's node keeps a distinct accent ring, and any graph edge directly connecting the two (if one already exists among the rendered Relationships) is shown as traversed; edges among not-yet-reached steps that do exist are shown dashed/upcoming. Replay opens only after an answer (and its trace) already exist — no live/streaming visualization.

## Boundaries & Constraints

**Always:** The Replay CTA reads "Replay this answer's Retrieval Trace — N steps" using the same `traceId`/`traceStepCount` this story's own new fetch already has, per answer message (LOCAL and GLOBAL both, reusing the same rendering — Story 3.3's own AC). Steps are discrete: play/pause, step-forward, step-back, and dragging the scrubber head all land on a whole step, never an in-between position. Transport controls (play/pause, step-forward, step-back) are reachable and operable via keyboard alone (Tab + Enter/Space at minimum). Replay is strictly post-hoc: it opens only after `GET /api/traces/{traceId}` returns, never during a live query.

**Never:** Do not change `GET /api/traces/{traceId}`'s response shape or `RetrievalTraceStore` (Story 5.1's backend is complete and frozen for this story). Do not require every step to have a highlightable edge — Story 5.1's trace records ENTITY/COMMUNITY touches, not traversed Relationships, so an edge is shown only when one already exists in the rendered graph between two consecutive steps' entities; a step with no such edge still highlights its node, with no edge shown for that transition (a real, accepted trace-model limitation, not a bug — see Design Notes). Do not implement the Explore page (Epic 6) or Retrieval Trace capture changes (Story 5.1's job). Do not add a second graph-rendering surface — Replay reuses the existing `#graph-canvas`/`GraphCanvas` from Story 4.3.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Answer with a multi-step trace | Chat answer rendered, `traceStepCount` > 0 | Replay CTA shown; clicking it fetches the trace and opens the scrubber at step 1 of N, with step 1's node highlighted active | N/A |
| Answer with a zero-step trace (`noAnswer` or no match) | `traceStepCount` == 0 | Replay CTA still shown ("— 0 steps"); clicking it opens the scrubber showing a plain-language "nothing was touched" state, no transport controls enabled | N/A |
| Autoplay reaches the last step | Play/pause pressed, steps remaining | Advances one step per interval until the last step, then stops (does not loop) | N/A |
| Step-forward/step-back at either end | At step 1 (back) or step N (forward) | No-op — stays at the boundary step, never wraps or errors | N/A |
| Dragging the scrubber head between two ticks | Pointer released mid-track | Snaps to the nearest discrete step, never a fractional position | N/A |
| Two consecutive steps with no direct graph edge between their entities | e.g. two ENTITY steps not connected by any persisted Relationship | Both nodes still highlight (active/previous-step); no edge is drawn/highlighted for that transition | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-web/src/main/resources/static/js/upload.js` (`appendAnswer`, the chat submit `.then()`) — pass `result.body.traceId`/`result.body.traceStepCount` into `appendAnswer`; render a Replay CTA row (dashed border, per DESIGN.md `components.replay-cta`) inside the answer message, reading `Replay this answer's Retrieval Trace — {N} steps`.
- `graphrag-web/src/main/resources/templates/index.html` — add the scrubber's static markup (hidden by default): transport buttons (step-back/play-pause/step-forward, real `<button>`s for keyboard access), a step-tick track container, a step counter span, and a caption span, docked below `#graph-canvas`. Reuse the existing `.canvas` region — no new page/route.
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` (`window.GraphCanvas`) — add `highlightStep(step, previousStep)`: sets the active/previous-step CSS classes on the corresponding node (matched by `RetrievalStep.identifier` — the same `normalizedIdentity()`/community-id strings already used for ENTITY/COMMUNITY nodes, per Story 5.1's Code Map), and, when a Relationship-derived edge already exists between the two (found via the edge id convention `graph-canvas.js` already builds for `addRelationship`), marks it traversed; add `clearStepHighlights()` to reset when Replay closes.
- `graphrag-web/src/main/resources/static/js/replay.js` (NEW) — owns the scrubber: fetches `GET /api/traces/{traceId}` on CTA click, tracks current step index, wires transport buttons/keyboard/drag-to-nearest-tick, calls `GraphCanvas.highlightStep(...)` per step change, renders the step counter/caption text, and the canvas eyebrow swap to "Knowledge Graph — Replaying Trace" while open (reverting to "Knowledge Graph — Resting"'s sibling wording on close). Kept out of `upload.js`/`graph-canvas.js`, matching Story 4.3's file-per-concern convention.
- `graphrag-web/src/main/resources/static/css/instrument.css` — style the Replay CTA row, the scrubber (transport buttons, tick track with done/now/future states, step counter, caption), and the active/previous-step/traversed/upcoming visual states, all using the DESIGN.md tokens already defined (`{colors.active}`, `{colors.accent}`, `{components.scrubber}`/`{components.step-badge}`/`{components.node-active}`/`{components.node-previous-step}`/`{components.edge-traversed}`/`{components.edge-upcoming}`).
- `graphrag-web/src/main/resources/templates/index.html` — add the Cytoscape.js `<script>` load order is unaffected; add `replay.js`'s `<script>` tag after `graph-canvas.js`.
- `graphrag-web/src/test/java/com/graphraglens/web/MainControllerTest.java` — assert the scrubber's static markup/script tag are present.

## Tasks & Acceptance

**Execution:**
- [x] `upload.js` -- pass `traceId`/`traceStepCount` into `appendAnswer`; render the Replay CTA row per answer -- completes Story 3.3's own unshipped AC, gives Replay its entry point
- [x] `index.html` -- add the scrubber's static markup and Replay CTA container, `replay.js` script tag -- gives the scrubber somewhere to mount
- [x] `graph-canvas.js` -- add `highlightStep(steps, currentIndex)`/`clearStepHighlights()` -- lets Replay drive canvas highlighting without duplicating Cytoscape logic
- [x] `replay.js` (new) -- fetch trace, transport controls (keyboard-operable), step tick track, drag-to-nearest-step, step counter/caption, canvas eyebrow swap -- owns all Replay UI/state
- [x] `instrument.css` -- style the CTA, scrubber, and node/edge highlight tokens (as Cytoscape style rules in `graph-canvas.js`, since node/edge rendering is canvas-drawn, not DOM CSS — see Implementation Notes) per DESIGN.md tokens -- visual consistency with the rest of the app
- [x] `MainControllerTest.java` -- assert the new static markup renders -- keeps the test suite honest about what's shipped

**Acceptance Criteria:**
- Given an answer with a trace, when it renders in chat, then a Replay CTA reads "Replay this answer's Retrieval Trace — N steps" (Story 5.2 AC1 / completes Story 3.3's AC).
- Given the Replay CTA is clicked, when the trace loads, then the scrubber appears below the graph canvas and step 1's node is highlighted active (Story 5.2 AC2).
- Given the scrubber is open, when play/pause, step-forward, or step-back is pressed, then exactly one step advances/retreats per press (or continuous autoplay until the last step), and dragging the scrubber head lands on the nearest discrete step (Story 5.2 AC3).
- Given any step, when it is shown, then the corresponding node is highlighted (active/previous-step) and a plain-language step-badge caption names the step (Story 5.2 AC4).
- Given the transport controls, when reached via keyboard alone (Tab + Enter/Space), then play/pause, step-forward, and step-back are all fully operable (Story 5.2 AC5, accessibility floor).
- Given Replay is not open, when a query is running, then no live/streaming trace visualization appears — Replay is available only after the answer (and its trace) already exist (Story 5.2 AC6).

## Implementation Notes

- Implemented directly (no subagent dispatch). All six execution tasks completed. `CorpusController`/`RetrievalTraceStore` (Story 5.1's backend) were read for reference only and left untouched, as required.
- `upload.js`: `appendAnswer` now takes `traceId`/`traceStepCount` and, whenever `traceId` is present (every successful query response, LOCAL and GLOBAL, matched or not), appends a `.replay-cta` `<button>` reading `Replay this answer's Retrieval Trace — {N} steps` — including the `noAnswer`/zero-step case, per the I/O matrix's second row. Also shows the new `#graph-eyebrow` label once a Corpus loads (the graph-canvas's own "Resting" state label, mirroring the idle-state eyebrow), since nothing else in the Code Map owned making that label visible in the first place.
- `index.html`: added `#graph-eyebrow`, the hidden `#replay-scrubber` (close button, step-back/play-pause/step-forward as real `<button>`s, `#replay-tick-track`, step counter, caption), and `replay.js`'s `<script>` tag between `graph-canvas.js` and `upload.js`.
- `graph-canvas.js`: added four new Cytoscape style rules (`node.step-active`, `node.step-previous`, `edge.edge-traversed`, `edge.edge-upcoming`) reusing the existing `readCssVar` token pattern, plus `highlightStep(steps, currentIndex)` and `clearStepHighlights()`. `highlightStep` takes the *whole* ordered step list and the current index (not just `(step, previousStep)` as the Code Map sketched) so it can also draw the Design Notes' "upcoming" dashed preview for any other consecutive-step pair that already has a graph edge — the two-argument signature couldn't do that without either duplicating trace state in `graph-canvas.js` or a third argument; passing the full list was the simpler option, and Code Map is non-frozen guidance, not a Boundaries/AC requirement. `identifier` resolves straight to the node id for ENTITY (and any future RELATIONSHIP) steps and to `'community::' + identifier` for COMMUNITY steps, matching `addCommunity`'s compound-node id convention. Edge lookup matches on each edge's `source`/`target` data in either direction rather than reconstructing `addRelationship`'s id string (which also encodes the relationship type, not available on a `RetrievalStep`) — same underlying data, a direction-agnostic, type-agnostic lookup.
- `replay.js` (new): delegated document-level click listener for `.replay-cta` (works for every CTA `upload.js` appends, regardless of script load order); fetches `GET /api/traces/{traceId}` on click; renders one `<button class="replay-tick">` per step; wires click-to-step, pointerdown/pointermove-drag-to-nearest-step (continuous snap, matching the "always lands on a whole step" constraint), and the three transport buttons. Autoplay runs on a 1.4s `setInterval`, stops (does not loop) at the last step, and step-forward/step-back/tick-click/drag all call `stopPlayback()` first so manual input always overrides autoplay. Transport buttons are `disabled` (not just visually inert) whenever there are zero steps, and play/step-forward are additionally disabled at the last step — satisfies the "no transport controls enabled" zero-step requirement and the "no-op at the boundary" requirement via native `disabled` semantics rather than silent no-ops.
- `instrument.css`: added `.replay-cta`, `.graph-eyebrow`, and the full `.replay-scrubber`/`.replay-transport-button`/`.replay-track`/`.replay-tick`/`.replay-meta` rule set, all built from existing `:root` tokens (no new custom properties needed — `instrument.css` already defines every color DESIGN.md's new component tokens reference). Node/edge highlight *rendering* itself lives in `graph-canvas.js`'s Cytoscape `style` array, not in `instrument.css` — Cytoscape draws nodes/edges to a canvas, not the DOM, so CSS selectors can't reach them; `instrument.css` remains the single source of the color tokens graph-canvas.js's existing `readCssVar` helper reads, consistent with how the pre-existing community-hull styling already works.
- `MainControllerTest.java`: added `rendersTheReplayScrubberMarkupAndScript`, asserting `#graph-eyebrow`, `#replay-scrubber`, the three transport button ids, `#replay-tick-track`, `#replay-step-counter`, `#replay-caption`, and the `replay.js` script tag are all present in the rendered page.
- Verification: `mvn test` (`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`) — full reactor `BUILD SUCCESS`, 24 tests in `graphrag-web` (up from 21; the 3 new `MainControllerTest` assertions are additive, no existing test needed to change), 0 failures/errors; `node --check` passed on all three touched/new JS files as a lightweight syntax gate (no JS test runner exists in this repo).
- Manual browser verification (transport buttons/keyboard/drag against a running app) was not performed in this sandbox — no browser is available here. This is the one Verification-section manual check left undone; the automated `mvn test` check specified by this story's own Verification section was run and passes.
- Nothing in this spec's Tasks & Acceptance or Boundaries & Constraints was left incomplete. The only judgment call beyond the Code Map's literal text is `highlightStep`'s signature (see above), made to honor the frozen Intent's "upcoming" edge-preview behavior without duplicating trace-order state across two files.

### Review round (build session)

Applied all 10 `patch`-routed findings from the 3-layer review directly: fixed the edge-highlighting ordering bug (all three reviewers found it independently), scoped step-highlight CSS to exclude compound hull nodes, made edge lookup return all parallel matches instead of the first, added `setPointerCapture` to the tick-track drag, surfaced a visible caption on a failed trace fetch instead of silently doing nothing, guarded against an out-of-order response when two Replay CTAs are clicked in quick succession, exported `window.Replay.close()` and call it before `GraphCanvas.init()` so switching corpora mid-Replay can't leave a stale autoplay running, removed the dead `stepCountHint` parameter, added the missing `#replay-close` test assertion and a GLOBAL-mode call-out in the manual-checks text, and added an `aria-live` region to the step counter/caption. Re-ran `mvn test` (JDK 25) and `node --check` on all three touched JS files after each fix — final state: `BUILD SUCCESS`, 24 `graphrag-web` tests (3 new/updated), no syntax errors.

## Spec Change Log

## Review Triage Log

Three-layer review (blind-hunter, edge-case-hunter, verification-gap) ran in parallel against the full diff. All three independently found the same root-cause edge-highlighting bug.

- **patch** — `highlightStep` marked *every* consecutive step-pair edge as `edge-upcoming` regardless of whether that pair was before or after `currentIndex`, so already-passed edges stayed dashed instead of reflecting they'd been reached (blind-hunter + edge-case-hunter + verification-gap, same root cause, found independently three times). Fixed the loop to only mark pairs from `currentIndex` onward.
- **patch** — `node.step-active`/`node.step-previous` set a fixed `width`/`height`, which would apply to a `.community-hull` compound node too if a COMMUNITY step were highlighted, breaking its auto-sizing around member nodes (edge-case-hunter). Scoped both selectors to `:not(.community-hull)` and added compound-safe border-only variants.
- **patch** — `findEdgeBetween` stopped at the first matching edge; two entities connected by more than one Relationship would only get one of them styled (edge-case-hunter). Renamed to `findEdgesBetween`, returns all matches, both the upcoming-preview loop and the traversed-edge assignment now iterate all of them.
- **patch** — The tick-track drag never called `setPointerCapture`, so a fast drag past the track's left/right edge stopped updating instead of clamping to the first/last step (blind-hunter + edge-case-hunter, same root cause). Added `setPointerCapture` on `pointerdown`; the track now keeps receiving `pointermove`/`pointerup` outside its bounds.
- **patch** — A failed/non-OK `GET /api/traces/{traceId}` fetch was swallowed silently — clicking Replay appeared to do nothing (blind-hunter, matching verification-gap's coverage-gap framing). Added a `loadError` state: Replay now opens showing a plain-language "could not be loaded" caption instead of staying silent.
- **patch** — Rapid clicks on two different Replay CTAs before the first fetch resolved could let a stale response overwrite the newer one (edge-case-hunter). Added a request-sequence token; only the most recently requested trace's response is applied.
- **patch** — Loading a new Corpus while Replay was open left its `setInterval` autoplay running against the canvas `GraphCanvas.init()` was about to destroy/rebuild, calling `highlightStep` with a stale trace's node ids (edge-case-hunter). Exported `window.Replay.close()` (matching the established `window.GraphCanvas` pattern) and call it from `showCorpusChip()` before `GraphCanvas.init()`.
- **patch** — `openReplay`'s `stepCountHint` parameter was accepted but never used, with a comment claiming otherwise (blind-hunter). Removed the dead parameter.
- **patch** — `#replay-close` existed in the new markup but wasn't asserted by `MainControllerTest`, and the spec's own manual-checks text never called out testing GLOBAL mode explicitly, despite AC1/Boundaries requiring both modes to work and GLOBAL exercising a materially different code path (blind-hunter, two small findings, same "documentation/test completeness" theme). Added the missing test assertion and updated the manual-checks wording.
- **patch** — The step counter/caption had no `aria-live` region, so a screen-reader user driving the transport controls by keyboard (the exact AC5 scenario) got no announcement that the step changed (blind-hunter). Added `aria-live="polite"` to the meta row.
- **defer** — No automated coverage exists for the Replay CTA's rendering, canvas highlighting, or transport boundary logic (blind-hunter + all three of verification-gap's findings, same root cause: no JS test framework in this repo, matching the same AD-15 constraint already accepted for Stories 4.3/5.1). Logged in `deferred-work.md`.
- **defer** — A trace step whose node isn't currently rendered/visible (e.g. hidden by the community toggle) advances the counter/caption with no visible highlight (blind-hunter). Narrow interaction between two independently-toggleable features; fix is more than a simple correction. Logged in `deferred-work.md`.
- **false** — Claimed risk of a frontend/backend trace JSON field-name mismatch (blind-hunter). Disproof: manually cross-checked `CorpusController.stepPayload()` (Story 5.1) against `replay.js`'s field usage — `kind`/`identifier`/`label` match exactly, including the Java enum's `.name()` string values (`ENTITY`/`RELATIONSHIP`/`COMMUNITY`) against `replay.js`'s string comparisons.
- **false** — Claimed two consecutive steps could resolve to the same node id, causing a `step-active`/`step-previous` class conflict (edge-case-hunter). Disproof: `AnswerGlobalSearch` iterates each distinct Community exactly once per trace (no repeats possible), and `entityStepsNamedIn`'s match list contains at most one entry per distinct Entity identity — no code path in either search mode can produce a duplicate-identifier step today.
- **false** — Claimed the Code Map's "Replay CTA container" wording implied a static markup element in `index.html` that wasn't added (edge-case-hunter). Disproof: the Approach text explicitly says "Add the missing Replay CTA to each chat answer" — the CTA is correctly built per-message in `upload.js`, matching the actual approved intent; the Code Map phrase was just loose wording, not a missed requirement.

## Design Notes

**Edge highlighting is opportunistic, not guaranteed.** Story 5.1's trace records what each search mode actually touched — ENTITY steps for Local Search (a best-effort text cross-reference, not a graph traversal) and COMMUNITY steps for Global Search — never a traversed Relationship. Two consecutive steps therefore may or may not have a real graph edge between them. Replay looks up whether `graphrag-web`'s already-rendered graph (Story 4.3) has an edge connecting the current pair of entities (via the same edge-id convention `graph-canvas.js` builds in `addRelationship`) and highlights it as traversed only when one exists; otherwise it highlights just the two nodes, with no edge for that transition. This is a direct, honest consequence of Story 5.1's own accepted trace-model limitation (logged in `deferred-work.md`), not something to paper over — a future story that reworks Local Search to genuinely traverse Relationships (also already deferred) would make edge highlighting complete.

**Replay CTA belongs here, not a Story 3.3 patch.** Story 3.3 is marked `done` in `sprint-status.yaml` and its code predates this session; rather than reopen a closed story, this spec completes the one AC it missed as part of unlocking Replay, since nothing else in the backlog would otherwise ever add it.

## Verification

**Commands:**
- `mvn test` -- expected: `MainControllerTest`'s new markup assertions pass; no other test should need to change (this story is almost entirely frontend, Story 5.1's backend is untouched)

**Manual checks (if no CLI):**
- Run the app, load the demo dataset, ask a question in **both LOCAL and GLOBAL mode** (they exercise different code paths — ENTITY vs. COMMUNITY steps, different node-id prefixes, different caption wording), confirm the Replay CTA appears with the right step count for each, click it, and step through with both the transport buttons and keyboard (Tab + Space/Enter) — confirm the canvas highlights update and the caption/counter track correctly, including at the first/last step boundaries.
