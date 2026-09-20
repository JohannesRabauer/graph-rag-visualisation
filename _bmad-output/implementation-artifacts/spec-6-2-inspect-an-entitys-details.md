---
title: 'Inspect an Entity''s Details'
type: 'feature'
created: '2026-09-20'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '42722754e7ba4b5d4e4e5ccbbd6bdf79b0d52881'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The Explore page (Story 6.1) renders the full Knowledge Graph but its nodes are inert — clicking an Entity does nothing, so a viewer can see the graph's shape but cannot inspect any single Entity's actual connections or attributes without asking a question through chat.

**Approach:** Make Entity nodes on the Explore page clickable: a detail panel slides in from the right showing the clicked Entity's name, type, its Relationships (derived from the graph data already fetched by Story 6.1's `GET /api/graph` call — no new endpoint), and a Tags chip row. No Tag concept exists anywhere in the codebase today (Entity has only `name`/`type`), so the Tags row shows the Entity's existing `type` value as its single chip — a deliberate, human-decided stand-in rather than a real Tag data model (see Design Notes). Read-only for v1. Closes on clicking elsewhere on the canvas or clicking the same node again; clicking a different node swaps the panel's content instead.

## Boundaries & Constraints

**Always:** Panel content is derived entirely from data the Explore page already fetched on load (Story 6.1's `GET /api/graph` response held in `explore.js`) — no new endpoint, no per-click network request (matches AD-14's read-path philosophy). Only Entity nodes open the panel; only one Entity's panel is shown at a time. The Tags row always renders exactly one chip: the clicked Entity's `type` value (human decision — see Design Notes).

**Never:** No edit affordances anywhere on the panel — Entities, Relationships, and Tags are read-only for v1 (explicit AC). Clicking a Community hull node never opens the panel — this story is scoped to Entity nodes only. Do not touch the main screen's own canvas or Story 5.2's Replay click-to-highlight wiring — the new tap callbacks are opt-in registrations that only `explore.js` uses; `upload.js` never registers them, so main-screen behavior is unchanged.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Entity node clicked | Tap on a non-hull node | Panel slides in: name, type, Relationships list, Tags row showing one chip (the Entity's `type`) | N/A |
| Same node clicked again | Tap on the already-selected node | Panel closes | N/A |
| Different node clicked while open | Tap on node B while node A's panel is open | Panel content swaps to node B; stays open | N/A |
| Canvas background clicked | Tap on empty canvas area, not a node | Panel closes if open | N/A |
| Community hull clicked | Tap on a `.community-hull` node | No panel opens | N/A |
| Entity with no Relationships | Isolated node | Relationships section shows a plain "No relationships" line instead of an empty list | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-core/.../domain/Entity.java` -- confirms no `tags` field exists; not modified by this story. The Tags row reuses the Entity's existing `type` value as its one chip (human decision, resolved before this spec left draft — see Design Notes).
- `graphrag-web/.../static/js/graph-canvas.js` -- `init()` builds the Cytoscape instance; add `onNodeTap(callback)`/`onBackgroundTap(callback)` registration functions, wire `cy.on('tap', 'node', ...)` (skip `.community-hull`-classed nodes, pass `{identity, name, type}` from node data) and a background tap (`evt.target === cy`) inside `init()`, export both from `window.GraphCanvas` alongside the existing `addEntity`/`addRelationship`/`addCommunity`/etc.
- `graphrag-web/.../static/js/explore.js` -- `renderGraph(body)` already holds the full fetched `body.entities`/`body.relationships` in this call's scope; register the two new callbacks there, keep a `selectedIdentity` variable to implement the "click same node again closes" toggle, and populate/clear `#entity-detail-panel`'s DOM by filtering `body.relationships` for the tapped entity's identity (matching `sourceIdentity`/`targetIdentity`).
- `graphrag-web/.../templates/explore.html` -- add the `#entity-detail-panel` markup (hidden `<aside>`: close button, name/type, a Relationships list container, a Tags row container), positioned inside `.canvas` alongside the existing `#graph-legend`/community-toggle absolutely-positioned overlays.
- `graphrag-web/.../static/css/instrument.css` -- add `.node-detail-panel` and its child element styles per DESIGN.md `components.node-detail-panel` (300px width, `--panel` background, `--line` border-left, `--chrome`-filled monospace tag chips); slide via a `.is-open` class toggling `transform: translateX(0)` vs `translateX(100%)` so the panel stays in the DOM (no destructive `textContent`/`innerHTML` swap — see Story 6.1's own review-caught pitfall).
- `graphrag-web/.../test/java/com/graphraglens/web/MainControllerTest.java` -- `rendersTheExplorePage` already asserts canvas/legend/empty-state markup ids; extend it to also assert `#entity-detail-panel` is present.

## Tasks & Acceptance

**Execution:**
- [x] `graphrag-web/.../templates/explore.html` -- add `#entity-detail-panel` markup -- gives `explore.js` DOM to populate/toggle
- [x] `graphrag-web/.../static/css/instrument.css` -- add `.node-detail-panel` styles per DESIGN.md tokens -- slide-in-from-right visual treatment
- [x] `graphrag-web/.../static/js/graph-canvas.js` -- add `onNodeTap`/`onBackgroundTap`, wire Cytoscape `tap` handlers, export both -- lets `explore.js` react to clicks with no per-page Cytoscape wiring duplication
- [x] `graphrag-web/.../static/js/explore.js` -- wire the two callbacks to open/update/close/toggle the panel from already-fetched graph data -- no new network call per click
- [x] `graphrag-web/.../test/java/com/graphraglens/web/MainControllerTest.java` -- extend `rendersTheExplorePage` to assert `#entity-detail-panel` markup -- server-rendered coverage matching Story 6.1's own test pattern

**Acceptance Criteria:**
- Given the Explore page has rendered the graph, when I click an Entity node, then a panel slides in from the right showing that Entity's name, type, Relationships, and a Tags row
- Given the panel is open for an Entity, when I click that same node again, then the panel closes
- Given the panel is open for an Entity, when I click a different Entity node, then the panel's content updates to the newly clicked Entity without closing first
- Given the panel is open, when I click elsewhere on the canvas background, then the panel closes
- Given the empty state (no Corpus ingested), there are no nodes to click and the panel never appears — no regression to Story 6.1's empty state

## Implementation Notes

All 5 Code Map files changed exactly as planned. The close button (`#entity-detail-close`, present in the Code Map's markup description though not named in the AC list) was wired to the same close function as the two AC-specified close paths (same node again, background click) — a dead control otherwise, and it adds no edit affordance.

Verification: `mvn test` (`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`) -- full reactor `BUILD SUCCESS`, 29/29 web tests passing (unchanged count — `MainControllerTest`'s existing method gained one more assertion rather than a new test method). `node --check` passed on `explore.js` and `graph-canvas.js`. Re-verified independently after the implementation subagent's own report: read the full diff, re-ran the same commands myself with the same result.

No browser is available in this sandbox, so the manual click/slide-in check from the Verification section was not performed here — it remains a human/browser task.

## Spec Change Log

## Review Triage Log

Self-review (single-pass, given the diff's small, contained footprint and its close match to the spec) against the frozen Boundaries, I/O Matrix, and AC:

- All 6 I/O Matrix rows verified correct by reading the diff: hull-node clicks are excluded via an explicit `hasClass('community-hull')` check (not left to selector specificity), same-node-again closes, a different node swaps content without an intermediate close, background tap closes via Cytoscape's `evt.target === cy` idiom, and an Entity with no Relationships renders a "No relationships" line rather than an empty list.
- **defer** — No automated, JS-executing test covers the actual click/open/close/swap behavior this story adds (`MainControllerTest`'s new assertion only checks that the panel's static markup exists in server-rendered HTML). Same root cause as the identically-shaped, already-deferred Story 5.2 and Story 6.1 entries: AD-15 forbids a JS build toolchain, and no lightweight JS test runner exists in this repo. Logged in `deferred-work.md`.
- No other findings — the implementation matches the Code Map's file list, the AC, and the Design Notes' stated approach (client-side filtering of already-fetched data, no destructive DOM overwrite) with no deviations.

## Design Notes

Filtering `body.relationships` client-side (rather than adding a per-Entity backend endpoint) keeps this a pure frontend feature: Story 6.1 already fetches the entire graph in one `GET /api/graph` call before rendering, so the data the panel needs is already sitting in `explore.js`'s memory by the time any node is clickable. This also avoids a second read-path pattern alongside the bulk one Story 6.1 established.

The panel stays permanently in the DOM (never inserted/removed), toggled only via a CSS class and `transform`/`hidden` state. This sidesteps the exact destructive-`textContent` mistake caught during Story 6.1's own implementation (replacing a node's children wholesale can silently destroy other markup) by never doing a full-subtree overwrite — only specific child elements' `textContent` get updated per click.

No Tag concept exists anywhere in this codebase (no `Entity.tags` field, no extraction step, no storage) — the AC's "Tags as chips" is satisfied by rendering the Entity's existing `type` value as the Tags row's one chip, a human decision made explicitly for this story rather than an inferred assumption. This means `type` appears twice in the panel (once as a plain detail line, once as a Tag chip) — a deliberate, accepted redundancy, not a bug. A real, separate Tag concept (distinct from Type, per the project glossary) remains unbuilt; if the creator later wants genuine Tags, that is new scope for a future story (its own domain field, extraction step, and Neo4j schema work).

## Verification

**Commands:**
- `mvn test` -- expected: `MainControllerTest`'s new assertion passes, full reactor `BUILD SUCCESS`
- `node --check graphrag-web/src/main/resources/static/js/explore.js graphrag-web/src/main/resources/static/js/graph-canvas.js` -- expected: no syntax errors (no JS test runner exists in this repo per AD-15; this is the established lightweight gate)

**Manual checks (if no CLI):**
- Load the demo dataset, open `/explore`, click a node — confirm the panel slides in with name/type/Relationships; click the same node — confirm it closes; click a different node — confirm content swaps without closing first; click empty canvas background — confirm it closes. No browser is available in this sandbox, so this stays a human check.
