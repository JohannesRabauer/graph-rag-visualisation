---
title: 'Toggle Community Formation Visualization'
type: 'feature'
created: '2026-09-19'
status: 'ready-for-dev'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '533efa370bde0f3c7e6a15aa16d8c4ea2c1ab103'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The main screen has no graph rendering at all yet — Entities, Relationships, and Communities already exist server-side (Epic 2, Story 4.1/4.2), but nothing draws them, and the community-visualization toggle checkbox already sitting in the DOM (`#community-visualization-toggle`) only logs to the console.

**Approach:** Add a Cytoscape.js graph canvas (per AD-15) to the main screen that renders the active Corpus's Entities/Relationships as nodes/edges, plus Community hulls (as pale compound-node backgrounds) that fold in as new `community-detected` SSE events arrive. The toggle controls only whether the hull overlay/animation is shown; nodes/edges render regardless, and the backend stays unaware of the toggle's state (AD-6).

## Boundaries & Constraints

**Always:** Toggle affects only client-side rendering — the backend emits identical progress events regardless of toggle state (AD-6). New SSE events reuse the existing single per-corpus stream and `{type, data}` envelope (AD-12), no new endpoint. Cytoscape.js loads as a plain, unbundled, pinned-version CDN `<script>` — no bundler/build step (AD-15). Community hull colors/labels reuse the DESIGN.md `community-1..5` tint tokens already defined in `instrument.css`. Nodes/edges render whether the toggle is ON or OFF; only the hull layer and its fold-in animation are toggle-controlled.

**Never:** Do not add per-Corpus data isolation to `GraphStorePort`/the in-memory store — it is process-global today (inherited from Stories 2.4/4.1/4.2, confirmed during investigation); logged separately in `deferred-work.md` rather than fixed here. Do not build the Explore page (Epic 6) — no pan/zoom/click-to-inspect, main screen only. Do not build Retrieval Trace replay visuals (Epic 5) — no scrubber/step UI. Do not introduce a second real-time transport or a bundler/npm toolchain.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh Corpus, first run | Corpus just ingested; detection starts | Toggle defaults ON; hulls fold in one by one as `community-detected` events arrive | N/A |
| Toggle switched OFF mid-animation | User unchecks while detection is running | Hull layer + animation hidden immediately; nodes/edges stay visible; detection keeps running server-side, unaffected | N/A |
| Toggle switched back ON | User re-checks after being OFF | Hulls for already-detected Communities render immediately (no replay of the fold-in); future ones still animate in as they arrive | N/A |
| Canvas requested before any entities exist | Page/SSE connects before `entity-extracted` events sent | Canvas shows only the existing grid background, no nodes, no error | N/A |
| SSE connection drops mid-detection | `EventSource.onerror` fires | Whatever graph/hulls already rendered stay as-is — partial state is acceptable (AD-14 philosophy), no error banner spam | Existing `error` SSE handler still covers genuine extraction/detection failures |

</frozen-after-approval>

## Code Map

- `graphrag-core/.../domain/{Entity,Relationship,Community,CommunityMembership}.java` — reuse as-is; no `corpusId` field exists (global-store limitation, out of scope here).
- `graphrag-core/.../port/GraphStorePort.java` — promote `communities()`/`communityMemberships()` from "adapter-only" to proper interface default read methods (currently only `InMemoryGraphStoreAdapter` exposes them concretely; the port itself has no read method for either).
- `graphrag-adapter-neo4j/.../InMemoryGraphStoreAdapter.java` — already implements both; add `@Override` once promoted.
- `graphrag-core/.../usecase/ExtractEntitiesAndRelationships.java` (and its `BuildKnowledgeGraph` subclass) — add an optional progress-callback parameter/functional hook invoked per persisted Entity/Relationship.
- `graphrag-core/.../usecase/DetectCommunities.java` (~line 41-106, ids are `"community-" + index`) — add an optional progress-callback hook invoked per detected Community (with its member entity identities).
- `graphrag-web/.../CorpusController.java` (`startKnowledgeGraphConstruction`, ~line 149-161) — wire the two new callbacks to `corpusProgressService.emit(...)`, adding `entity-extracted`, `relationship-extracted`, `community-detected` event types alongside the existing three.
- `graphrag-web/.../CorpusProgressService.java` — no structural change; reuse `emit()`.
- `graphrag-web/src/main/resources/templates/index.html` — add the Cytoscape.js CDN `<script>` tag and a `<div id="graph-canvas">` mount point inside `.canvas`, alongside `#canvas-idle`/`#community-toggle-wrap`.
- `graphrag-web/src/main/resources/static/js/upload.js` (`connectProgressStream`, ~line 257-304) — add listeners for the 3 new event types; replace the toggle's console-log-only handler (~line 46-54) with real hull-layer show/hide.
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` (NEW) — Cytoscape init, node/edge add functions, community compound-node (hull) add/remove, re-run `cose` layout on graph changes.
- `graphrag-web/src/main/resources/static/css/instrument.css` (~line 169-173) — add `#graph-canvas` sizing (fills `.canvas`); remove the stale "No chat panel, composer, or Cytoscape.js in this story" comment.
- `graphrag-web/src/test/java/com/graphraglens/web/MainControllerTest.java:36` — replace the stale `doesNotContain("cytoscape")` assertion with a positive one asserting the Cytoscape script tag is present.
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` — add coverage that after `useDemoDataset()`'s async pipeline settles, `GraphStorePort.communities()`/`communityMemberships()` are populated (poll/await, matching whatever async-wait pattern existing tests in this class already use, if any — otherwise a short bounded retry loop).

## Tasks & Acceptance

**Execution:**
- [ ] `GraphStorePort.java` -- add `communities()`/`communityMemberships()` default read methods -- lets `graphrag-web` read Community data through the port instead of a concrete downcast
- [ ] `ExtractEntitiesAndRelationships.java` / `DetectCommunities.java` -- add optional progress-callback hooks -- gives the web layer a place to emit granular SSE events without web/core coupling
- [ ] `CorpusController.java` -- wire callbacks to `corpusProgressService.emit(...)` with `entity-extracted`/`relationship-extracted`/`community-detected` events -- delivers the granular stream the frontend animates from
- [ ] `index.html` -- add Cytoscape CDN script + `#graph-canvas` mount point -- gives the canvas somewhere to render into
- [ ] `graph-canvas.js` (new) -- Cytoscape init, node/edge/hull add functions, layout re-run -- owns all graph-drawing logic, kept out of `upload.js`
- [ ] `upload.js` -- wire new SSE listeners to `graph-canvas.js` functions; wire the toggle to hull-layer show/hide instead of `console.log` -- connects live events to rendering
- [ ] `instrument.css` -- size `#graph-canvas`, drop stale no-Cytoscape comment -- keeps the canvas visually consistent with DESIGN.md tokens
- [ ] `MainControllerTest.java` -- flip the stale cytoscape-absence assertion -- keeps the test suite honest about what's now shipped
- [ ] `CorpusControllerTest.java` -- assert Communities/memberships are populated after the demo pipeline completes -- covers the new port read methods end-to-end

**Acceptance Criteria:**
- Given a fresh Corpus is ingested, when community detection begins, then the toggle defaults ON and hulls visibly fold in as `community-detected` events arrive (Story 4.3 AC1).
- Given the toggle is switched OFF, when detection is still running, then the hull overlay and animation stop being shown while nodes/edges remain visible and detection is unaffected (Story 4.3 AC2).
- Given the toggle's state changes, when any change happens, then no SSE request/behavior on the backend differs — the same events are emitted regardless (Story 4.3 AC3).
- Given the toggle control, when reached via keyboard alone (Tab/Space), then it is fully operable (Story 4.3 AC4, accessibility floor).

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Design Notes

Cytoscape has no built-in "hull" primitive. The simplest dependency-free way to get a pale bounding shape behind a Community's member nodes is a **compound parent node** per Community: create one parent node styled with the Community's pale fill/label color, and set each member Entity node's `parent` to it. Cytoscape auto-sizes and positions the parent's background around its children and keeps it updated as the layout runs — this reads as a soft hull without a convex-hull-geometry library or a second rendering layer. Toggling OFF simply hides the compound parent nodes' background/border (`display: none` via a style class) while leaving child nodes visible and still parented (so turning back ON needs no re-fetch).

## Verification

**Commands:**
- `mvn -pl graphrag-web -am test` -- expected: all tests pass, including the new/updated `MainControllerTest` and `CorpusControllerTest` assertions
- Manual: run the app, load the demo dataset, confirm nodes/edges appear and hulls fold in with the toggle ON, then confirm switching it OFF hides hulls but keeps nodes/edges and detection running

**Manual checks (if no CLI):**
- Open the browser dev console during a demo-dataset load and confirm `entity-extracted`/`relationship-extracted`/`community-detected` SSE events are received and logged with the expected payload shape.
