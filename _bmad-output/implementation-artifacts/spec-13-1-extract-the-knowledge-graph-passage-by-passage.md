---
title: 'Story 13.1: Extract the Knowledge Graph Passage by Passage'
type: 'feature'
created: '2026-10-01'
status: 'in-review'
baseline_revision: '52fdc9297bc182da4119b56b29dea66954968243'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** `OpenAiLlmPort.extract(Corpus)` sends the whole Corpus in one prompt, so long documents are sampled unevenly (mostly their beginning), the response can be truncated, and the graph appears all at once at the end of ingestion.

**Approach:** Split each document into overlapping Text Units in `graphrag-core`, extract one Text Unit at a time against a fixed Entity type list, persist each unit and its result before the next one, and report per-unit progress on the existing SSE stream so the canvas grows passage by passage (AD-24).

## Boundaries & Constraints

**Always:**
- `graphrag-core` stays framework-free (AD-1); only `graphrag-adapter-langchain4j` touches LangChain4j/OpenAI (AD-3).
- Text Units: ~6,000 characters, ~600 overlap, cut on a paragraph break, else a sentence end, else whitespace, inside the window; stable id `{corpusId}::doc-{documentIndex}::tu-{ordinal}`; `ordinal` is 0-based per document.
- Entity types: exactly Person, Organization, Product, Technology, Version, Event, Location, Concept; matched case-insensitively and canonicalised; anything else (or blank) → `Concept`. Applied in core to Entity types and Relationship source/target types.
- Units are extracted sequentially; each unit's `TextUnit` + extraction are persisted (corpus-scoped, AD-20) before the next unit starts (AD-14).
- Event order per unit: `text-unit-extracted` `{index (1-based), total, documentName}`, then that unit's `entity-extracted`/`relationship-extracted` events.
- Any unit failure stops ingestion → corpus `FAILED` + existing `error` event (FR-5); server log names document and passage number.
- Offline stub stays deterministic and network-free; all existing tests keep passing.
- `LlmPort` stays usable as a lambda (`corpus -> ...`): the new per-unit method is a `default` that wraps the unit as a single-document Corpus and calls `extract(Corpus)`.

**Never:** No descriptions, provenance fields, `weight`, or `MENTIONED_IN` (Story 13.2); no duplicate resolution beyond today's `name::type` MERGE (Story 13.3); no detail-panel or text-unit endpoint (Story 13.4); no retries; no partial graph on failure; do not reuse or change the Vector Baseline's 500-character chunker.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Short document | 1 doc, 800 chars | 1 Text Unit, ordinal 0, whole text | No error expected |
| Long document | 1 doc, ~20,000 chars of paragraphs | ≥4 units, each ≤6,000 chars, consecutive units share overlapping text, every character of the doc is in some unit | No error expected |
| No boundary | 15,000 chars, no whitespace | hard cuts at 6,000; still progresses and terminates | No error expected |
| Late Entity | Entity name only in the last unit | Entity is extracted and persisted | No error expected |
| Off-list type | LLM returns type `"Place"` / `"person"` / blank | `Concept` / `Person` / `Concept` | No error expected |
| Empty doc | blank content among other docs | skipped, contributes 0 units | No error expected |
| Unit fails | 2nd of 3 units throws | units 1 persisted, unit 3 never called, exception propagates naming doc + passage 2 | Controller marks `FAILED`, emits `error`, logs it |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/io/graphrag/core/domain/` -- add `TextUnit` record (`id, corpusId, documentName, ordinal, text`); `Chunk.java` is the style reference. Keep `UploadedDocument(filename, content)` as the document source.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/ConstructVectorIndex.java:55-90` -- existing chunking loop to mirror stylistically; **do not modify**.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/ExtractEntitiesAndRelationships.java` -- currently `extract(corpus)` → one `llmPort.extract(corpus)` → one `graphStorePort.persist(corpusId, extraction)` → callbacks. Rewrite `run` as the per-unit loop; keep `extract(Corpus)` (used by `BuildKnowledgeGraph.build`) returning the merged result of all units.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/BuildKnowledgeGraph.java` -- alias subclass; must keep compiling.
- `graphrag-core/src/main/java/io/graphrag/core/port/LlmPort.java:24` -- `extract(Corpus)` is the only abstract method; 11 lambda + 4 anonymous implementations exist across tests (`ExtractEntitiesAndRelationshipsTest`, `CorpusControllerTest.stubLlmPort()`, `AnswerDriftSearchTest`, …) — must stay valid.
- `graphrag-core/src/main/java/io/graphrag/core/port/GraphStorePort.java` -- add `default void persistTextUnits(String corpusId, Collection<TextUnit>)` (no-op) and `default Collection<TextUnit> textUnits(String corpusId)` (empty).
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jGraphStoreAdapter.java:55-70,124-145` -- add `text_unit_corpus_id` constraint in `ensureConstraints`; implement both methods with corpus-scoped `MERGE (t:TextUnit {corpusId, id}) SET ...`, following `persistEntities(String, …)`.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/InMemoryGraphStoreAdapter.java:19-28` -- add a per-corpus `LinkedHashMap` of Text Units, same pattern as `entitiesByCorpusId`.
- `graphrag-adapter-neo4j/src/test/java/.../Neo4jGraphStoreAdapterTest.java` -- Testcontainers (`neo4j:2026.08.1-community`) pattern for the new round-trip test.
- `graphrag-adapter-langchain4j/src/main/java/.../OpenAiLlmPort.java:76-112` -- override the per-unit `extract`, prompt (built by a package-private `extractionPrompt(TextUnit, List<String>)`) lists the given types; `extract(Corpus)` loops over `TextUnitSplitter` units and merges.
- `graphrag-adapter-langchain4j/src/main/java/.../LangChain4jLlmPort.java:50-80` -- override the per-unit `extract` with the existing sentence/regex logic on the unit text.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java:449-470` -- `startKnowledgeGraphConstruction`: pass a Text Unit callback emitting `text-unit-extracted`; `catch` must log (`LOG.warn`) before `markFailed`.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusProgressService.java:29` -- `MAX_BUFFERED_EVENTS_PER_CORPUS = 500` is too small once units re-emit entities; raise to 5,000.
- `graphrag-web/src/main/resources/static/js/upload.js:1407-1418,1510-1543` -- add a `text-unit-extracted` listener next to `ingestion-started`; while BUILDING it sets `workflowStatusText` to `Extracting passage {index} of {total} — {documentName}`; `renderWorkflowStatus('BUILDING')` keeps its current default text.
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java:300-340` -- existing event-capture test (`eventTypeCaptor`) to extend for event order.

## Tasks & Acceptance

**Execution:**
- `graphrag-core/.../domain/TextUnit.java` -- new record with javadoc -- shared unit of extraction (and later provenance).
- `graphrag-core/.../usecase/EntityTypes.java` -- new: `List<String> ALL` (the 8 types, in order) + `static String normalize(String)` -- one source of truth for the type list.
- `graphrag-core/.../usecase/TextUnitSplitter.java` -- new: `List<TextUnit> split(Corpus)` with the size/overlap/boundary rules above; constants `TARGET_CHARS = 6000`, `OVERLAP_CHARS = 600` -- deterministic, framework-free.
- `graphrag-core/.../port/LlmPort.java` -- add `default GraphExtraction extract(TextUnit unit, List<String> entityTypes)` wrapping the unit as a one-document Corpus -- keeps lambdas valid.
- `graphrag-core/.../usecase/TextUnitProgress.java` -- new record `(int index, int total, String documentName)`.
- `graphrag-core/.../usecase/ExtractEntitiesAndRelationships.java` -- per-unit loop: extract → normalize types → `persistTextUnits` + `persist(corpusId, unitExtraction)` → progress callback → entity/relationship callbacks; wrap a unit's failure in an `IllegalStateException` naming document and passage (`ordinal + 1`); new overload `run(Corpus, Consumer<TextUnitProgress>, Consumer<Entity>, Consumer<Relationship>)`, old overloads delegate with `null`.
- `graphrag-core/.../port/GraphStorePort.java` + both adapters in `graphrag-adapter-neo4j` -- `persistTextUnits` / `textUnits` as mapped above.
- `graphrag-adapter-langchain4j/.../OpenAiLlmPort.java` + `LangChain4jLlmPort.java` -- per-unit overrides as mapped above.
- `graphrag-web/.../CorpusController.java`, `CorpusProgressService.java`, `static/js/upload.js` -- emit, buffer, and display progress as mapped above.
- Tests: `graphrag-core/src/test/.../usecase/TextUnitSplitterTest.java` (matrix rows 1–3, 6), `EntityTypesTest.java` (row 5), extend `ExtractEntitiesAndRelationshipsTest` (rows 4, 7; per-unit persistence before next call; callback order); `Neo4jGraphStoreAdapterTest` (Text Unit round trip, corpus-scoped); `LangChain4jLlmPortTest` (per-unit extract on a unit's text) / `OpenAiLlmPortTest` (a new package-private `String extractionPrompt(TextUnit, List<String>)` in `OpenAiLlmPort` contains every type name and the unit text — no network call; the existing tests only cover `parseExtraction`); `CorpusControllerTest` (a `text-unit-extracted` event precedes the first `entity-extracted`, payload has `index`, `total`, `documentName`).

**Acceptance Criteria:**
- Given a multi-unit corpus ingested through `POST /api/corpora`, when ingestion completes, then the captured progress events contain one `text-unit-extracted` per Text Unit with `index` 1..`total`, each preceding that unit's entity/relationship events, followed by `community-detected` and `ingestion-complete`.
- Given the Demo Dataset or the offline demo, when it is ingested, then it reaches READY and all existing unit and UI tests that passed before still pass.
- Given ingestion is running in the browser, when a `text-unit-extracted` event arrives, then the workflow status line shows `Extracting passage {index} of {total} — {documentName}`.
- Given a corpus whose second unit's extraction throws, when ingestion runs, then the corpus is `FAILED`, the `error` event is emitted, and the server log names the document and passage 2.

## Spec Change Log

## Review Triage Log

## Design Notes

AD-24 says "the old `extract(Corpus)` stays as a default method that loops over Text Units". Implemented inverted instead: `extract(Corpus)` stays abstract and the new per-unit method is the `default`. Same outcome for adapters (both real adapters override the per-unit method), but `LlmPort` remains a functional interface, so the 15 existing lambda/anonymous test implementations compile unchanged.

Type normalisation lives in core (`EntityTypes.normalize`) rather than in each adapter, so the offline stub and OpenAI adapter can never disagree on canonical types and `name::type` identities stay stable.

## Verification

**Commands:**
- `mvn -q -pl graphrag-core,graphrag-adapter-langchain4j,graphrag-adapter-neo4j -am test` -- expected: BUILD SUCCESS
- `mvn -q -pl graphrag-web -am test -Dtest='CorpusControllerTest' -Dsurefire.failIfNoSpecifiedTests=false` -- expected: BUILD SUCCESS
- `mvn -q -pl graphrag-web -am test` -- expected: no failures beyond the pre-existing ones on baseline `52fdc92` (implementer measured: `CanvasSettingsPopoverUiTest` ×1, `EntityTypeColorToggleUiTest` ×3, `MainScreenDetailPanelUiTest` ×1 — the exact set varies between runs; earlier runs instead showed `DriftTreeReplayUiTest` ×1, `MainScreenDetailPanelUiTest` ×2, `EntitySearchUiTest` ×2)

**Environment notes (this machine):**
- Run Maven with `env -u OPENAI_API_KEY` — with the key set, Spring tests call real OpenAI (now once per passage) and hit their 15 s pipeline timeout.
- Testcontainers 1.20.4 needs `-Dapi.version=1.44` against the local Docker 29, and must run outside the sandbox (named-pipe access).
- `Neo4jCorpusRegistryTest` ×2 (`reconcileInterruptedCorpora…`) fail identically on baseline (shared container state).

## Auto Run Result

Status: in-review (handed over mid-run on user request, 2026-10-01)
Blocking condition: none

**Handover:** step-01 (route), step-02 (plan → ready-for-dev) and step-03 (implement + verify) are complete; **step-04 (review) has not run**. Resume with `/bmad-build-auto` pointing at this spec file — its `in-review` status routes straight to step-04.

**Implemented** (see the diff against `baseline_revision`): `TextUnit`, `TextUnitProgress`, `EntityTypes`, `TextUnitSplitter` in core; per-unit default `LlmPort.extract(TextUnit, List<String>)`; per-unit loop with type normalisation, per-unit persistence and progress callback in `ExtractEntitiesAndRelationships`; `persistTextUnits`/`textUnits` on `GraphStorePort` + Neo4j (constraint `text_unit_corpus_id`) + in-memory adapters; per-unit overrides in the OpenAI adapter (package-private `extractionPrompt`) and the offline stub; `text-unit-extracted` SSE + `LOG.warn` on failure in `CorpusController`; SSE buffer 500 → 5,000; status-line text in `upload.js`.

**Verified by implementer:** core, langchain4j, neo4j module tests pass (except the 2 pre-existing registry failures); `CorpusControllerTest` 38/38 incl. 2 new; full web suite 159 tests with only baseline-identical failures. Re-checked at handover: `mvn -pl graphrag-core,graphrag-adapter-langchain4j -am test` green.

**Open risks for the reviewer:**
- No UI test asserts the new status-line text (spec did not require one).
- Overlapping passages re-emit the same Entity as `entity-extracted`; canvas `addEntity` is assumed idempotent (it reuses existing nodes) — worth confirming for relationship edges too.
- With a real API key ingestion is now one LLM call per passage: slower, and relevant for demo timing.
