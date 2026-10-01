---
title: 'Story 13.3: Resolve duplicate entities before they reach Neo4j'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: 'cc15483ec31bd768e76e5548e8d7d7ba59bd2d8d'
baseline_commit: 'cc15483ec31bd768e76e5548e8d7d7ba59bd2d8d'
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

- No review subagents were run because this session is itself a sub-agent and higher-priority instructions prohibit nested delegation unless explicitly requested. Direct implementation verification passed except known flaky/registry failures listed in Verification.

## Design Notes

Why re-key instead of deferring persistence: AD-14 requires each unit persisted before the next, so the majority type can only be known incrementally; re-keying the single existing node keeps one node per real entity without buffering. The canonical name never changes after first sight, so Neo4j relationship MERGE keys (raw names) stay stable and only endpoint types need updating. The resolver guarantees one resolved identity per name key, so a node with the new identity cannot already exist from this run.

## Verification

**Commands** (PowerShell; clear the key first: `$env:OPENAI_API_KEY=$null;`):
- `mvn -q -pl graphrag-core -am test` -- expected: all core tests green (read `target\surefire-reports\*.txt`).
- `mvn -q -pl graphrag-adapter-neo4j -am test "-Dapi.version=1.44" "-Dmaven.test.failure.ignore=true"` -- expected: green except known `Neo4jCorpusRegistryTest` ×2.
- `mvn -q -pl graphrag-web -am test "-Dapi.version=1.44" "-Dmaven.test.failure.ignore=true"` -- expected: `CorpusControllerTest` and the new UI test green; only known flaky UI tests (`CanvasSettingsPopoverUiTest`, `EntityTypeColorToggleUiTest`, `MainScreenDetailPanelUiTest`, `DriftTreeReplayUiTest`, `EntitySearchUiTest`, `CorpusSwitcherUiTest`) may fail.
