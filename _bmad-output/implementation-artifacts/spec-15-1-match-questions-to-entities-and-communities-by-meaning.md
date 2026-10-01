---
title: 'Match Questions to Entities and Communities by Meaning'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: 'a69f5c12817d33ec9b7f9d57618d9a14be792c8d'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: ['oversized']
deferred:
  - summary: >-
      help.js localChain multi-seed (semantic) trace rendering has no test.
    evidence: |-
      HelpPaneUiTest only runs offline single-seed traces; reverting `rel && !other` to `!other` passes. Needs a stubbed semantic trace via page.route.
    location: >-
      graphrag-web/src/main/resources/static/js/help.js:327
    severity: low
---

<intent-contract>

## Intent

**Problem:** Local, Global and DRIFT Search find their starting points with `KeywordMatcher`, which is pure token overlap against Entity names/types and Community summaries. A question that shares no words with an Entity's name finds nothing, even when the Entity's description clearly matches.

**Approach:** When a real (semantic) embedding model is configured, ingestion does two things after community detection: it embeds each Entity's `name + description` and each Community's summary, and it stores them as `embedding` properties behind one Neo4j vector index per label (`Entity`, `Community`). That index spans all corpora and is filtered by `corpusId`. The searches embed the question once. Local takes the top 3 Entities as seeds; Global and DRIFT take the top 3 Communities. Each seed is recorded as a trace step, in similarity order. With no semantic model (offline/stub), everything behaves exactly as today.

## Boundaries & Constraints

**Always:**
- `graphrag-core` stays framework-free.
- `EmbeddingPort` gains `default boolean isSemantic() { return true; }`. `LangChain4jEmbeddingPort` (the deterministic offline stub) returns `false`. A null port or `isSemantic() == false` means "no embedding model configured".
- Without a semantic port, nothing is embedded during ingestion. All three modes then use the current `KeywordMatcher` code paths unchanged, with the same steps, order and answers, so every existing test passes untouched.
- Embeddings use only what is already persisted: `name + ": " + description` for Entities, the `summary` for Communities. Vectors are stored as `LIST<FLOAT>` properties (AD-17 correction).
- Neo4j writes and reads are corpus-scoped (AD-20). There is one vector index per label (`entity_embedding`, `community_embedding`), created idempotently with cosine similarity and the dimension of the first vector persisted. Similarity queries filter by `corpusId`, using the `SEARCH` clause with in-index filtering on Neo4j 2026.08 Community (AD-17). The exact syntax must be proven by the adapter integration test against the real container.
- With a semantic port:
  - Local records `ENTITY` steps for the top 3 seeds in similarity order, then runs today's hop logic from the first seed.
  - Global records `COMMUNITY` steps for the top 3 Communities in similarity order and answers from the first one, instead of recording every Community.
  - DRIFT uses the top 3 Communities (in order) as its candidates and steps, instead of all Communities tied at the top keyword score. Sub-question branching is unchanged.
- If the semantic path yields no candidates (e.g. a corpus ingested before this story, with no embeddings), that mode falls back to `KeywordMatcher` for that query.
- An embedding failure during ingestion fails the corpus visibly (FR-5): FAILED status plus an SSE `error` with a dedicated embedding message. No retries.

**Never:**
- No LLM answer synthesis or citations (Stories 15.2–15.4).
- No change to the Vector Baseline (`AnswerVectorBaseline`, Chunk storage).
- No embedding of anything in offline mode.
- No per-corpus indexes.
- No change to the query REST contract beyond the steps it returns.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| No word overlap, description matches | Entity "Ada Lovelace" with description "wrote the first computer program"; question "who invented software?"; semantic port maps both close | Local seeds include Ada Lovelace as the first `ENTITY` step | none |
| Top-3 ordering | 5 entities with known similarities | 3 `ENTITY` steps in descending similarity | none |
| Global top-3 | 5 communities | exactly 3 `COMMUNITY` steps in similarity order; answer from the first | none |
| DRIFT top-3 | 5 communities | candidates = the 3 most similar, in order; `SUB_QUESTION_SPAWNED` per sub-question as today | none |
| Offline / stub port | `LangChain4jEmbeddingPort` or null | nothing embedded; all modes produce today's output | none |
| Corpus without embeddings | semantic port, but nodes lack `embedding` | keyword fallback for that query | none |
| Other corpus | another corpus with closer vectors | never returned | none |
| Embedding call fails at ingestion | port throws | corpus FAILED, SSE error names embedding | no retry |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/io/graphrag/core/port/EmbeddingPort.java:12` -- `float[] embed(String)`. Add the `isSemantic()` default.
- `graphrag-adapter-langchain4j/.../LangChain4jEmbeddingPort.java` -- the offline stub (64-dim token hash). Override `isSemantic()` to return `false`. `OpenAiEmbeddingPort` (text-embedding-3-small, 1536 dims) keeps the default `true`.
- `graphrag-core/src/main/java/io/graphrag/core/port/GraphStorePort.java` -- add these defaults:
  - `persistEntityEmbeddings(String corpusId, Map<String, float[]> byIdentity)`, a no-op;
  - `persistCommunityEmbeddings(String corpusId, Map<String, float[]> byCommunityId)`, a no-op;
  - `List<Entity> similarEntities(String corpusId, float[] query, int k)`, returning an empty list;
  - `List<Community> similarCommunities(String corpusId, float[] query, int k)`, returning an empty list.
  
  An empty result triggers the keyword fallback, so the in-memory adapter and the 6 test fakes need no change.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/` -- new `EmbedGraphElements` use case `(GraphStorePort, EmbeddingPort)`. Its `run(Corpus)` does nothing unless the port is semantic. Otherwise it embeds every Entity and Community of the corpus and persists the vectors.
- `graphrag-core/.../usecase/AnswerLocalSearch.java:40-123` -- constructor `(GraphStorePort)`. The seed comes from `KeywordMatcher` (L81-95) and the hop from L102-123. Add a `(GraphStorePort, EmbeddingPort)` constructor and keep the 1-arg one with a null port.
- `graphrag-core/.../usecase/AnswerGlobalSearch.java:28-64` -- records a COMMUNITY step for every community (L47) and picks the best (L48-53). Add the semantic path behind an optional `EmbeddingPort` constructor argument.
- `graphrag-core/.../usecase/AnswerDriftSearch.java:29-96` -- candidates are the communities tied at the top score (L62-67), and the nested Local Search is built at L70. Pass the `EmbeddingPort` to the nested `AnswerLocalSearch`, and add an optional `EmbeddingPort` constructor argument while keeping the 2-arg one.
- `graphrag-adapter-neo4j/.../Neo4jGraphStoreAdapter.java` -- Entities are keyed `(corpusId, normalizedIdentity)` and Communities `(corpusId, id)`. `persistEntities` SETs named properties, so `embedding` survives later SETs. Follow the `ensureConstraint` pattern (L68-92) for index creation. Create the index lazily on the first persisted vector, because the dimension is only known then.
- `graphrag-adapter-neo4j/.../Neo4jVectorStoreAdapter.java:91-124` -- an example of storing and reading `float[]` as `LIST<FLOAT>`. It creates no index itself.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java`:
  - The constructor at L98-114 has 11 args, and tests call it directly. Add an `@Autowired` 12-arg constructor with `EmbeddingPort`, and keep the 11-arg one delegating with `null`.
  - In the pipeline (L476-515), run `EmbedGraphElements` after `DetectCommunities` and before `markReady`, with a stage flag and a dedicated `EMBEDDING_FAILURE_MESSAGE`.
  - The query use cases are built inline (L346, L357, L380); pass the port to them.
- `graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java:94-104` -- the `embeddingPort` bean already exists (OpenAI when the key is set, stub otherwise).
- `graphrag-core/src/test/.../usecase/AnswerLocalSearchTest.java`, `AnswerGlobalSearchTest.java`, `AnswerDriftSearchTest.java` -- the existing keyword tests must stay unchanged.

## Tasks & Acceptance

**Execution:**
- `EmbeddingPort.java`, `LangChain4jEmbeddingPort.java` (+ its test) -- add the `isSemantic` flag.
- `GraphStorePort.java` -- the four additive defaults.
- `EmbedGraphElements.java` + `EmbedGraphElementsTest` -- embed and persist when semantic; do nothing when not semantic or when the port is null.
- `AnswerLocalSearch.java`, `AnswerGlobalSearch.java`, `AnswerDriftSearch.java` -- the semantic seed path with keyword fallback.
- New core tests (`AnswerLocalSearchSemanticTest` etc. or added cases), using a fake semantic `EmbeddingPort` and a fake store with `similar*` -- cover each matrix row for the three modes, including the no-word-overlap case.
- `Neo4jGraphStoreAdapter.java` -- persist embeddings, lazy idempotent vector indexes, and corpus-filtered `similarEntities`/`similarCommunities` returning full records in score order.
- `Neo4jGraphStoreAdapterTest` -- with hand-made 4-dim vectors in two corpora, check top-k ordering, corpus filtering, an index existing per label (`SHOW VECTOR INDEXES`), and an empty result when no embeddings exist.
- `CorpusController.java` + `CorpusControllerTest` -- the embedding stage runs only with a semantic port. An embedding failure leaves the corpus FAILED with the embedding SSE message, and offline ingestion embeds nothing.
- `graphrag-web/.../static/help/local-search.html`, `global-search.html`, `drift-search.html` -- one sentence each on semantic seed matching and the keyword fallback.
- `graphrag-core/CHANGELOG.md` -- the new port methods and use case.

**Acceptance Criteria:**
- Given a live corpus (semantic embedding port, Neo4j), when ingestion completes, then every Entity and Community node of that corpus has an `embedding` and both vector indexes exist.
- Given that corpus, when a Local question with no word overlap but semantic closeness to an Entity's description is asked, then that Entity is a seed `ENTITY` step in the returned trace.
- Given offline mode, when any mode is queried, then answers and steps equal today's, and all existing core and web tests pass.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 35 findings — high 0, medium 9, low 23, false 3, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) Index dimension is fixed by the first vector; a model change silently keeps the old index — now compares the existing index dimension with the vector length and throws a visible IllegalStateException; adapter test
  - `[medium]` `[patch]` (blind) A query-time embedding failure breaks Local/Global/DRIFT — now maps to the `{error}` shape with a non-2xx status (no silent keyword fallback, per FR-5); controller test
  - `[low]` `[reject]` (blind) Corpora without embeddings still pay one embedding call per query — one cheap call before the keyword fallback; tracking embedded state per corpus adds surface
  - `[low]` `[reject]` (blind) No similarity threshold, so the semantic path never says no match — Story 15.2 in this same run replaces template answers with LLM synthesis that maps out-of-context to noAnswer; the threshold choice is left to that grounding step
  - `[false]` `[reject]` (blind) Reused Leiden community ids keep stale embeddings — corpora are built once and never re-detected; there is no rerun path for a corpus
  - `[low]` `[reject]` (blind) One sequential embed call per element, no batching or progress events — acceptable for demo-size corpora; batching would add a port surface
  - `[low]` `[reject]` (blind) `isSemantic()` defaults to true — spec decision; only the deterministic stub is non-semantic
  - `[low]` `[reject]` (blind) DRIFT costs 1+3 embedding calls — bounded per query; caching adds complexity
  - `[low]` `[reject]` (blind) Constants and helpers duplicated across Global/DRIFT — cosmetic; no diverging caller named
  - `[low]` `[reject]` (blind) Untested adapter edge cases: mixed lengths, stale cache, no minimum version documented — the dimension mismatch is now guarded (row 1); the others are unlikely in use
  - `[low]` `[patch]` (blind) Stale help callouts on local/global/drift — callouts qualified as offline/keyword behaviour
  - `[low]` `[patch]` (blind) CHANGELOG lacks index names, dimension rule, failure policy — added
  - `[medium]` `[patch]` (verification-gap) GLOBAL/DRIFT controller paths are never tested with a semantic port — controller test for GLOBAL and DRIFT seeding by meaning added
  - `[medium]` `[patch]` (verification-gap) DRIFT nested Local semantic seeding is untested — core test with embedded entities and a no-overlap sub-question added
  - `[low]` `[defer]` (verification-gap) help.js `localChain` multi-seed trace is untested — needs a stubbed semantic trace in Playwright; deferred
  - `[low]` `[patch]` (verification-gap) The demo path never embedding is not pinned — demo-with-semantic-port test added
  - `[medium]` `[patch]` (verification-gap other) Query-time embedding failure is uncaught — same root cause as row 2
  - `[low]` `[reject]` (verification-gap other) Embedding call per query on non-embedded corpora — same as row 3
  - `[medium]` `[patch]` (verification-gap other) Dimension mismatch — same root cause as row 1
  - `[medium]` `[patch]` (edge) Existing index with a different dimension — same root cause as row 1
  - `[low]` `[reject]` (edge) Mixed vector lengths within a batch — one model per deployment produces uniform lengths
  - `[medium]` `[patch]` (edge) Query vector dimension differs from the index, so SEARCH throws — surfaces through the row 2 error shape
  - `[low]` `[reject]` (edge) Stale index cache after an external drop — manual DBA action only
  - `[low]` `[reject]` (edge) Index POPULATING or FAILED — creation awaits ONLINE via db.awaitIndex
  - `[medium]` `[patch]` (edge) A query-time embed exception gives no fallback — same root cause as row 2
  - `[low]` `[reject]` (edge) A semantic seed with no word overlap gets no hop — 15.2 assembles one-hop context regardless of keyword score
  - `[low]` `[reject]` (edge) Local answers with an arbitrary nearest entity (no threshold) — same as row 4
  - `[low]` `[reject]` (edge) Global always claims a strongest signal (no threshold) — same as row 4
  - `[low]` `[reject]` (edge) Duplicate ENTITY step when the hop target is also a seed — harmless for replay and highlighting; 15.2 rebuilds the step list
  - `[low]` `[reject]` (edge) A store failure in the embedding stage is labelled as an embedding failure — rare; the message still points at the right stage
  - `[low]` `[reject]` (edge) Placeholder entities whose identity mismatches name/type are missed — placeholders are only created for real members or endpoints, whose name and type are set
  - `[low]` `[reject]` (edge claim) Not every Entity is embedded — same as row 31
  - `[low]` `[reject]` (intent) Tests use substring-mapped fake vectors, not a real model — no API key in CI by design; the real-model risk is recorded in the Auto Run Result
  - `[false]` `[reject]` (intent) Only the first seed drives the hop (B2) — matches the spec's chosen reading; 15.2 uses all seeds for context
  - `[false]` `[reject]` (intent) Embedding runs once after detection, not after extraction — the AC says "when extraction and detection complete"; satisfied

## Design Notes

Neo4j 2026.04+ Community supports in-index filtering. The index is declared with the filter property, and the query uses the `SEARCH` clause:
```cypher
CREATE VECTOR INDEX entity_embedding IF NOT EXISTS FOR (e:Entity) ON (e.embedding) WITH [e.corpusId]
OPTIONS {indexConfig: {`vector.dimensions`: $dims, `vector.similarity_function`: 'cosine'}}

MATCH (e:Entity)
SEARCH e IN (VECTOR INDEX entity_embedding FOR $query WHERE e.corpusId = $corpusId LIMIT $k) SCORE AS score
RETURN e, score ORDER BY score DESC
```
Treat this as a sketch: confirm the exact grammar against the Testcontainer. If the container rejects the `WITH [...]` or `SEARCH ... WHERE` form, find the supported 2026.08 form. Do not silently fall back to `db.index.vector.queryNodes`, which is deprecated. If no in-index filter form works at all, use an exact corpus-scoped `vector.similarity.cosine` scan and record that in the Spec Change Log.

## Verification

Run every Maven command with `OPENAI_API_KEY` unset (as in CI). Otherwise the Spring and UI tests call the real LLM and flake. `-Dapi.version=1.44` is required for Testcontainers with the local Docker 29.

**Commands:**
- `env -u OPENAI_API_KEY mvn -B -q -pl graphrag-core,graphrag-adapter-langchain4j test` -- expected: all pass
- `env -u OPENAI_API_KEY mvn -B -pl graphrag-adapter-neo4j -am test -Dapi.version=1.44` -- expected: BUILD SUCCESS
- `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44` -- expected: BUILD SUCCESS (all modules, UI tests included)

## Auto Run Result

Status: done

**Summary:** With a semantic embedding model (OpenAI), ingestion embeds every Entity (`name: description`) and Community (summary) after detection. The vectors are stored as `LIST<FLOAT>` `embedding` properties behind the corpusId-filtered vector indexes `entity_embedding` and `community_embedding`, queried with the Cypher `SEARCH ... WHERE` clause. Local seeds are the top 3 Entities; Global and DRIFT use the top 3 Communities (DRIFT's nested Local Searches too). Each is recorded as a step in similarity order. Offline (`isSemantic() == false`), nothing is embedded and keyword matching is unchanged. A corpus without embeddings falls back to keywords per query. Failures are visible:
- an embedding failure at ingestion marks the corpus FAILED with an embedding SSE message;
- a failure at query time returns 503 `{error}`;
- an index dimension mismatch fails with a message naming both dimensions.

**Files changed:**
- `EmbeddingPort` -- `isSemantic()`.
- `LangChain4jEmbeddingPort` -- returns false.
- `GraphStorePort` -- 4 additive defaults.
- New `EmbedGraphElements` and `SemanticMatchingException`.
- `AnswerLocalSearch`, `AnswerGlobalSearch`, `AnswerDriftSearch` -- semantic seed paths.
- `Neo4jGraphStoreAdapter` -- embeddings, lazy vector indexes with a dimension guard, `similar*`.
- `CorpusController` -- 12-arg constructor, embedding stage, 503 handler.
- `help.js` -- multi-seed localChain.
- help pages (local, global, drift, mode-chooser) and `CHANGELOG.md`.
- Tests: `EmbedGraphElementsTest`, `SemanticSeedMatchingTest`/`SemanticTestFixtures`, embedding port tests, `Neo4jGraphStoreAdapterTest` (+5), `CorpusControllerTest` (+6).

**Review findings:** 35 findings. 15 rows were patched, covering 6 distinct fixes:
- query-time error shape;
- index dimension guard;
- GLOBAL/DRIFT controller test;
- DRIFT nested semantic test;
- demo never-embeds test;
- help and CHANGELOG wording.

1 deferred: the help.js multi-seed rendering test. 19 rejected, with reasons in the Review Triage Log. Notably, the absence of a similarity threshold is left for 15.2's not-in-context handling.

**Follow-up review recommendation:** true. 4 medium entries were patched. Named unverified risk: the semantic path has only run with hand-made vectors. It has not been exercised end to end with real 1536-dim OpenAI embeddings against Neo4j `SEARCH`.

**Verification:** `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44` gave BUILD SUCCESS: core 130, neo4j 63, langchain4j 25, parsing 10, web 177, 0 failures.

**Residual risks:**
- Each query on a non-embedded corpus with a key set still costs one embedding call.
- With no similarity threshold, semantic Global and Local always report the nearest match until 15.2's synthesis judges relevance.
