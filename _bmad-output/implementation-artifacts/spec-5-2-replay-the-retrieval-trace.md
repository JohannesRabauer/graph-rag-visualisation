---
title: 'Replay the Retrieval Trace'
type: 'feature'
created: '2026-09-20'
status: 'in-progress'
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
- [ ] `upload.js` -- pass `traceId`/`traceStepCount` into `appendAnswer`; render the Replay CTA row per answer -- completes Story 3.3's own unshipped AC, gives Replay its entry point
- [ ] `index.html` -- add the scrubber's static markup and Replay CTA container, `replay.js` script tag -- gives the scrubber somewhere to mount
- [ ] `graph-canvas.js` -- add `highlightStep(step, previousStep)`/`clearStepHighlights()` -- lets Replay drive canvas highlighting without duplicating Cytoscape logic
- [ ] `replay.js` (new) -- fetch trace, transport controls (keyboard-operable), step tick track, drag-to-nearest-step, step counter/caption, canvas eyebrow swap -- owns all Replay UI/state
- [ ] `instrument.css` -- style the CTA, scrubber, and active/previous-step/traversed/upcoming states per DESIGN.md tokens -- visual consistency with the rest of the app
- [ ] `MainControllerTest.java` -- assert the new static markup renders -- keeps the test suite honest about what's shipped

**Acceptance Criteria:**
- Given an answer with a trace, when it renders in chat, then a Replay CTA reads "Replay this answer's Retrieval Trace — N steps" (Story 5.2 AC1 / completes Story 3.3's AC).
- Given the Replay CTA is clicked, when the trace loads, then the scrubber appears below the graph canvas and step 1's node is highlighted active (Story 5.2 AC2).
- Given the scrubber is open, when play/pause, step-forward, or step-back is pressed, then exactly one step advances/retreats per press (or continuous autoplay until the last step), and dragging the scrubber head lands on the nearest discrete step (Story 5.2 AC3).
- Given any step, when it is shown, then the corresponding node is highlighted (active/previous-step) and a plain-language step-badge caption names the step (Story 5.2 AC4).
- Given the transport controls, when reached via keyboard alone (Tab + Enter/Space), then play/pause, step-forward, and step-back are all fully operable (Story 5.2 AC5, accessibility floor).
- Given Replay is not open, when a query is running, then no live/streaming trace visualization appears — Replay is available only after the answer (and its trace) already exist (Story 5.2 AC6).

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Design Notes

**Edge highlighting is opportunistic, not guaranteed.** Story 5.1's trace records what each search mode actually touched — ENTITY steps for Local Search (a best-effort text cross-reference, not a graph traversal) and COMMUNITY steps for Global Search — never a traversed Relationship. Two consecutive steps therefore may or may not have a real graph edge between them. Replay looks up whether `graphrag-web`'s already-rendered graph (Story 4.3) has an edge connecting the current pair of entities (via the same edge-id convention `graph-canvas.js` builds in `addRelationship`) and highlights it as traversed only when one exists; otherwise it highlights just the two nodes, with no edge for that transition. This is a direct, honest consequence of Story 5.1's own accepted trace-model limitation (logged in `deferred-work.md`), not something to paper over — a future story that reworks Local Search to genuinely traverse Relationships (also already deferred) would make edge highlighting complete.

**Replay CTA belongs here, not a Story 3.3 patch.** Story 3.3 is marked `done` in `sprint-status.yaml` and its code predates this session; rather than reopen a closed story, this spec completes the one AC it missed as part of unlocking Replay, since nothing else in the backlog would otherwise ever add it.

## Verification

**Commands:**
- `mvn test` -- expected: `MainControllerTest`'s new markup assertions pass; no other test should need to change (this story is almost entirely frontend, Story 5.1's backend is untouched)

**Manual checks (if no CLI):**
- Run the app, load the demo dataset, ask a question, confirm the Replay CTA appears with the right step count, click it, and step through with both the transport buttons and keyboard (Tab + Space/Enter) — confirm the canvas highlights update and the caption/counter track correctly, including at the first/last step boundaries.
