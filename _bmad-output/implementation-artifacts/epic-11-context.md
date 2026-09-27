# Epic 11 Context: Layout, Replay & Graph Navigation Live-Demo Fixes

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

This epic is a grab-bag of user-reported UI/layout bugs and small hardening items found while actually using the app on stream (GitHub #30-#36), grouped only for sprint tracking rather than a themed epic. It fixes the main-screen layout not filling the viewport, makes the chat thread scroll independently of a pinned composer, restores a broken Retrieval Trace Replay regression, investigates/clarifies the Vector Search comparison feature, tunes graph layout spacing for communities, adds explicit zoom controls, and fixes a recurring toggle-overlap bug — all in service of keeping the live demo legible and reliable, not adding new product FRs.

## Stories

- Story 11.1: Scale the Main Screen to Fill the Viewport
- Story 11.2: Make the Chat History Scrollable with a Pinned Composer
- Story 11.3: Fix Retrieval Trace Replay Not Rendering or Highlighting
- Story 11.4: Clarify and Verify "Compare with Vector Search" Behavior
- Story 11.5: Tune Graph Layout So Communities and Nodes Aren't Wildly Far Apart
- Story 11.6: Add Zoom In/Out Controls for the Graph Canvas
- Story 11.7: Make the Community-Formation and Color-Code-Entity-Types Toggles Non-Blocking

## Requirements & Constraints

- The frontend is Java-native: the page shell is server-rendered Thymeleaf, and all client-side behavior (graph canvas, replay scrubber, SSE consumption, zoom controls) is plain unbundled JavaScript under `graphrag-web/src/main/resources/static/js/` — no Node/npm/bundler, no TypeScript, ever (test-only exception: Playwright-Java for headless UI tests, still invoked via `mvn test`, not a build step).
- Graph exploration (pan/zoom/click) is pure client-side Cytoscape.js interaction against elements already delivered over the existing SSE progress stream — there is no dedicated backend read endpoint for it, and none should be introduced.
- Live ingestion/construction/detection progress is pushed as named SSE events on one multiplexed per-Corpus stream; no WebSocket, no second progress endpoint.
- A query is rejected with 409 at the web layer while a Corpus is still `BUILDING`/`FAILED`; once a Corpus is `READY`, reads never lock or wait — this gating is unrelated to Replay/canvas rendering bugs, which are purely client-side/trace-shape concerns.
- Retrieval Traces (and the Vector Baseline's trace) are transient, in-memory, keyed by `traceId`, and are strictly ordered step sequences — Replay's step/scrub controls depend on that fixed order.
- Community detection (Leiden via Neo4j GDS) always runs in the background regardless of any visualization toggle; toggles only control whether formation/animation is shown, never whether detection executes or communities exist.
- The Vector Baseline (Compare with Vector Search) is triggered strictly on demand from an already-answered question and produces its own independent trace/tab — it must never run automatically alongside a GraphRAG answer.
- UI tone is "instrument," not consumer-app: light-mode only, minimal chrome, no dark mode, no responsive/mobile support — the app targets a developer's own machine and a stream capture.
- Basic keyboard reachability is expected for primary controls (mode choice, community toggle, scrubber play/pause/step, Compare CTA, zoom controls) — best-effort, not a compliance program.

## Technical Decisions

- Single continuous main screen: chat panel (left rail) + graph canvas (right, remainder of space) inside one bordered "browser" frame; per Story 11.1 this region must now resize/fit to the actual viewport (`cy.resize()`/`cy.fit()` on container size changes), not stay a fixed small box — check `instrument.css` / the Thymeleaf shell for stale fixed-width/height containers.
- Chat panel is intended as a flex column: message thread is the single `overflow-y: auto` scrolling region; the Local/Global/Drift mode choice and the Composer sit outside that scroll region, pinned at the bottom, always visible (Story 11.2).
- Replay/graph-canvas visual states during trace playback: active (current step), previous-step, traversed, upcoming — driven by Cytoscape.js element classes/styles. Story 11.3 requires root-causing a real regression (likely introduced in recent Epic 9/10 work) before fixing, and explicitly checking whether the same client-side replay code path is shared across DRIFT (Epic 7), Vector (Epic 8), and the base Local/Global Replay — a shared bug would need one shared fix.
- A Retrieval Trace step touching a Community must force that specific hull visible for the duration it is current/previous, even if the community-visualization toggle is OFF, without changing every other hidden hull's state — this per-hull override behavior must keep working after Story 11.3's fix.
- Graph layout (Story 11.5): identify whether the current Cytoscape.js layout algorithm is community-aware before tuning; goal is nodes within a Community rendering at a bounded, legible distance from each other and their hull, without guess-and-check parameter changes.
- Zoom controls (Story 11.6): add visible zoom-in/zoom-out/fit-to-view buttons styled per the existing Instrument design tokens, using the same eased/animated transition as existing mouse-wheel zoom; mouse-wheel zoom must keep working unchanged alongside the new buttons.
- Floating canvas controls (community-formation toggle, color-code-entity-types toggle, and now zoom controls) have previously collided in one corner (fixed once already in Story 10.1/#20). Story 11.7 wants a durable layout pattern — e.g. a collapsible/collapsed-icon or docked settings affordance — rather than another one-off absolutely-positioned corner fix, so it scales to further floating controls being added later.
- Compare with Vector Search (Story 11.4) is scoped to investigation plus, at most, a labeling/clarity UI fix (inline explanation of what the action does and what its result is) — not a rebuild of the Vector Baseline pipeline (`ConstructVectorIndex`/`AnswerVectorBaseline` use cases, `EmbeddingPort`/`VectorStorePort`). If the underlying pipeline is found broken/incomplete, file it as its own separate follow-up issue rather than patching speculatively in this story.

## UX & Interaction Patterns

- Chat panel background is the "Panel" surface color; composer sits at the bottom with a top hairline border; mode choice sits directly above the composer as a real radio choice (dot + label), not tabs.
- Graph canvas is the hero surface: faint grid background, always pannable/zoomable/clickable, with an uppercase eyebrow title stating current state ("Knowledge Graph — Replaying Trace" / "— Resting"). A legend row lists visible Community names with color swatches when the community toggle is active.
- Retrieval Trace scrubber sits as the bottom bar of the canvas region: step-back/play-pause/step-forward transport buttons, a rail with per-step ticks (done/now/future distinctly styled), a monospace step counter, and a one-line plain-language caption of the current step.
- The node-detail panel (opened by clicking an Entity) and the Replay scrubber coexist independently on the same canvas — opening/using one must never close or block the other; this independence must be preserved by any layout or overlap fix in this epic.
- Zoom controls and existing toggles must follow the "instrument" visual language (hairline borders, small monospace/eyebrow labels, no decorative color) and remain independently visible/clickable at any supported viewport width, per Story 11.7's non-overlap requirement.

## Cross-Story Dependencies

- Story 11.7 directly extends the same class of fix as Story 10.1 (#20's toggle/entity-search overlap) — implementers should confirm whether it's the same bug resurfacing with the newer color-code-entity-types toggle before choosing a fix, per that story's design notes.
- Story 11.6's new zoom controls are another floating canvas control competing for the same corner(s) that Story 11.7 is fixing — sequence or coordinate these two so the zoom controls land inside whatever durable non-overlapping layout pattern Story 11.7 establishes, rather than becoming a third one-off element needing its own future fix.
- Story 11.3's root-cause investigation may reveal a regression shared with DRIFT (Epic 7) and Vector Baseline (Epic 8) Replay, in which case the fix should be applied once at the shared code path rather than three times.
- Story 11.4 may spawn its own follow-up issue if the Vector Baseline pipeline (Epic 8, Stories 8.2-8.5) is found broken — that follow-up is explicitly out of this epic's scope.
