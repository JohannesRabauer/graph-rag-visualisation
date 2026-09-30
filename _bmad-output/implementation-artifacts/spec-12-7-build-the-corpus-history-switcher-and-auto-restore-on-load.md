---
title: 'Build the Corpus History Switcher and Auto-Restore on Load'
type: 'feature'
created: '2026-09-29'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '7ecb782dde4e4483edf381c03e0569495a91594f'
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `GET /api/corpora`/`POST .../activate` (Story 12.6) have no frontend caller yet — nothing lists past corpora, nothing restores the last-used one on page load, and there is no way to switch back to a previously-ingested corpus without re-ingesting.

**Approach:** Add a page-load bootstrap that auto-restores the most-recently-activated corpus, and a switcher popover (beside `#corpus-chip`, modeled on the existing `canvas-settings-popover`/`entity-search-results` patterns) listing every retained corpus, clickable to switch. Switching a `READY` corpus needs its graph redrawn without re-ingesting — since the canvas today is only ever populated incrementally via live SSE during ingestion, this story adds one new bulk-read endpoint (`GET /api/corpora/{corpusId}/graph`) shaped exactly like the existing SSE payloads, so the frontend can call the *same* `GraphCanvas.addEntity`/`addRelationship`/`addCommunity` functions it already uses for live ingestion — no new canvas API.

## Boundaries & Constraints

**Always:** A new `activateCorpus(corpusMeta)` frontend function branches on the corpus's `status`: `BUILDING` reconnects the existing live `connectProgressStream(id)` (resuming to watch it finish, not re-ingesting); `READY` bulk-fetches `GET /api/corpora/{id}/graph` and loops calling the existing `addEntity`/`addRelationship`/`addCommunity` functions (same payload shapes as the `entity-extracted`/`relationship-extracted`/`community-detected` SSE events) then renders `READY`; `FAILED` renders the existing failure/recovery UI with no graph. Page load calls `GET /api/corpora`; if non-empty, `activateCorpus` the first entry (already most-recently-activated first) and `POST .../activate` for it too (opening the app counts as activating). The switcher list lives beside `#corpus-chip` in `.app-bar-right`; clicking a non-active entry calls `activateCorpus` + `POST .../activate` for it. Switching preserves `resetToIdleState`'s documented Vector-Space-tab-hide ordering constraint (`switchCanvasTab('knowledge-graph')` before explicit hides) when a previous corpus had Vector Space revealed.

**Never:** No change to `showCorpusChip` (still owns the fresh-ingestion path) or `resetToIdleState` (still owns the "start over" path, Story 10.4) — `activateCorpus` is a third, new path, not a merge of the other two. No re-ingestion, no full page reload, on any switch. No change to `GraphStorePort`/`Neo4jGraphStoreAdapter`/`graphrag-core` — the new endpoint only reads via `entities(corpusId)`/`relationships(corpusId)`/`communities(corpusId)`/`communityMemberships(corpusId)`, all already real and Neo4j-backed. No eager vector-space bulk-load on activation — `VectorSpace.init(corpusId)` already lazily loads on tab-reveal (unchanged).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh app, no corpora yet | `GET /api/corpora` returns `{"corpora": []}` | `#canvas-idle` stays visible, unchanged from today's default | N/A |
| Auto-restore on load | Two corpora exist, B most-recently-activated | Page load shows B's graph/chat, `#corpus-chip` reflects B, `POST .../activate` fired for B | N/A |
| Switch to a READY corpus | User clicks corpus A (not active) in the switcher | Canvas/vector-space/query panel re-render against A in place; no reload; `POST .../activate` fired for A | N/A |
| Switch to a FAILED corpus | A history entry with `status: "FAILED"` | Failure/recovery UI renders (same as a live failure), no graph, query still 409s per the existing gate | N/A |
| Switch to a BUILDING corpus | A history entry with `status: "BUILDING"` (e.g. still ingesting in another tab) | Reconnects the live progress stream; UI shows `BUILDING` and updates to `READY` if/when it completes | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` — new `@GetMapping("/api/corpora/{corpusId}/graph")`, returning entities/relationships/communities in the exact JSON shapes the existing `entity-extracted`/`relationship-extracted`/`community-detected` SSE events already use (grep those event-building call sites for the exact field names: `identity`/`name`/`type`; `sourceIdentity`/`source`/`targetIdentity`/`target`/`type`; `communityId`/`summary`/`memberEntityIdentities`), read via the already-real `graphStorePort.entities(corpusId)`/`.relationships(corpusId)`/`.communities(corpusId)`/`.communityMemberships(corpusId)` (compute `memberEntityIdentities` by grouping `communityMemberships(corpusId)` by `communityId`).
- `graphrag-web/src/main/resources/static/js/upload.js` — `showCorpusChip` (895-1003) and `resetToIdleState` (772-893) are the two existing "whole-state-change" functions to read fully before writing the third (`activateCorpus`); `connectProgressStream` (1154-1266) is reused as-is for the `BUILDING` branch; the `entity-extracted`/`relationship-extracted`/`community-detected` SSE handlers (1186-1226) show the exact `window.GraphCanvas.addEntity/addRelationship/addCommunity` call shapes to reuse for the bulk `READY` branch. No `DOMContentLoaded`/page-load hook exists yet anywhere in this file — confirmed nothing to conflict with.
- `graphrag-web/src/main/resources/templates/index.html` — `.app-bar-right` (17-20) holds `#corpus-chip`/`#workflow-restart-button` today; the new switcher button+popover goes here as a third sibling, shown/hidden alongside the chip.
- `graphrag-web/src/main/resources/static/css/instrument.css` — `.canvas-settings-popover` (875-888, open/close mechanics) and `.entity-search-results`/`.entity-search-result` (1489-1572, scrollable row-list styling) are the two patterns to combine for the switcher's look; `.corpus-chip` (1588-1611) for its pill/status-dot idiom, reused per-row.
- `graphrag-web/src/main/resources/templates/index.html:121` — `#canvas-idle`'s copy ("Corpora... live only in this app's memory — restarting it clears them") is now stale (corpus bookkeeping persists since Story 12.4); update it, mirroring the `README.md` correction already made in that story.
- `graphrag-web/src/test/java/com/graphraglens/web/ui/LoadNewCorpusUiTest.java` + `UiTestSupport.java` — the Playwright idioms (`page.route` for SSE mocking, `page.onceDialog`, `LocatorAssertions...setTimeout`) and the ordering-regression test style (`resettingAfterVectorSpaceWasRevealedLeavesEveryKnowledgeGraphControlHidden`) to follow for the new switcher test.

## Tasks & Acceptance

**Execution:**
- [x] `CorpusController.java` -- add `GET /api/corpora/{corpusId}/graph`, SSE-payload-shaped JSON, reading the already-real corpus-scoped `GraphStorePort` methods
- [x] `upload.js` -- add `activateCorpus(corpusMeta)`: status-branched (BUILDING reconnect / READY bulk-load / FAILED render-only), sharing teardown steps with `resetToIdleState` (close EventSource/Replay/entity-detail panel, clear `activeRelationships`) but never tearing down to idle
- [x] `upload.js` -- add a page-load bootstrap: fetch `GET /api/corpora`, `activateCorpus` + `POST .../activate` the first entry if non-empty, no-op if empty
- [x] `index.html` + `instrument.css` -- switcher button+popover in `.app-bar-right`, rows built from `GET /api/corpora`, click handler calling `activateCorpus` + `POST .../activate`; update `#canvas-idle`'s stale restart-loses-everything copy
- [x] New Playwright UI test (`CorpusSwitcherUiTest.java`) -- ingest two corpora, assert the switcher lists both most-recent-first, assert clicking the non-active one re-renders in place (no reload), assert both auto-restore-on-load and manual-switch each fire `POST .../activate`

**Acceptance Criteria:**
- Given two corpora ingested in one session, when the page is reloaded, then the one most recently activated is shown automatically, matching Story 12.6's `lastActivatedAt` ordering
- Given corpus A is showing and corpus B (also `READY`) exists in the switcher, when B is clicked, then B's graph/chat/vector-space all reflect B afterward, with no full page reload and no re-ingestion

## Implementation Notes

- All 5 tasks complete, matching the Design Notes exactly: the bulk `/graph` endpoint reuses the existing `entityEventPayload`/`relationshipEventPayload`/`communityEventPayload` helpers, so the frontend's `READY` branch calls the same `GraphCanvas.addEntity`/`addRelationship`/`addCommunity` functions the live SSE handlers already use — no new canvas API. `showCorpusChip`/`resetToIdleState` are untouched; `activateCorpus` is a genuinely separate third path, duplicating only the small shared teardown steps rather than refactoring either existing function (per the spec's explicit "Never").
- **Real cross-cutting issue found and fixed during implementation itself (not by review):** introducing page-load auto-restore made every existing UI test's `page.navigate("/")` non-neutral — since all ~21 `UiTestSupport`-based test classes share one JVM-wide Neo4j test container, any corpus left behind by an earlier test would auto-restore on the next test's page load and hide `#canvas-idle`'s upload controls before that test could use them. Fixed with a `@BeforeEach` in `UiTestSupport.java` that wipes the whole Neo4j graph before every test method, restoring every existing test's "fresh idle canvas" assumption. This is exactly the kind of test-suite-wide contamination Story 12.4's own review already flagged as a forward-looking risk (logged in `deferred-work.md`) — this story is where it actually landed, and it's now fixed at the root (the shared test harness), not worked around per-test.
- Verified: full `graphrag-web` suite (142 tests, up from 140 — 2 new switcher tests) passes cleanly on an independent rerun with real Docker access, not just the implementing subagent's own (unconfirmed-complete) background run.
- Known coverage gap, explicitly acknowledged rather than papered over: no dedicated test for the `BUILDING`-reconnect or `FAILED`-switch branches of `activateCorpus` — the demo/offline ingestion paths use a deterministic stub that always succeeds, so a real backend `FAILED` corpus isn't easy to manufacture in this harness. Only `READY`-switch and auto-restore are tested, matching the spec's own Task list.
- Minor, spec-uncovered cosmetic gap: the corpus chip for a restored/switched corpus shows only the name, not the document count (`GET /api/corpora`'s summary payload doesn't carry `documentNames`), unlike the chip shown right after a fresh upload.

Final verification: full `graphrag-web` suite (144 tests, up from 142 — 2 new `/graph` endpoint tests) passes cleanly on an independent rerun after the review patches, with real Docker access (not just the implementing subagent's own narrowly-scoped run).

## Spec Change Log

## Review Triage Log

- **patch** (medium) — `GET /api/corpora/{corpusId}/graph` has no status gate: calling it for a `BUILDING`/`FAILED` corpus returns `200` with empty/partial entity data instead of a clear signal, unlike `query()`'s existing `409` gate for the same statuses (AD-16). Add the same gate for consistency and defense-in-depth (the frontend never calls it for non-`READY` corpora today, but the endpoint is public).
- **patch** (low) — `activateCorpus`'s status branch is `if (BUILDING) ... if (FAILED) ... else (READY)` — an unrecognized future status would silently fall through to the `READY` bulk-load path. Make the `READY` case an explicit `else if`, with a console warning for anything else, for defensiveness against a future enum value.
- **patch** (low) — `.corpus-history-row` defines `:hover` but no `:focus-visible`, unlike its sibling controls — a keyboard user tabbing through the open popover gets no visible focus indicator on any row. Add the same focus-visible treatment used elsewhere in this file.
- **patch** (low) — Both new `POST .../activate` call sites (switcher row click, page-load auto-restore) swallow failures with an empty `.catch(function(){})` — if that write fails, the durable `lastActivatedAt` ordering silently falls out of sync with what's on screen, with nothing surfaced anywhere. Add at least a `console.warn` so a failure isn't completely invisible (the client-side switch itself still succeeds either way, so no user-facing error banner is warranted).
- **patch** (low) — No backend test covers the new `GET /api/corpora/{corpusId}/graph` endpoint directly — only indirectly, through the Playwright UI test. Add a lightweight `CorpusControllerTest` case asserting the response shape (entities/relationships/communities, including the community→member-identity grouping) and the 404 path for an unknown id.
- **patch** (low) — `resetToIdleState()` (Story 10.4, untouched by this diff) doesn't hide `corpusHistoryToggle`, so the switcher stays visible after "Start over with a new corpus." Verified this is a reasonable, arguably desirable interaction (the history should stay reachable even from the idle screen) but it was undocumented. Add a one-line comment recording this as intentional rather than an oversight.
- **false** — Edge Case Hunter flagged (medium confidence) that the `FAILED` branch might not be "render-only" as the spec's Boundaries state, since `revealCorpusCanvasSurface()` runs before the status branch. Verified against the actual code: the empty canvas *scaffolding* does become visible before the `FAILED` check, but no entities/relationships/communities are ever added to it (the function returns before any `GraphCanvas.add*` call) — this exactly matches the existing live-failure UX (a corpus that fails mid-ingestion already shows whatever partial canvas state existed plus the failure banner), not a new inconsistency.
- **defer** — Three real gaps found independently by multiple reviewers, all requiring Playwright-harness machinery beyond this story's scope to test deterministically: (1) the `BUILDING`-reconnect branch of `activateCorpus` has no test (needs a controllable-delay ingestion stub); (2) the `FAILED`-switch branch has no test (needs a way to force a corpus into `FAILED` deterministically — the demo/offline stub paths always succeed); (3) the stale-response guard in the `READY` bulk-fetch (`requestedCorpusId !== activeCorpusId`) is never exercised by an actual race (needs response-delay route interception purely to prove ordering). Logged to `deferred-work.md`.
- **defer** — The corpus-history popover's open/close interactions (outside-click, Escape) have no dedicated test, and the popover has no focus management (opening doesn't move focus to the first row, closing doesn't return focus to the toggle button) — a real accessibility gap beyond the cheap `:focus-visible` CSS fix above, but a larger UX behavior change. Logged to `deferred-work.md`.

## Design Notes

The bulk-graph endpoint deliberately mirrors the SSE event payload shapes rather than inventing a new one — this means the frontend's bulk-load branch is a thin loop over the *same* `addEntity`/`addRelationship`/`addCommunity` functions the live-ingestion SSE handlers already call, not a new `GraphCanvas` API. This keeps the canvas's population surface at exactly two callers (SSE events during ingestion, this bulk endpoint on switch) instead of three.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am test` -- expected: new switcher UI test passes; full existing suite (140 tests) unaffected
- `mvn -q -B clean install` -- expected: full reactor green
