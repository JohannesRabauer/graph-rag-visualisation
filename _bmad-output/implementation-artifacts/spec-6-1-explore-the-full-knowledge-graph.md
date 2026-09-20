---
title: 'Explore the Full Knowledge Graph'
type: 'feature'
created: '2026-09-20'
status: 'done'
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
- [x] `ExploreGraph.java` (new) -- read all four `GraphStorePort` collections -- gives the web layer one place to assemble the full-graph read
- [x] `ExploreController.java` (new) -- `GET /api/graph` -- exposes the bulk read reusing Story 4.3's existing entity/relationship/community payload field names
- [x] `MainController.java` -- add `GET /explore` -- serves the new page
- [x] `explore.html` (new) -- app bar, canvas/legend markup, empty state -- the page shell
- [x] `index.html` -- add the persistent Explore link -- gives users a way to reach the page
- [x] `graph-canvas.js` -- make `init()` accept an `interactive` option controlling pan/zoom, default unchanged -- lets Explore enable pan/zoom without touching the main screen's behavior
- [x] `explore.js` (new) -- fetch `/api/graph`, render via `GraphCanvas`, always-on hulls, empty-state handling -- wires the page together
- [x] `instrument.css` -- style the Explore link and empty state -- visual consistency
- [x] `ExploreControllerTest.java` (new) / `MainControllerTest.java` -- cover the matrix -- proves the endpoint and page render correctly

**Acceptance Criteria:**
- Given a Corpus has been ingested and Communities detected, when the Explore page loads, then the full graph renders pannable and zoomable with Community hulls always visible (Story 6.1 AC1).
- Given no Corpus has been ingested yet, when the Explore page loads, then a plain-language empty state points back to `/` — never a blank or broken canvas (Story 6.1 AC2).
- Given ingestion is still in-flight, when the Explore page loads or is refreshed, then it renders whatever is currently committed with no waiting, locking, or error (Story 6.1 AC3, AD-14).
- Given the main screen's own graph canvas, when this story ships, then it ends up pan/zoom-disabled, matching the frozen Boundaries' requirement (Story 6.1 AC4 — see Review Triage Log: review found this was not actually a no-op change, since Cytoscape.js defaults pan/zoom to enabled and no prior code had set it `false`; the requirement itself — disabled on the main screen — is still correctly met).

## Implementation Notes

- Implemented directly (no subagent dispatch). All nine execution tasks completed.
- `ExploreGraph`/`ExploreGraphResult` (new, `graphrag-core`): a framework-free use case reading `graphStorePort.entities()`/`relationships()`/`communities()`/`communityMemberships()` and assembling them into a small immutable record — no new port method, no Cypher, same shape/spirit as `AnswerGlobalSearch`/`GlobalSearchAnswer`.
- `ExploreController` (new, `graphrag-web`): `GET /api/graph` builds the community→member-identities index once (like `CorpusController.startKnowledgeGraphConstruction`'s equivalent grouping for the `community-detected` SSE payload) and serializes entities/relationships/communities with the exact field names Story 4.3's SSE events already use (`identity`/`name`/`type`; `sourceIdentity`/`source`/`targetIdentity`/`target`/`type`; `communityId`/`summary`/`memberEntityIdentities`), so `explore.js` can call `GraphCanvas.addEntity`/`addRelationship`/`addCommunity` unchanged. It is a plain read: no waiting/locking against in-flight ingestion (AD-14), no mutation.
- `MainController`: added `GET /explore` returning the new `explore` view, alongside the existing `/`.
- `explore.html` (new): app bar with the brand mark, a persistent `Explore` nav link (marked `active`/`aria-current="page"`) and a link back to `/`; `#graph-canvas`/`#graph-legend` (same ids/classes `instrument.css` and `graph-canvas.js` already know) plus a hidden `#explore-empty-state` block (reusing the existing `.canvas-idle`/`.eyebrow`/`.subtitle` classes from Story 1.3, so no new empty-state CSS was needed beyond the nav link styling). No chat panel, community toggle, or replay markup — out of scope per the Code Map. Both `#graph-canvas` and `#explore-empty-state` start `hidden`; `explore.js` picks exactly one to show once the fetch resolves, so there is never a flash of the wrong state or an uninitialized-but-visible canvas.
- `index.html`: added an `.app-bar-right` wrapper (nav + the existing corpus-chip) so the layout stays clean with the new `<a class="app-bar-link" href="/explore">Explore</a>` link; this is a structural wrapper only; every existing `id` (`corpus-chip`, etc.) is unchanged, so no existing assertion needed touching.
- `graph-canvas.js`: `init()` now takes an optional `options` object; `interactive = !!(options && options.interactive)` drives `userZoomingEnabled`/`userPanningEnabled` on the Cytoscape constructor config, defaulting to `false` when omitted. **Correction (review found this claim wrong):** Cytoscape.js itself defaults both options to `true` when unset, and no code before this diff ever set them — so the main screen's canvas was actually pan/zoom-*enabled* the whole time (an undocumented, accidental side effect of Stories 4.3/5.2, not a deliberate choice), not "already disabled" as originally assumed here. This diff is what first disables it, which is the behavior the frozen Boundaries actually require — the AC4 requirement is met, just not as a no-op.
- `explore.js` (new): fetches `/api/graph` once on load; an empty `entities` array (or a failed fetch — treated identically, since this page has no other error-reporting surface) shows `#explore-empty-state` and keeps `#graph-canvas` hidden; otherwise it calls `GraphCanvas.init({ interactive: true })`, replays every entity/relationship/community through the same `addEntity`/`addRelationship`/`addCommunity` calls `upload.js` makes incrementally from SSE, and finishes with an unconditional `setHullsVisible(true)` — no toggle exists on this page.
- `instrument.css`: added `.app-bar-right`/`.app-bar-nav`/`.app-bar-link` (plus `.app-bar-link.active`/`:hover`/`:focus-visible`), all built from existing `:root` tokens. The empty-state block needed no new CSS — it reuses `.canvas-idle`/`.eyebrow`/`.subtitle`, already styled since Story 1.3.
- `ExploreControllerTest.java` (new): a plain, non-Spring unit test (same reasoning as the existing `CorpusControllerGlobalSearchTest` — a shared `@SpringBootTest`'s `GraphStorePort` bean is a global, unreset singleton across test methods) constructing `ExploreController` directly against a fresh `InMemoryGraphStoreAdapter`. Covers both I/O-matrix rows for the endpoint: an empty store returns `200` with all three arrays empty, and a populated store returns entities/relationships/communities with the documented field names, including a community's `memberEntityIdentities`.
- `MainControllerTest.java`: added `mainScreenLinksToTheExplorePage` (asserts `/` contains `href="/explore"`) and `rendersTheExplorePage` (asserts `GET /explore` is `200` and contains the canvas/legend/empty-state ids, the link back to `/`, the Cytoscape/`graph-canvas.js`/`explore.js` script tags, and explicitly does **not** contain the chat-panel/community-toggle/replay-scrubber markup that's out of scope here).
- Verification: `mvn test` (`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`, since the default `JAVA_HOME` on this machine is JDK 21 and the project's enforcer plugin requires JDK 25) — full reactor `BUILD SUCCESS`, 28 tests in `graphrag-web` (up from 24: 2 new `MainControllerTest` cases + 2 new `ExploreControllerTest` cases), 0 failures/errors. `node --check` passed on `graph-canvas.js`, `explore.js`, and `upload.js` as a lightweight syntax gate (no JS test runner exists in this repo, consistent with prior stories' verification notes).
- Manual browser verification (loading the demo dataset, then panning/zooming `/explore` with the mouse, then confirming the main screen's own canvas still cannot be panned/zoomed) was not performed in this sandbox — no browser is available here. This is the one Verification-section manual check left undone; the automated `mvn test` check this story's own Verification section specifies was run and passes.
- Nothing in this spec's Tasks & Acceptance or Boundaries & Constraints was left incomplete. No new `GraphStorePort` method, no Cypher, and no per-Corpus scoping were introduced, per the frozen Boundaries. The main screen's canvas *does* newly become pan/zoom-disabled by this diff (see the correction above) — that is the frozen Boundaries' actual requirement, satisfied correctly even though the original claim of "no change" was inaccurate.

## Spec Change Log

## Review Triage Log

Three-layer review (blind-hunter, edge-case-hunter, verification-gap) ran in parallel against the full diff.

- **patch** — `explore.js`'s `renderGraph()` revealed `#graph-canvas` unconditionally without checking whether `GraphCanvas.init()` actually succeeded — if the Cytoscape CDN script failed to load, `init()` returns `null` but the canvas was shown anyway, violating AC2's "never a blank/broken canvas" (edge-case-hunter, found twice/independently as both a trigger-condition finding and a claim). Fixed: `renderGraph()` now checks `init()`'s return value and falls back to the empty state when it's falsy.
- **patch** — The page showed nothing (neither canvas nor empty state) while `GET /api/graph` was in flight, and a failed/non-OK fetch was silently treated identically to "no Corpus ingested yet" with no way to tell the two apart (blind-hunter's two findings, same "nothing visible/no error surfaced" theme, merged with edge-case-hunter's "no fetch timeout" finding on the same underlying gap). Added a `#explore-loading` state shown from page load, a `console.error` on fetch/HTTP failure, and a distinct "could not be loaded" message separate from the "no Corpus yet" one — split into three states (`explore-loading`/`explore-empty-state` with its message swapped/`graph-canvas`) instead of overwriting the empty-state block's `textContent` (which would have destroyed its "back to main screen" link — caught while implementing the fix, not by a reviewer).
- **patch** — `ExploreControllerTest` only called `ExploreController.graph()` directly, never proving `GET /api/graph` serializes correctly over real HTTP/Jackson (the I/O matrix's own "called directly" scenario) (blind-hunter). Added `exploreGraphEndpointServesEntitiesRelationshipsAndCommunitiesOverRealHttp` to `CorpusControllerTest`, which already has the `@SpringBootTest`/MockMvc/demo-dataset scaffolding.
- **patch** — `CorpusController.relationshipEventPayload` and `ExploreController.relationshipPayload` both computed `new Entity(name, type).normalizedIdentity()` verbatim (blind-hunter). Extracted `Entity.identityOf(name, type)`, used by both.
- **defer** — Neither AC1 (Explore's pan/zoom-enabled, hulls-always-on rendering) nor AC4 (main screen's pan/zoom staying disabled) has any JS-executing test (blind-hunter + verification-gap's two pre-verified findings, same root cause). Logged in `deferred-work.md`, alongside the identically-caused Story 5.2 entry.
- **defer** — `GraphStorePort`'s four collections could in principle disagree (a Relationship/Membership referencing a missing Entity), with only `graph-canvas.js`'s existing placeholder fallback handling it (blind-hunter). Not currently reachable given how `BuildKnowledgeGraph` persists; a real fix is a design decision, not a simple correction. Logged in `deferred-work.md`.
- **false** — `List.copyOf` NPE risk if a `GraphStorePort` implementation returned a collection containing a null element (edge-case-hunter). Disproof: the only real implementation, `InMemoryGraphStoreAdapter`, and every caller that persists into it, never constructs or stores a null Entity/Relationship/Community/Membership — no reachable path produces this today (same pattern as previously-rejected unreachable-state findings in Stories 4.3/4.4).
- **false** — The commit's spec `status` went straight from `in-progress` to `done`, skipping `in-review` (blind-hunter). This reflects the same intentional staged status lifecycle used consistently all session (in-progress during implementation, `in-review` set before the review pass, `done`/`review` set at finalize) — the build session set it to `in-review` immediately after the implementer's commit, before this review ran.
- **false** — The "Explore" nav link stays clickable/reloads the page while marked `active`/`aria-current="page"` (blind-hunter). This is standard, expected web-navigation behavior (most sites' "you are here" nav items still work as plain links) — not a defect.
- **rejected, fix is a spec correction, not a code change** — The spec's Boundaries/Implementation Notes claimed the main screen's canvas was "already disabled"/"unchanged" by this story (verification-gap + edge-case-hunter, confirmed independently twice, and consistent with Cytoscape.js's documented default of `userZoomingEnabled`/`userPanningEnabled: true`). Verified real: no code before this diff ever set those options, so the main screen was actually pan/zoom-*enabled* the whole time — this diff is what first disables it, which is what the frozen Boundaries actually require, just not a no-op as originally written. The desired end-state was already correct; only the record was wrong. Corrected in the Implementation Notes and AC4 above rather than routed as a patch, since editing this build's own spec text was the entire fix.

## Design Notes

`GraphStorePort` has no per-Corpus scoping (an accepted, already-deferred limitation), so "the full Knowledge Graph" this page renders is, today, the same single global graph every other read path already sees — not scoped to whichever Corpus was most recently loaded on the main screen. This is consistent with how Local/Global Search and Story 4.3's canvas already behave, not a new gap this story introduces.

## Verification

**Commands:**
- `mvn test` -- expected: all tests pass, including new `ExploreControllerTest`/`MainControllerTest` coverage

**Manual checks (if no CLI):**
- Load the demo dataset on the main screen, then open `/explore` in the same session and confirm the full graph renders with hulls always visible and can be panned/zoomed with the mouse; confirm the main screen's own canvas still cannot be panned/zoomed (regression check).
