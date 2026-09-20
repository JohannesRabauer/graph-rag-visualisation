---
title: 'Explore the Full Knowledge Graph'
type: 'feature'
created: '2026-09-20'
status: 'in-progress'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: 'c91c4107cb6168efbe0f27c1022974988882e60e'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The Knowledge Graph is only ever visible reactively — while a Corpus is ingesting (Story 4.3's live canvas) — with no way to freely pan/zoom/browse the graph on its own terms, independent of any query or ingestion run, and no persistent link to reach such a view at all.

**Approach:** Add a dedicated `GET /explore` page (a new Thymeleaf template + `MainController` route) with a persistent "Explore" link in the main screen's app bar. Add a new `ExploreGraph` use case in `graphrag-core` (reads `graphStorePort.entities()`/`relationships()`/`communities()`/`communityMemberships()` — no new port method, no Cypher) and a bulk `GET /api/graph` endpoint returning everything in one response. The Explore page fetches that once on load and renders it via the existing `graph-canvas.js`, reused wholesale but with panning/zooming enabled (the main screen disables both) and Community hulls always visible with no toggle. An empty state points back to the main screen when no Entities exist yet.

## Boundaries & Constraints

**Always:** Community hulls are always visible on this page — no toggle, unlike the main screen (epic UX requirement). Reads never wait for, lock against, or block on in-flight ingestion (AD-14 philosophy already established) — the page simply renders whatever `graphStorePort` currently holds, which may be partial. `GET /api/graph` is a plain REST read, no SSE, no mutation. The Explore page reuses the main screen's `graph-canvas.js` rendering code, node/edge/community-hull visual tokens, and Cytoscape instance pattern wholesale — no second graph-rendering implementation.

**Never:** Do not implement the node detail panel (Story 6.2 — this story only needs nodes to be clickable in principle, not what clicking does). Do not add per-Corpus scoping to `GraphStorePort` (same accepted, pre-existing global-store limitation as Stories 4.3/4.4/5.1/5.2) — Explore renders the one shared graph exactly as every other read path already does. Do not change the main screen's own Cytoscape config (pan/zoom must stay disabled there) — `graph-canvas.js`'s shared `init()` must accept an option rather than having its default behavior changed globally. Do not add a database/Cypher query layer — `graphrag-adapter-neo4j`'s `InMemoryGraphStoreAdapter` is the only real `GraphStorePort` implementation today (verified: `Neo4jGraphStoreAdapter` is an empty subclass, dead code), and this story does not change that.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Graph has Entities/Relationships/Communities | `GET /explore` after any ingestion has run | Full graph renders, pannable/zoomable, Community hulls always visible, legend shown | N/A |
| No Corpus ingested yet | `graphStorePort.entities()` is empty | Plain-language empty state with a link back to `/` (the main screen's ingestion entry point) — never a blank/broken canvas | N/A |
| `GET /api/graph` called directly | Any state | `200` with `{"entities":[...],"relationships":[...],"communities":[...]}`, each community including its member entity identities | N/A |
| Ingestion is still in-flight when Explore loads | Partial graph state committed | Renders whatever is currently in `graphStorePort` — no waiting, no lock, no error | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-core/.../usecase/ExploreGraph.java` (NEW) — reads `graphStorePort.entities()`/`relationships()`/`communities()`/`communityMemberships()` and assembles a plain result object (e.g. a small record grouping the four collections) — no framework dependency, matches `AnswerGlobalSearch`'s existing use-case shape.
- `graphrag-web/.../ExploreController.java` (NEW) — `GET /api/graph` calling `ExploreGraph`, serializing entities (`identity`/`name`/`type`), relationships (`sourceIdentity`/`source`/`targetIdentity`/`target`/`type`, same shape as Story 4.3's SSE payloads), and communities (`communityId`/`summary`/`memberEntityIdentities`, same shape as Story 4.3's `community-detected` event) — reusing the exact field names the frontend already knows from `graph-canvas.js`/`upload.js`, so the Explore page's fetch-then-render code can reuse the same `GraphCanvas.addEntity`/`addRelationship`/`addCommunity` calls the main screen already makes incrementally from SSE.
- `graphrag-web/.../MainController.java` — add `GET /explore` returning a new `explore` view.
- `graphrag-web/src/main/resources/templates/explore.html` (NEW) — app bar (with the persistent Explore link, and a link back to `/`), `#graph-canvas`/`#graph-legend` markup (reused ids/classes from `instrument.css`), an empty-state block, no chat panel/community-toggle/replay markup (out of scope here). Loads Cytoscape.js, `graph-canvas.js`, and a new `explore.js`.
- `graphrag-web/src/main/resources/templates/index.html` — add the persistent "Explore" link in the app bar (`<a href="/explore">Explore</a>` styled per DESIGN.md's app-bar conventions).
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` (`init`) — accept an options argument, e.g. `init({ interactive: true })`, controlling `userZoomingEnabled`/`userPanningEnabled` (default stays `false`, matching the main screen's existing behavior unchanged); update the one existing call site in `upload.js` to pass nothing (keeping today's disabled default).
- `graphrag-web/src/main/resources/static/js/explore.js` (NEW) — on page load, `GET /api/graph`; if empty, show the empty-state block; otherwise call `GraphCanvas.init({ interactive: true })`, then `addEntity`/`addRelationship`/`addCommunity` for each item, then `setHullsVisible(true)` unconditionally (no toggle exists on this page).
- `graphrag-web/src/main/resources/static/css/instrument.css` — style the Explore page's app bar link and empty-state block, reusing existing tokens; no new color/typography tokens needed.
- `graphrag-web/src/test/java/com/graphraglens/web/ExploreControllerTest.java` (NEW) — covers the matrix: populated graph returns the expected shape; empty graph still returns `200` with empty arrays (the page itself decides how to render that, not the endpoint).
- `graphrag-web/src/test/java/com/graphraglens/web/MainControllerTest.java` — add a case asserting `GET /explore` renders (status 200, contains the expected markup ids) and that `/` now links to `/explore`.

## Tasks & Acceptance

**Execution:**
- [ ] `ExploreGraph.java` (new) -- read all four `GraphStorePort` collections -- gives the web layer one place to assemble the full-graph read
- [ ] `ExploreController.java` (new) -- `GET /api/graph` -- exposes the bulk read reusing Story 4.3's existing entity/relationship/community payload field names
- [ ] `MainController.java` -- add `GET /explore` -- serves the new page
- [ ] `explore.html` (new) -- app bar, canvas/legend markup, empty state -- the page shell
- [ ] `index.html` -- add the persistent Explore link -- gives users a way to reach the page
- [ ] `graph-canvas.js` -- make `init()` accept an `interactive` option controlling pan/zoom, default unchanged -- lets Explore enable pan/zoom without touching the main screen's behavior
- [ ] `explore.js` (new) -- fetch `/api/graph`, render via `GraphCanvas`, always-on hulls, empty-state handling -- wires the page together
- [ ] `instrument.css` -- style the Explore link and empty state -- visual consistency
- [ ] `ExploreControllerTest.java` (new) / `MainControllerTest.java` -- cover the matrix -- proves the endpoint and page render correctly

**Acceptance Criteria:**
- Given a Corpus has been ingested and Communities detected, when the Explore page loads, then the full graph renders pannable and zoomable with Community hulls always visible (Story 6.1 AC1).
- Given no Corpus has been ingested yet, when the Explore page loads, then a plain-language empty state points back to `/` — never a blank or broken canvas (Story 6.1 AC2).
- Given ingestion is still in-flight, when the Explore page loads or is refreshed, then it renders whatever is currently committed with no waiting, locking, or error (Story 6.1 AC3, AD-14).
- Given the main screen's own graph canvas, when this story ships, then its pan/zoom-disabled behavior is unchanged (Story 6.1 AC4 — regression guard for the shared `graph-canvas.js`).

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Design Notes

`GraphStorePort` has no per-Corpus scoping (an accepted, already-deferred limitation), so "the full Knowledge Graph" this page renders is, today, the same single global graph every other read path already sees — not scoped to whichever Corpus was most recently loaded on the main screen. This is consistent with how Local/Global Search and Story 4.3's canvas already behave, not a new gap this story introduces.

## Verification

**Commands:**
- `mvn test` -- expected: all tests pass, including new `ExploreControllerTest`/`MainControllerTest` coverage

**Manual checks (if no CLI):**
- Load the demo dataset on the main screen, then open `/explore` in the same session and confirm the full graph renders with hulls always visible and can be panned/zoomed with the mouse; confirm the main screen's own canvas still cannot be panned/zoomed (regression check).
