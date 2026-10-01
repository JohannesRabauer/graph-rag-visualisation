---
title: 'Story 13.3: Resolve duplicate entities before they reach Neo4j'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: 'cc15483ec31bd768e76e5548e8d7d7ba59bd2d8d'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Per-passage extraction (13.1) produces the same real-world entity under spelling, case, punctuation and type variants ("OpenAI", "openai.", "OpenAI" as ORGANIZATION vs PRODUCT), so Neo4j gets several nodes for one thing and relationships attach to the wrong variant.

**Approach:** A deterministic core `EntityResolver` (AD-26) maps every extracted Entity and every Relationship endpoint onto the corpus's already-known Entities by normalised name, picks the majority type, and the per-unit pipeline persists only resolved identities; AD-10's `name::type` MERGE stays the persistence key.

## Boundaries & Constraints

**Always:**
- Name key = NFKC → strip leading/trailing Unicode punctuation and symbols → collapse internal whitespace runs to one space → trim → `toLowerCase(Locale.ROOT)`. If the key is empty, fall back to the trimmed lower-cased raw name.
- Canonical name of a resolved Entity = the first-seen surface spelling (trimmed) for that key in the run.
- Type = the type with the most Entity mentions for that key across the run so far; ties keep the type seen earliest. A Relationship endpoint whose name is not yet known registers the name with its endpoint type at 0 mentions (so a later real Entity mention outvotes it).
- Descriptions merge via `GraphElementMerger` (distinct sentences, 1,000-char cap); source ids union; a re-extracted Relationship gains weight (= distinct source ids, existing rule).
- Relationship endpoints (`source`/`sourceType`, `target`/`targetType`) are rewritten to the resolved canonical name and type before persistence and before callbacks.
- Per-unit commit (AD-14) is preserved: each unit's resolved changes are persisted before the next unit is extracted; callbacks fire only after persistence.
- When the majority type of an already-persisted Entity flips, the store re-keys the existing node (no second node left behind), every merged Relationship touching it is re-keyed and re-persisted with the new endpoint type, and the browser is told the old identity so the canvas migrates the node and its edges.
- `extract(Corpus)` (non-persisting) applies the same resolution.

**Never:** fuzzy/edit-distance/embedding/LLM-based merging; non-deterministic ordering (use insertion-ordered maps); changing the `normalizedIdentity` formula or AD-10 MERGE key; cross-corpus resolution; resolution across separate runs (each run starts from an empty resolver — a corpus is ingested once).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Case/punctuation variant | unit1 `OpenAI` ORG; unit2 `"openai."` ORG | one entity `OpenAI::ORGANIZATION`, ids {u1,u2}, descriptions merged | none |
| Whitespace/NFKC variant | `Ada  Lovelace`, `ＡＤＡ Lovelace` (fullwidth) | resolves to `Ada  Lovelace`'s key; canonical name first-seen | none |
| Type conflict, no flip | u1 `Paris` LOCATION; u2 `Paris` PERSON | one entity `Paris::LOCATION` (tie keeps earlier) | none |
| Type flip | u1 `Jaguar` ANIMAL; u2,u3 `Jaguar` ORGANIZATION | after u3 the stored entity is `jaguar::organization`; no `jaguar::animal` node remains; retype callback carries previous identity `jaguar::animal` | none |
| Relationship endpoint variant | entity `OpenAI` ORG known; rel `openai,` (PRODUCT) –FOUNDED_BY→ `Sam Altman` | rel persisted as `OpenAI`/ORGANIZATION → `Sam Altman`; no new `openai,` node | none |
| Endpoint-only name later typed | rel endpoint `Acme` PERSON (unknown); later entity `Acme` ORG | Acme becomes ORGANIZATION (1 vs 0 mentions); edge follows | none |
| Same rel re-extracted | same resolved rel in u1 and u2 | one relationship, weight 2, ids {u1,u2} | none |
| Empty normalised key | name `"..."` | keyed by raw trimmed lower-case name, no crash | none |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/io/graphrag/core/usecase/ExtractEntitiesAndRelationships.java` -- `run(...)` loop (≈75–108) keeps `mergedEntities`/`mergedRelationships` across the run and per-unit changed maps; `extract(Corpus)` (≈39–52); `relationshipKey` (≈170) uses `Entity.identityOf`. Resolution plugs in after `extractUnit` (types already normalised by `EntityTypes.normalize`, ids stamped). Callbacks: `Consumer<TextUnitProgress>`, `Consumer<Entity>`, `Consumer<Relationship>`.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/BuildKnowledgeGraph.java` -- subclass used by `CorpusController`; inherits `run` overloads.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/GraphElementMerger.java` -- reuse `merge(Entity,Entity)`, `merge(Relationship,Relationship)`, `mergeDescriptions`. Note `merge(Entity,Entity)` keeps the first argument's name/type — build the resolved Entity with canonical name/type before merging.
- `graphrag-core/src/main/java/io/graphrag/core/domain/Entity.java` -- `normalizedIdentity()` / `identityOf(name,type)` = trimmed lower `name::type`; read-only.
- `graphrag-core/src/main/java/io/graphrag/core/port/GraphStorePort.java` -- abstract `persistEntities`/`persistRelationships`, scoped defaults, `persist(corpusId, GraphExtraction)` (~113). No retype method exists; add a `default` (no-op) so the test fakes (`DetectCommunitiesTest`, `AnswerLocalSearchTest`, `AnswerGlobalSearchTest`, `AnswerDriftSearchTest`, fakes in `ExtractEntitiesAndRelationshipsTest`) keep compiling.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jGraphStoreAdapter.java` -- `persistEntities` (~129) MERGE `{corpusId, normalizedIdentity}`; `persistRelationships` (~161) MERGEs endpoint nodes by identity then `(s)-[r {corpusId, source, type, target}]->(t)` and SETs `sourceType/targetType`. Because relationships hang off nodes and are keyed by raw names (unchanged — canonical name is stable), re-keying the node then re-persisting the relationships finds the same edge.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/InMemoryGraphStoreAdapter.java` -- global + `entitiesByCorpusId` maps keyed by `normalizedIdentity` (persist ~52–126, reads ~188–216); relationship key `source::type::target` raw strings.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- `startKnowledgeGraphConstruction` (~450) wires callbacks to `corpusProgressService.emit`; payload builders `entityEventPayload`/`relationshipEventPayload` (~498–512, also used by `/graph`).
- `graphrag-web/src/main/resources/static/js/upload.js` -- SSE listeners (~1433–1473); `activeRelationships` list keyed by identities feeds the detail panel.
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` -- `ensureNode` (~811), `addEntity` (~826), `addRelationship` (~862; edge id `sourceIdentity->type->targetIdentity`); no rename/remove logic yet. Check for other identity-keyed caches (search index, colour/type filters) that must follow a retype.
- Tests: `graphrag-core/src/test/java/io/graphrag/core/usecase/ExtractEntitiesAndRelationshipsTest.java` (recording fakes), `GraphElementMergerTest.java`; Neo4j/in-memory adapter tests under `graphrag-adapter-neo4j/src/test`; `graphrag-web/src/test/.../CorpusControllerTest.java`; UI template `PassageProgressStatusUiTest` (Playwright `page.route("**/api/corpora/*/progress")` with fake SSE body, `#demo-dataset-button`).

## Tasks & Acceptance

**Execution:**
- [x] `graphrag-core/.../usecase/EntityResolver.java` (new) -- stateful per-run resolver: `nameKey(String)`, resolve an Entity (returns resolved Entity + optional previous identity when the type flipped), resolve a Relationship's endpoints -- AD-26 single place for resolution rules.
- [x] `graphrag-core/.../usecase/ExtractEntitiesAndRelationships.java` -- apply the resolver in `run` and `extract`; on a flip, call the store re-key, move `mergedEntities` entry, re-key and re-persist affected `mergedRelationships`, include them in the unit's relationship callbacks; add a `run` overload taking an extra `BiConsumer<String, Entity> onEntityRetyped` (previous identity, resolved entity) fired after persistence and before the entity callbacks; existing overloads delegate with `null`.
- [x] `graphrag-core/.../port/GraphStorePort.java` -- add `default void retypeEntity(String corpusId, String previousIdentity, Entity resolved)` (no-op default).
- [x] `Neo4jGraphStoreAdapter.java` -- implement: match old identity in corpus, SET new `normalizedIdentity`, name, type, description, ids; update `sourceType`/`targetType` on its incident relationships; no-op if the old node is absent.
- [x] `InMemoryGraphStoreAdapter.java` -- implement: remove old key, put resolved entity under new key in global and corpus maps; update stored relationships' endpoint types.
- [x] `CorpusController.java` -- pass the retype callback; emit SSE `entity-retyped` with `previousIdentity` plus the normal entity payload fields.
- [x] `upload.js` + `graph-canvas.js` -- handle `entity-retyped`: `GraphCanvas.retypeEntity(previousIdentity, identity, name, type)` migrates the node (keeps position, recreates incident edges with new ids, removes old node, no duplicate if new id already present); rewrite identities in `activeRelationships`.
- [x] Tests -- core unit tests for every I/O matrix row (resolver + use case with recording fakes, asserting persisted calls and callback order); adapter tests for `retypeEntity` (in-memory always; Neo4j Testcontainers); `CorpusControllerTest` asserting an `entity-retyped` event for a flip scenario; one Playwright UI test feeding fake SSE with `entity-extracted` → `entity-retyped` and asserting a single node with the new identity and its edge preserved.

**Acceptance Criteria:**
- Given a corpus whose passages mention one entity under variants, when ingestion completes, then Neo4j (and the in-memory store) hold exactly one Entity node for it, keyed by `canonicalName::majorityType`, and no relationship references a variant identity.
- Given a type flip mid-run, when the browser receives the SSE stream, then the canvas shows one node for the entity with the new identity and all its edges.
- Given the existing test suites, when run, then nothing regresses beyond the known flaky/Neo4j-registry failures.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 15 findings — high 0, medium 1, low 9, false 5, maybe-false 0
- findings:
  - `[low]` `reject` Endpoint-only spelling (e.g. `openai,`) stays canonical if a relationship names it before any Entity mention — spec-conformant (canonical = first-seen spelling); entities are resolved before relationships within a unit, so only cross-unit endpoint-first ordering hits it; fix needs a new name-upgrade branch.
  - `[medium]` `patch` Stale `previousIdentity` left in `changedEntities` when a same-key variant earlier in the unit is followed by a flip — persist then re-created the old node and emitted it — `accumulateResolved` now removes `previousIdentity` from `changedEntities`; test `typeFlipWithinUnitDoesNotPersistOrEmitStalePreviousIdentity`.
  - `[low]` `reject` In-memory `retypeEntity` mutates the unscoped global map across corpora — the global map is already last-writer-wins across corpora on persist (pre-existing semantics, not read by corpus-scoped flows); a guard adds complexity.
  - `[false]` `reject` In-memory relationship rewrite collapses keys — keys are raw `source::type::target`; canonical names never change on retype, so rewritten keys equal the original keys and cannot collide.
  - `[false]` `reject` Neo4j retype may collide with an existing node at the new identity — the resolver yields one identity per name key per run and retype runs before the unit's persist; identities with equal lower-cased canonical names share a key.
  - `[false]` `reject` Neo4j retype leaves parallel equivalent edges — relationship MERGE is keyed on raw names, unchanged by retype; all edges of the flipped entity already share one identity, so none become equivalent.
  - `[low]` `reject` Spec matrix says `ANIMAL`/`jaguar::animal` but `EntityTypes.normalize` maps it to `Concept` — behaviour (flip and previous identity) is correct with the normalised type; the fix would edit this build's spec.
  - `[low]` `patch` Adapter retype tests only covered the source side — added incoming `Market -> Jaguar` relationships to the in-memory and Neo4j retype tests, asserting both relationships remain and `targetType` becomes Organization.
  - `[false]` `reject` Canvas `retypeEntity` drops edge data/classes — `addRelationship` creates edges with exactly `id/source/target/label`; the only edge classes are transient trace highlights applied after ingestion.
  - `[low]` `reject` UI test does not assert exactly one node/edge — old node absence is asserted via `entityNodeFillColor`; counting edges needs new test-support API surface.
  - `[low]` `reject` (edge-case) Scoped retype removes another corpus's global entry — same root as the global-map row above; same reason.
  - `[false]` `reject` (edge-case claim) Matrix says `jaguar::animal` — same as the `ANIMAL` row; claim wording only, behaviour verified with `jaguar::concept`.
  - `[low]` `patch` (verification-gap) `entity-retyped` payload fields `name`/`type`/`description` unverified server-side — `CorpusControllerTest` matcher now requires them.
  - `[low]` `patch` (verification-gap) Target-side retype unverified — same root as the adapter row; also added an incoming relationship to the core flip test asserting `targetType` Organization.
  - `[low]` `reject` (intent-alignment) Tests exercise sliced surfaces rather than end-to-end ingestion — `CorpusControllerTest` drives the real pipeline into the in-memory store; adapter + core + UI tests compose the chain; no named defect.

## Design Notes

Why re-key instead of deferring persistence: AD-14 requires each unit persisted before the next, so the majority type can only be known incrementally; re-keying the single existing node keeps one node per real entity without buffering. The canonical name never changes after first sight, so Neo4j relationship MERGE keys (raw names) stay stable and only endpoint types need updating. The resolver guarantees one resolved identity per name key, so a node with the new identity cannot already exist from this run.

## Verification

**Commands** (PowerShell; clear the key first: `$env:OPENAI_API_KEY=$null;`):
- `mvn -q -pl graphrag-core -am test` -- expected: all core tests green (read `target\surefire-reports\*.txt`).
- `mvn -q -pl graphrag-adapter-neo4j -am test "-Dapi.version=1.44" "-Dmaven.test.failure.ignore=true"` -- expected: green except known `Neo4jCorpusRegistryTest` ×2.
- `mvn -q -pl graphrag-web -am test "-Dapi.version=1.44" "-Dmaven.test.failure.ignore=true"` -- expected: `CorpusControllerTest` and the new UI test green; only known flaky UI tests (`CanvasSettingsPopoverUiTest`, `EntityTypeColorToggleUiTest`, `MainScreenDetailPanelUiTest`, `DriftTreeReplayUiTest`, `EntitySearchUiTest`, `CorpusSwitcherUiTest`) may fail.

## Auto Run Result

**Summary:** Added a deterministic per-run `EntityResolver` (AD-26) that resolves entity name variants (NFKC, punctuation strip, whitespace collapse, case fold) and relationship endpoints to a canonical first-seen name and majority type. When the majority type flips mid-run, the store re-keys the existing node (`GraphStorePort.retypeEntity`, implemented in Neo4j and in-memory), affected relationships are re-persisted, and the browser receives an `entity-retyped` SSE event so the canvas migrates the node and its edges.

**Files changed:**
- `graphrag-core/.../usecase/EntityResolver.java`: new resolver (name key, type tally, endpoint resolution).
- `graphrag-core/.../usecase/ExtractEntitiesAndRelationships.java`: resolution in `run`/`extract`, flip handling, new `run` overload with the retype callback.
- `graphrag-core/.../port/GraphStorePort.java`: `retypeEntity` default no-op.
- `Neo4jGraphStoreAdapter.java` / `InMemoryGraphStoreAdapter.java`: `retypeEntity` implementations.
- `CorpusController.java`: emits `entity-retyped` with `previousIdentity`.
- `graph-canvas.js` / `upload.js`: `GraphCanvas.retypeEntity` node/edge migration; detail-panel state follows the retype.
- Tests: core extraction tests (matrix rows, in-unit flip), adapter retype tests (source and target sides), `CorpusControllerTest` retype event, `PassageProgressStatusUiTest` retype UI test.

**Review:** 15 findings. 4 patched (1 medium, 3 low); 0 deferred; 11 rejected (5 false, 6 low/observational; reasons in the Review Triage Log).

**Follow-up review recommended:** false (patched: high 0, medium 1, low 3).

**Verification:** core 15/15 `ExtractEntitiesAndRelationshipsTest` and the full core suite green; Neo4j module green except the known `Neo4jCorpusRegistryTest` ×2; `CorpusControllerTest` 40/40, `PassageProgressStatusUiTest` 3/3; the pre-patch full web run failed only the known flaky UI tests.

**Residual risks:** canonical name stays the first-seen spelling even when it came from a relationship endpoint (e.g. `openai,`); the unscoped in-memory global map is not corpus-safe on retype (pre-existing last-writer-wins semantics); type flips add one extra Neo4j write per flip.