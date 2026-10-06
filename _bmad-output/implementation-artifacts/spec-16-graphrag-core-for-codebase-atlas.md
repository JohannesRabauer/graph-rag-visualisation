---
title: 'Make graphrag-core consumable by Codebase Atlas'
type: 'feature'
created: '2026-10-06'
status: 'done'
baseline_revision: '150244a'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: ['oversized']
deferred: ['work package 1 (build and distribution) is handled in a separate session']
---

<intent-contract>

## Intent

**Problem:** `graphrag-core` assumed a text corpus: LLM extraction was the only way in, Entities had no source location, Communities needed GDS (or degraded to connected components), queries always synthesized an answer, and the graph port mixed reads and writes. Codebase Atlas has an exact code graph from a bytecode scan, needs `path:startLine-endLine` on every answer item, serves another LLM (retrieval only), runs plain Neo4j 5.26 without GDS, keeps embeddings in pgvector and uses a small local model.

**Approach:** Eight work packages, one commit each (WP1 — JDK 21 build, publishing, BOM, release workflow — runs in a separate session):
- **WP2** Optional LLM capabilities (`extractsEntities`, `summarizesCommunities`, `derivesSubQuestions`, `LlmPort.none()`) and `ImportKnowledgeGraph`.
- **WP3** `attributes` and `SourceLocator` on Entity, Relationship, TextUnit, Citation, RetrievalStep (attributes on Community); free-form types; Neo4j persists both.
- **WP4** Core `ModularityCommunityDetector` (Louvain + Leiden-style connectivity refinement) as the port default; configurable GDS fallback; `DetectCommunities.Options` (detector, size, parallelism, budget, failure policy, summary reuse by content hash) and `CommunityDetectionResult`.
- **WP5** `RetrieveLocalContext` / `RetrieveGlobalContext` / `RetrieveDriftContext` returning `RetrievalResult` (items + trace with locators); `SeedMatcher` (keyword, semantic, identifier, hybrid RRF); configurable Local expansion. Local Search's answer context runs on the same engine.
- **WP6** `GraphReadPort` / `GraphWritePort` (GraphStorePort extends both); query use cases on the read port; `graphrag-core-testkit` with contracts, fixtures and a reference in-memory store.
- **WP7** `UpdateSources` (remove/replace by source, recompute Communities), delete primitives, Neo4j label/type prefixes and injected sessions.
- **WP8** `LenientJson`, `PromptedLlmPort` (prompts, schemas, corrective retry), per-item `FailurePolicy`, lenient OpenAI parsing.

## Boundaries & Constraints

**Always:**
- Core stays framework-free (JDK only), compiles with `--release 21`; the Enforcer ban also covers the testkit.
- Existing constructors and methods stay; records keep their old constructors (record patterns must add the new components).
- The web app's JSON is unchanged (it maps payloads by hand); retrieval results are plain records (Jackson 2 and 3 round-trip them).
- Failures stay visible: per-item isolation is opt-in (`FailurePolicy.ISOLATE_ITEM`), reported per item and counted.

**Never:**
- No LLM call on the import path except Community summaries.
- No GDS requirement in core; the Neo4j GDS default and its fail-hard behaviour are unchanged unless the fallback flag is set.

**Decisions and deviations:**
- `LlmPort.extract(Corpus)` stays the single abstract method so existing lambdas keep compiling; "optional" is expressed by the `extractsEntities()` flag, `GraphExtraction.empty()` and `LlmPort.none()`.
- Two existing core tests encoded the old connected-components default: the bridged-cliques test now asserts the modularity result (two Communities), the relationship-cap test pins connected components explicitly.
- `DetectCommunities` reports summaries of a port without `summarizesCommunities()` as `DETERMINISTIC`.
- The OpenAI corrective retry is opt-in (its tests and docs promise one call per item); `PromptedLlmPort` retries by default.
- `Neo4jVectorStoreAdapter` (`:Chunk`) is not namespaced.

## Verification

- Core unit tests per behaviour (`*OptionsTest`, `Retrieve*Test`, `SeedMatchersTest`, `UpdateSourcesTest`, `PromptedLlmPortTest`, `PerItemFailureIsolationTest`, …); the existing suites unchanged except the two tests above.
- Testkit: `CodeGraphExampleTest` (the `code-graph` example) and the contracts against the reference store.
- Adapters: the contracts against `InMemoryGraphStoreAdapter`, `InMemoryVectorStoreAdapter`, `Neo4jGraphStoreAdapter` (plain Neo4j, core detector; and with prefixes and an injected session supplier), `Neo4jVectorStoreAdapter`, `LangChain4jEmbeddingPort`; `CodeGraphRetrievalContract` against the in-memory adapter and Neo4j.
- Web: `RetrievalResultJsonTest`; the full reactor `mvn verify` (JDK 25).

</intent-contract>
