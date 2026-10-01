---
title: 'Story 13.4: Show descriptions and source passages in the entity detail panel'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: '80eee5c5cff857062fe6dcec45a144cdb8b9fcc7'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Stories 13.2–13.3 give every Entity and Relationship a description and source Text Unit ids, but the entity detail panel shows neither. The viewer cannot check a node against the text it came from.

**Approach:** Entity payloads (SSE and `/graph`) gain a labelled `sources` list. A new scoped endpoint `GET /api/corpora/{corpusId}/text-units/{textUnitId}` returns one passage. The panel shows the entity description, a "Source passages" section whose rows fetch their text when expanded, and relationship descriptions as row tooltips.

## Boundaries & Constraints

**Always:**
- The entity payload (`entity-extracted`, `entity-retyped`, `/graph` `entities[]`) adds `sources: [{textUnitId, documentName, ordinal}]`, in the entity's `sourceTextUnitIds` order. `ordinal` is the raw 0-based `TextUnit.ordinal`. Ids whose unit is unknown to the controller are omitted. All existing fields stay unchanged.
- The SSE path labels sources from the units reported by this run's `text-unit-extracted` callback, with no store read per event. The `/graph` path labels them from `graphStorePort.textUnits(corpusId)`, read once per request.
- The text-unit endpoint returns `200 {id, documentName, ordinal, text}`. An unknown corpus or unit returns `404 {"error": "<plain-language message>"}`. The lookup is corpus-scoped in both adapters; the Neo4j adapter uses one real `MATCH` on `(corpusId, id)`.
- Panel, entity mode: the existing Description section shows the entity's description and is hidden when it is empty or missing. The new "Source passages" section is hidden when there are no sources and is always hidden in community mode. Community mode keeps today's behaviour exactly.
- Source row label: `{documentName} · passage {ordinal + 1}` (1-based, matching the status line and the failure log's "passage N"). Expanding a row fetches the text once and caches it per row while the panel shows that entity. A non-OK response or a network error shows "Passage not available".
- Relationship rows get `title` = the relationship's description when it is non-empty, and no `title` otherwise.
- Re-emitted `entity-extracted` and `relationship-extracted` events, which carry cumulative merged values, replace the stored description and sources. If the panel shows that entity, it re-renders. Entity details follow `entity-retyped` to the new identity and are cleared wherever `activeRelationships` is reset.
- The `entity-detail` help article mentions descriptions and source passages.

**Never:** fetching passage text before a row is expanded; adding text to the SSE or `/graph` payloads; changing the `text-unit-extracted` SSE payload, the graph canvas API, or the community panel; unscoped text-unit lookups; parsing text-unit ids for labels.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Passage found | `GET /api/corpora/c1/text-units/c1::doc-0::tu-2` (stored) | 200 `{id, documentName:"a.txt", ordinal:2, text}` | none |
| Passage missing | unknown unit id, or unit of another corpus | 404 `{"error": ...}` | panel row shows "Passage not available" |
| Entity with sources | entity ids [u0,u2] of `a.txt` | `sources` = `[{u0,"a.txt",0},{u2,"a.txt",2}]`; panel rows `a.txt · passage 1`, `a.txt · passage 3` | none |
| Legacy entity | description "", ids [] | Description and Source passages sections both hidden | none |
| Relationship tooltip | rel description "Founded in 1995." | the row has `title="Founded in 1995."` | no `title` when empty |
| Re-emitted entity | second `entity-extracted` with merged description and 2 sources while the panel is open | panel shows the merged description and 2 rows | none |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/io/graphrag/core/usecase/TextUnitProgress.java` -- record `(index, total, documentName)`, built only at `ExtractEntitiesAndRelationships.java:104`. Add the `textUnitId` and `ordinal` components and keep a 3-arg convenience constructor.
- `graphrag-core/src/main/java/io/graphrag/core/domain/TextUnit.java` -- `(id, corpusId, documentName, ordinal, text)`. The id is `{corpusId}::doc-{i}::tu-{ordinal}` (`TextUnitSplitter.java:50`), and the ordinal is 0-based per document.
- `graphrag-core/src/main/java/io/graphrag/core/port/GraphStorePort.java` -- the `persistTextUnits` (~66) and `textUnits(corpusId)` (~73) defaults. Add `default Optional<TextUnit> textUnit(String corpusId, String textUnitId)`, which filters `textUnits(corpusId)`, so the test fakes keep compiling.
- `graphrag-adapter-neo4j/.../Neo4jGraphStoreAdapter.java` -- `persistTextUnits` (~228) and `textUnits` (~358). Override `textUnit` with a single `MATCH (t:TextUnit {corpusId:$corpusId, id:$id})`; return empty for a blank corpus id or unit id.
- `graphrag-adapter-neo4j/.../InMemoryGraphStoreAdapter.java` -- `textUnitsByCorpusId` (~30) and `textUnits` (~47). Override `textUnit` with a scoped map lookup.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java`:
  - `startKnowledgeGraphConstruction` (~450) owns the callbacks. Keep a per-run `Map<String, TextUnitProgress>` filled in the `text-unit-extracted` callback, before that unit's entity events.
  - `entityEventPayload` (~500) is used by SSE, `entityRetypedEventPayload` and `/graph` (~171). Give it a label-lookup parameter.
  - The `IllegalArgumentException` handler (~436) already maps to 404 `{"error":…}`, so it can be reused for the new endpoint.
  - `relationshipEventPayload` already has `description`.
- `graphrag-web/src/main/resources/templates/index.html` -- `#entity-detail-panel` (~200). Add `#entity-detail-sources-section` (hidden) containing the heading "Source passages" and `<ul id="entity-detail-sources">`, after the description section.
- `graphrag-web/src/main/resources/static/js/upload.js`:
  - Panel refs (~62–72); `relationshipLines`/`renderEntityDetailRelationships` (~512–547) return strings, so they must become `{text, description}`.
  - `setDetailMode` (~585) hides the description in entity mode; `openEntityDetailPanel` (~631).
  - SSE listeners: `entity-extracted` (~1433), `entity-retyped` (~1445, which already re-renders the open panel), `relationship-extracted` (~1477, where the dedupe keeps the first payload and should update its description).
  - The `/graph` bulk load (~1800); the `activeRelationships = []` resets (~979, ~1114, ~1719); `activeCorpusId` (~44).
  - Add a module-level `activeEntityDetails` map, keyed by identity, holding `{description, sources}`.
- `graphrag-web/src/main/resources/static/css/instrument.css` -- the existing `.node-detail-*` styles; add minimal styles for source rows and passage text using the existing tokens.
- `graphrag-web/src/main/resources/static/help/entity-detail.html` -- the abstract, the SVG labels, and the "In this demo" text.
- Tests:
  - `CorpusControllerTest` (real pipeline into the in-memory store).
  - The in-memory and Neo4j adapter tests (Testcontainers).
  - `ExtractEntitiesAndRelationshipsTest`.
  - The UI templates `PassageProgressStatusUiTest` (fake SSE via `page.route("**/api/corpora/*/progress")`) and `MainScreenDetailPanelUiTest` (opening the panel by node tap).
  - `HelpPaneUiTest` / `MainControllerTest` reference `entity-detail`; keep them green.

## Tasks & Acceptance

**Execution:**
- [x] `TextUnitProgress.java` + `ExtractEntitiesAndRelationships.java` -- add `textUnitId` and `ordinal` and pass `unit.id()`/`unit.ordinal()` -- so the controller can label sources without a store read.
- [x] `GraphStorePort.java`, `Neo4jGraphStoreAdapter.java`, `InMemoryGraphStoreAdapter.java` -- add the scoped `textUnit(corpusId, id)` lookup -- backs the endpoint.
- [x] `CorpusController.java` -- add `sources` to the entity payloads (SSE run map, `/graph` from `textUnits`) and add `GET /api/corpora/{corpusId}/text-units/{textUnitId}` -- the server half of the AC.
- [x] `index.html`, `upload.js`, `instrument.css` -- description display, the expandable Source passages section with lazy fetch and "Passage not available", relationship tooltips, and the `activeEntityDetails` lifecycle (extract, re-emit, retype, `/graph`, resets) -- the panel half of the AC.
- [x] `entity-detail.html` -- describe descriptions, source passages and relationship tooltips -- help requirement.
- [x] Tests:
  - Core: assert `TextUnitProgress` ids and ordinals.
  - Adapters: `textUnit` found, missing, and other-corpus cases.
  - `CorpusControllerTest`: endpoint 200 and 404, `sources` in the SSE entity payload and in `/graph`.
  - New Playwright `EntityDetailSourcesUiTest`, using fake SSE plus a `page.route` for `**/text-units/**`. It opens an entity, sees its description and source label, expands one passage and sees its text, sees "Passage not available" for a 404 passage, and sees a relationship row `title`.
  - Update existing UI or help tests only where markup changes require it.

**Acceptance Criteria:**
- Given a Corpus ingested with descriptions and provenance, when I click an Entity on the canvas, then the Description section shows its description and Source passages lists `{documentName} · passage {n}` rows; expanding one shows the passage text fetched from the new endpoint.
- Given a corpus loaded through the switcher (`/graph`), when I open an Entity, then its description and source passages show the same way as during live ingestion.
- Given the existing suites, when run, then nothing regresses beyond the known flaky or Neo4j-registry failures.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 12 findings — high 0, medium 0, low 2, false 10, maybe-false 0
- findings:
  - `[false]` `[reject]` `text-unit-extracted` payload does not expose `textUnitId`/`ordinal` — the intent's Never list forbids changing that SSE payload; sources travel on entity payloads instead.
  - `[false]` `[reject]` Compatibility constructor's `""`/`-1` defaults leak into source metadata — the only production caller (`ExtractEntitiesAndRelationships:104`) passes the real id and ordinal; the 3-arg form is test-only.
  - `[false]` `[reject]` Blank ids pollute `sourceLabelsById` — same as above: no production path emits a blank id, and entities never carry a blank source id.
  - `[false]` `[reject]` Unknown source ids are dropped instead of shown as fallback rows — the intent mandates "Ids whose unit is unknown to the controller are omitted".
  - `[false]` `[reject]` A duplicate relationship with an empty description erases the tooltip — events carry cumulative merged values and `GraphElementMerger.mergeDescriptions` never drops existing text, so a later event's description is never emptier.
  - `[false]` `[reject]` Relationship description only reachable via `title` — the intent explicitly specifies `title` as the surface; a different surface is a spec change.
  - `[false]` `[reject]` Failed passage fetches are cached, so there is no retry — the intent says "fetches the text once and caches it per row while the panel shows that entity"; reselecting the entity resets the cache.
  - `[false]` `[reject]` Source toggles lack `aria-controls` — the passage `p` is the button's next sibling inside the same `li`, and `aria-expanded` is set; no assistive-technology failure was demonstrated, and adding ids would be new surface.
  - `[false]` `[reject]` Neo4j `textUnit` lacks `LIMIT 1` — text-unit ids are unique per corpus (`{corpusId}::doc-i::tu-n`), so at most one record matches.
  - `[false]` `[reject]` No size guard on passage text — text units are produced by the bounded chunker (~one passage), so an oversized unit cannot be reached.
  - `[low]` `[patch]` (verification-gap) `entity-retyped` payload `sources` unverified — extended the retype assertion in `CorpusControllerTest` to require non-empty, corpus-scoped `sources` with `documentName` and `ordinal`.
  - `[low]` `[patch]` (verification-gap) the UI test's wildcard route never checked that passage fetches use the active corpus id — `EntityDetailSourcesUiTest` now captures the demo corpus id and asserts both passage requests target `/api/corpora/{corpusId}/text-units/`.

## Design Notes

Labels travel with the entity payload so the panel can list sources without a request per row; only the passage text is fetched lazily. Displaying `ordinal + 1` keeps one numbering scheme across the status line ("Extracting passage {index}"), the failure log ("passage N") and the panel. The SSE path labels from `TextUnitProgress` because every source id of an emitted entity belongs to a unit already reported in this run (units are reported before their entities).

## Verification

**Commands** (PowerShell; clear the key first with `$env:OPENAI_API_KEY=$null;`, and delete stale `target\surefire-reports` before reading them):
- `mvn -q -pl graphrag-core -am test` -- expected: core suite green.
- `mvn -q -pl graphrag-adapter-neo4j -am test "-Dapi.version=1.44" "-Dmaven.test.failure.ignore=true"` -- expected: green except the known `Neo4jCorpusRegistryTest` ×2.
- `mvn -q -pl graphrag-web test "-Dapi.version=1.44" "-Dmaven.test.failure.ignore=true"` (after installing upstream modules, or with `-am`) -- expected: `CorpusControllerTest` and `EntityDetailSourcesUiTest` green; only the known flaky UI tests (`CanvasSettingsPopoverUiTest`, `EntityTypeColorToggleUiTest`, `MainScreenDetailPanelUiTest`, `DriftTreeReplayUiTest`, `EntitySearchUiTest`, `CorpusSwitcherUiTest`) may fail.

## Auto Run Result

**Summary:** Entity payloads (`entity-extracted`, `entity-retyped`, `/graph`) now carry labelled `sources` (`textUnitId`, `documentName`, raw `ordinal`) in `sourceTextUnitIds` order. A new corpus-scoped `GET /api/corpora/{corpusId}/text-units/{textUnitId}` returns one passage. In entity mode the detail panel shows the description, a "Source passages" list labelled `{document} · passage {ordinal+1}` whose text is fetched lazily when a row expands ("Passage not available" on failure), and relationship descriptions as row tooltips. Re-emitted and retyped entities update the open panel.

**Files changed:**
- `graphrag-core/.../TextUnitProgress.java`: adds `textUnitId` and `ordinal`, keeping the 3-argument constructor.
- `graphrag-core/.../ExtractEntitiesAndRelationships.java`: reports each unit's id and ordinal.
- `graphrag-core/.../GraphStorePort.java`: default `textUnit(corpusId, id)` lookup.
- `graphrag-adapter-neo4j/.../Neo4jGraphStoreAdapter.java`, `InMemoryGraphStoreAdapter.java`: corpus-scoped `textUnit` overrides (one Neo4j `MATCH`).
- `graphrag-web/.../CorpusController.java`: `sources` on entity payloads (per-run label map on SSE, one `textUnits` read on `/graph`) and the new text-unit endpoint with 404s.
- `graphrag-web/.../templates/index.html`: the "Source passages" section.
- `graphrag-web/.../static/js/upload.js`: per-entity detail state, description and source rendering, lazy passage cache, relationship tooltips, updates on re-emit and retype.
- `graphrag-web/.../static/css/instrument.css`: source row styles.
- `graphrag-web/.../help/entity-detail.html`: help article covers descriptions and source passages.
- Tests: `ExtractEntitiesAndRelationshipsTest`, both adapter tests, `CorpusControllerTest` (source values and order for SSE, `/graph` and retype; the endpoint), and the new `EntityDetailSourcesUiTest` (lazy fetch, failure, tooltip present and absent, legacy entity, re-emit while open via a fake `EventSource`, corpus-scoped fetch URL).

**Review findings:** 12 findings (blind 10, edge-case 0, verification-gap 2, intent-alignment descriptive only).
- Patched 2 (both `low`): retype `sources` assertions, and the passage-URL corpus-id assertion.
- Deferred 0.
- Rejected 10 (all `false`):
  - Unchanged `text-unit-extracted` payload: the intent requires it.
  - `""`/`-1` compatibility defaults and blank-id map pollution: no production caller can produce them.
  - Unknown ids omitted: the intent mandates it.
  - Tooltip erased by an empty duplicate: merged descriptions never shrink.
  - Tooltip-only relationship description: the intent specifies `title`.
  - Failure caching: the intent specifies fetch-once caching.
  - No `aria-controls`: no demonstrated assistive-technology failure.
  - Neo4j without `LIMIT 1`: ids are unique per corpus.
  - No passage size guard: units are bounded by the chunker.

**Follow-up review recommended:** `false`. Patched counts are high 0, medium 0, low 2.

**Verification:**
- `mvn -q install -Dapi.version=1.44 -Dmaven.test.failure.ignore=true` with `OPENAI_API_KEY` cleared. All core, adapter and web suites pass except the known failures:
  - `Neo4jCorpusRegistryTest` ×2.
  - Flaky UI tests: `CanvasSettingsPopoverUiTest`, `CorpusSwitcherUiTest`, `EntityTypeColorToggleUiTest`.
  - `MainScreenDetailPanelUiTest` (1 test): its last assertion expects a community-hull tap to leave the panel closed. It fails the same way with the baseline `upload.js` (`80eee5c`), so 13.4 did not cause it.
- Targeted runs: `CorpusControllerTest` 41/41, `EntityDetailSourcesUiTest` 2/2, `HelpPaneUiTest` 11/11, `PassageProgressStatusUiTest` 3/3, `MainControllerTest` 8/8, `ExtractEntitiesAndRelationshipsTest` 15/15, `Neo4jGraphStoreAdapterTest` 14/14, `InMemoryGraphStoreAdapterTest` 6/6.

**Residual risks:**
- UI tests use synthetic SSE and passage responses, not the real extraction pipeline end to end.
- A failed passage fetch stays cached until the entity is re-selected.
- `MainScreenDetailPanelUiTest`'s stale hull-tap assertion remains a known failure.