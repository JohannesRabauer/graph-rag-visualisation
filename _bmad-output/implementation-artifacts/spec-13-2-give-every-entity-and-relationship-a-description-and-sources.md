---
title: 'Story 13.2: Give Every Entity and Relationship a Description and Its Source Passages'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_commit: 'ad96da4c648ebccb1068dde9bf55c342bad097fa'
baseline_revision: 'ad96da4c648ebccb1068dde9bf55c342bad097fa'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-13-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 13.1 extracts per Text Unit, but Entities and Relationships are still bare `{name, type}` / `{source, type, target}`: there is no description for searches and summaries to use, and no link back to the passage they came from. The OpenAI call also has no output-token limit, so a truncated response can't be told apart from a complete one.

**Approach:** Extend `Entity` and `Relationship` with `description` and `sourceTextUnitIds` (plus `weight` on Relationship), ask the LLM for descriptions, stamp each unit's id onto its results in core, and merge repeat sightings across units in core before persisting. Store the fields in both graph-store adapters with `MENTIONED_IN` links to Text Units, add `description` to the SSE payloads, and fail visibly on token-limit truncation (AD-25).

## Boundaries & Constraints

**Always:**
- Core stays framework-free (AD-1); only `graphrag-adapter-langchain4j` touches LangChain4j/OpenAI (AD-3).
- `Entity(name, type, description, sourceTextUnitIds)` and `Relationship(source, sourceType, type, target, targetType, description, sourceTextUnitIds, weight)` become the canonical constructors. The existing 2- and 5-argument constructors remain as overloads that default to description `""`, sourceTextUnitIds `List.of()` and weight `1`. In the compact constructors, null description becomes `""` (trimmed) and null ids become `List.of()` (otherwise `List.copyOf`). Identity, `normalizedIdentity()` and equality keys are unchanged (MERGE stays on lowercased `name::type`, corpus-scoped).
- Core (`ExtractEntitiesAndRelationships`) sets `sourceTextUnitIds = [unit.id()]` on every Entity and Relationship a unit returns, ignoring whatever the adapter supplied. Type normalisation keeps every new field.
- **Merge rule (core, one ingestion run):** when a key seen in an earlier unit appears again:
  - `description`: append each sentence of the new description (split after `.`, `!` or `?` followed by whitespace) that isn't already present, comparing case-insensitively and trimmed. Stop before the total would exceed 1,000 characters; a single first sentence longer than 1,000 is hard-cut at 1,000.
  - `sourceTextUnitIds`: ordered union, first-seen order.
  - Relationship `weight` = `sourceTextUnitIds.size()`.
  - An earlier description is never overwritten or lost.
  - The merged record is what gets persisted and what the entity/relationship callbacks receive.
- Persistence: the Neo4j adapter stores `description`, `sourceTextUnitIds` and (for relationships) `weight` as properties, SET from the record, and MERGEs one `(:Entity)-[:MENTIONED_IN]->(:TextUnit)` per source id, matching both nodes on `corpusId`. Reads use `coalesce(..., '')`, `coalesce(..., [])` and `coalesce(r.weight, 1)`, so pre-13.2 data loads without error. The in-memory adapter keeps the full records.
- OpenAI: the prompt asks per Entity for `name`, `type` and `description` (one or two sentences), and per Relationship for `source`, `sourceType`, `type`, `target`, `targetType` and `description`, in JSON mode against the given type list. The JSON model has an explicit max-output-token limit, `MAX_EXTRACTION_OUTPUT_TOKENS = 4096`. The call uses `chat(ChatRequest)`; finish reason `LENGTH` → `LlmCallFailedException` mentioning the output-token limit. Invalid JSON keeps throwing `LlmCallFailedException`. Core's existing wrapper adds document and passage to the message, and `CorpusController` logs it and marks the corpus `FAILED`.
- The offline stub stays deterministic and network-free. Entity description = the first sentence of the unit that contains the name; Relationship description = the sentence it was extracted from.
- SSE `entity-extracted` and `relationship-extracted` payloads gain `description`; existing keys stay.

**Never:**
- No name normalisation or type-conflict resolution beyond today's `name::type` key (Story 13.3).
- No detail-panel UI, no `text-units/{id}` endpoint and no frontend changes (Story 13.4).
- No retries, no gleaning, no partial "best effort" graph.
- Never derive `sourceTextUnitIds` from LLM output.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Single unit | Unit `u0` returns Ada (desc "A mathematician.") | Persisted Ada: description "A mathematician.", ids `[u0]` | No error expected |
| Repeat across units | Ada in `u0` ("A mathematician.") and `u1` ("A mathematician. She wrote notes.") | description "A mathematician. She wrote notes.", ids `[u0, u1]` | No error expected |
| Relationship repeat | Same (Ada, wrote_about, Engine) in `u0`, `u1`, `u2` | weight 3, ids `[u0,u1,u2]` | No error expected |
| Cap | Accumulated descriptions would exceed 1,000 chars | Stays ≤1,000 chars, earliest sentences kept | No error expected |
| Missing description | LLM omits `description` | Stored and emitted as `""` | No error expected |
| Truncated response | Finish reason `LENGTH` | `LlmCallFailedException` → corpus `FAILED`, log names document + passage | Visible failure, no retry |
| Invalid JSON | Response is not JSON | Same as truncated | Visible failure, no retry |
| Legacy data | Neo4j Entity/RELATIONSHIP without new properties | Reads as `""`, `[]`, weight 1 | No error expected |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/io/graphrag/core/domain/Entity.java:8-29` -- 2-field record, compact ctor defaults (`type` null → `"Unknown"`), `normalizedIdentity()`, `identityOf`. About 85 test and 6 main call sites use the 2-arg form, so keep it as an overload.
- `graphrag-core/src/main/java/io/graphrag/core/domain/Relationship.java:6-14` -- 5-field record (`type` null → `related_to`). About 28 test and 10 main call sites use the 5-arg form.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/ExtractEntitiesAndRelationships.java`:
  - `extract(Corpus)` (39-56) dedupes with `putIfAbsent`; switch it to the merge rule.
  - `run` (75-101): `unit` is known at line 78; persistence at 82-83, callbacks after.
  - `normalizeTypes` (111-137) rebuilds records with the 2- and 5-arg ctors (121, 129-133) and would drop new fields.
  - `relationshipKey` (140-144) is the merge key.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/` -- new `GraphElementMerger` (framework-free; `merge(Entity, Entity)`, `merge(Relationship, Relationship)`, `mergeDescriptions(String, String)`). Story 13.3's resolver will reuse it.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jGraphStoreAdapter.java`:
  - Entity write (130-158) uses `MERGE (e:Entity {corpusId, normalizedIdentity})` and SETs only name/type.
  - Relationship write (161-193) uses generic `:RELATIONSHIP` with `r.type` as a property and SETs only endpoint types.
  - TextUnit write: 190-222.
  - Entity read (267-282) and relationship read (285-310) rebuild with the old ctors.
  - Unscoped overloads throw `UnsupportedOperationException` (99-110); keep that.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/InMemoryGraphStoreAdapter.java:73-126` -- `put` by key; already keeps whatever record it receives.
- `graphrag-adapter-langchain4j/src/main/java/com/graphraglens/adapter/langchain4j/OpenAiLlmPort.java`:
  - Builders: 56-71.
  - `extract(Corpus)` (80-102): `putIfAbsent`, so use `GraphElementMerger`.
  - `extract(TextUnit, …)` (110-122) calls `jsonChatModel.chat(String)`, which hides the finish reason.
  - `extractionPrompt`: 129-158.
  - `parseExtraction` (182-229) rebuilds records at 205 and 222.
  - LangChain4j version is `1.20.0`.
  - Add a package-private constructor `OpenAiLlmPort(ChatModel json, ChatModel text)` so tests can inject a fake `ChatModel` that returns a `ChatResponse` with a chosen `FinishReason`.
- `graphrag-adapter-langchain4j/src/main/java/com/graphraglens/adapter/langchain4j/LangChain4jLlmPort.java:73-145` -- offline per-unit regex/sentence extraction; records are built at 88 and 130-144.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java:498-512` -- `entityEventPayload` / `relationshipEventPayload` build explicit maps, so `description` must be added by hand. The failure log is already at 468.
- Tests to extend:
  - `ExtractEntitiesAndRelationshipsTest`
  - `InMemoryGraphStoreAdapterTest`
  - `Neo4jGraphStoreAdapterTest` (Testcontainers `neo4j:2026.08.1-community`)
  - `OpenAiLlmPortTest`: covers parse and prompt; no network.
  - `LangChain4jLlmPortTest`
  - `CorpusControllerTest:340-360`: an `ArgumentCaptor` on payload maps with `containsKeys(...)`.
- Consumers that only read accessors (community detection, search, drift, demo loaders) need no change. Graph JSON endpoints that serialize `Entity`/`Relationship` records will gain the fields automatically, which is harmless.

## Tasks & Acceptance

**Execution:**
- [x] `graphrag-core/.../domain/Entity.java`, `Relationship.java` -- add the fields, canonical constructors, overloads and defaults described above, and update the javadoc -- AD-25 with v1 compatibility.
- [x] `graphrag-core/.../usecase/GraphElementMerger.java` -- new merge rule (descriptions, id union, weight) -- a single source of truth for merging.
- [x] `graphrag-core/.../usecase/ExtractEntitiesAndRelationships.java`:
  - `normalizeTypes` keeps the new fields.
  - Stamp `[unit.id()]` onto every Entity and Relationship of the unit.
  - Hold a per-run `Map` of merged Entities and Relationships, and persist and call back with the merged records.
  - `extract(Corpus)` uses the same merge.
- [x] `graphrag-adapter-neo4j/.../Neo4jGraphStoreAdapter.java` -- SET the new properties, MERGE `MENTIONED_IN`, and coalesce on read.
- [x] `graphrag-adapter-langchain4j/.../OpenAiLlmPort.java`:
  - Add the max-output-token limit and the `chat(ChatRequest)` call with the `LENGTH` check.
  - Ask for descriptions in the prompt and parse `description`.
  - Add the test constructor.
  - `extract(Corpus)` merges via `GraphElementMerger`.
- [x] `graphrag-adapter-langchain4j/.../LangChain4jLlmPort.java` -- add the deterministic descriptions.
- [x] `graphrag-web/.../CorpusController.java` -- add `description` to both payloads.
- [x] Tests:
  - `GraphElementMergerTest` (matrix rows 2-4).
  - `ExtractEntitiesAndRelationshipsTest`: rows 1-3; ids are stamped from the unit even when the adapter returns other ids; type normalisation keeps the description.
  - `InMemoryGraphStoreAdapterTest`: fields round-trip.
  - `Neo4jGraphStoreAdapterTest`: fields round-trip; one `MENTIONED_IN` per source id, scoped to the corpus; legacy nodes without the properties read as `""`, `[]` and weight 1.
  - `OpenAiLlmPortTest`:
    - The prompt mentions `description`.
    - `parseExtraction` reads descriptions; a missing description becomes `""`.
    - A fake `ChatModel` returning `FinishReason.LENGTH` makes `extract(unit, …)` throw `LlmCallFailedException`.
    - A fake returning `STOP` parses normally.
  - `LangChain4jLlmPortTest`: descriptions are non-blank and deterministic.
  - `CorpusControllerTest`: both payloads contain `description`.

**Acceptance Criteria:**
- Given a corpus ingested through `POST /api/corpora` (offline stub), when ingestion completes, then every captured `entity-extracted` and `relationship-extracted` payload contains a `description` key, and the graph store holds Entities whose `sourceTextUnitIds` are ids of persisted Text Units.
- Given an Entity mentioned in two Text Units of one corpus, when ingestion completes, then the stored Entity has both unit ids, a description containing both units' distinct sentences, and (Neo4j) two `MENTIONED_IN` relationships.
- Given the OpenAI model returns finish reason `LENGTH` for a unit, when ingestion runs, then the corpus is `FAILED` and the logged error names the document and passage number.
- Given the Demo Dataset, the offline demo, or a corpus persisted before this story, when it is loaded or ingested, then it reaches or shows READY, and all previously passing unit and UI tests still pass.

## Spec Change Log

## Review Triage Log

## Design Notes

Merging lives in core rather than in Cypher, so both adapters can simply SET what they are given and Story 13.3's resolver can plug in before the merge. Weight is derived (`sourceTextUnitIds.size()`) instead of incremented, which makes it idempotent if a unit is ever re-persisted. Legacy and overload weight is `1` because an existing edge was seen at least once; Epic 14's Leiden step relies on it being positive.

## Verification

**Commands:**
- `$env:OPENAI_API_KEY=$null; mvn -q -pl graphrag-core,graphrag-adapter-langchain4j -am test` -- expected: BUILD SUCCESS
- `mvn -q -pl graphrag-adapter-neo4j -am test "-Dapi.version=1.44"` (outside sandbox) -- expected: only the 2 known baseline `Neo4jCorpusRegistryTest` failures
- `$env:OPENAI_API_KEY=$null; mvn -q -pl graphrag-web -am test "-Dapi.version=1.44"` -- expected: no failures beyond the known flaky UI set (`CanvasSettingsPopoverUiTest`, `EntityTypeColorToggleUiTest`, `MainScreenDetailPanelUiTest`, `DriftTreeReplayUiTest`, `EntitySearchUiTest`, order-dependent `CorpusSwitcherUiTest`)
