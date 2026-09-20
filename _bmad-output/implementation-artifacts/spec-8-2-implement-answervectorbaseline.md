---
title: 'Implement AnswerVectorBaseline'
type: 'feature'
created: '2026-09-20'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** There is no way to answer a question using plain vector similarity — the only answer paths are LOCAL, GLOBAL, and DRIFT, all of which traverse the knowledge graph. Epic 8's comparison feature requires a deliberately simple vector-RAG baseline: embed the query, retrieve the top-k most similar chunks from the vector index built by Story 8-1, and synthesize a plain-language answer from those chunks alone.

**Approach:** Add `AnswerVectorBaseline` use case in `graphrag-core` — it takes `EmbeddingPort` and `VectorStorePort` (already exist from 8-1) plus `LlmPort`, embeds the query, computes cosine similarity against all persisted `EmbeddedChunk`s for the corpus, picks the top-k, synthesizes an answer via a new `LlmPort.synthesizeFromChunks(...)` default method, and returns a new `VectorBaselineAnswer` with an ordered `RetrievalStep` trace. Wire it into `CorpusController` as a new `VECTOR` mode on the existing `/api/corpora/{corpusId}/query` endpoint, following the exact same `answerId`/`traceId`/`captureTrace` pattern as LOCAL/GLOBAL/DRIFT.

## Boundaries & Constraints

**Always:**
- `AnswerVectorBaseline` lives in `graphrag-core/.../usecase/`, takes `EmbeddingPort`, `VectorStorePort`, and `LlmPort` constructor-injected, and exposes `VectorBaselineAnswer answer(String question, String corpusId)`.
- The retrieval path is strictly: embed query via `EmbeddingPort.embed(question)` → cosine similarity against every `EmbeddedChunk` from `VectorStorePort.chunks(corpusId)` → sort descending by score → take top-k (k=5 constant, `private static final int TOP_K = 5`) → synthesize via `LlmPort.synthesizeFromChunks(question, List<Chunk>)`.
- `RetrievalStep` trace for a matched answer: one `VECTOR_QUERY_EMBEDDED` step (identifier = `"query"`, label = the question), then one `VECTOR_CHUNK` step per retrieved chunk (identifier = `chunk.id()`, label = score formatted as `"score=0.XXX"`), then one `SYNTHESIS` step (identifier = `""`, label = the synthesized answer text). Steps are in that exact order: query-embed first, then chunks in descending similarity order, then synthesis last.
- `RetrievalStep.Kind` gains two new values: `VECTOR_QUERY_EMBEDDED` and `VECTOR_CHUNK`. Appended after the existing `SYNTHESIS` entry — no existing values are reordered or renamed.
- `LlmPort.synthesizeFromChunks(String question, List<Chunk> chunks)` is a new `default` method. Default implementation: if `chunks` is null/empty return `"No relevant chunks were found for this question."`. Otherwise concatenate chunk texts (separator `"\n---\n"`) and return `"Based on the retrieved text passages: " + concatenated`. All existing `LlmPort` implementors (including `LangChain4jLlmPort` and `OpenAiLlmPort`) inherit this default — no changes to those classes.
- `VectorBaselineAnswer` is a new record in `graphrag-core/.../usecase/` (same package as `LocalSearchAnswer`, `GlobalSearchAnswer`, `DriftSearchAnswer`): `record VectorBaselineAnswer(boolean noChunks, String answer, String reason, List<RetrievalStep> steps)` with the same static factory pattern: `matched(String answer, List<RetrievalStep> steps)` and `noChunksYet()` (when `VectorStorePort.chunks(corpusId)` returns empty — vector index not yet built for this corpus).
- Cosine similarity is computed inline in `AnswerVectorBaseline` as a private static helper — no new dependency. Dot product of query embedding and chunk embedding, divided by the product of their L2 norms. If either norm is zero, similarity is `0.0` (safe degenerate, not NaN).
- `CorpusController` adds `VECTOR` to the mode validation set, injects `AnswerVectorBaseline` as a new constructor field (after `constructVectorIndex`), and adds a `vectorBaselineResponse(String question, String corpusId)` private method that mirrors `driftSearchResponse` — calls `AnswerVectorBaseline.answer(question, corpusId)`, captures the trace, returns `noAnswer`/`reason` or `answerId`/`traceId`/`traceStepCount`/`answer`/`"mode": "VECTOR"`.
- `ParserConfig` adds a new `@Bean AnswerVectorBaseline answerVectorBaseline(EmbeddingPort, VectorStorePort, LlmPort)` — all three dependencies already exist as beans from Story 8-1 and existing wiring.
- All existing `CorpusControllerTest`, `CorpusControllerGlobalSearchTest`, `CorpusControllerDriftSearchTest` constructor call sites updated to add the new `AnswerVectorBaseline` parameter (pass a `new AnswerVectorBaseline(stubEmbeddingPort, stubVectorStore, null)` or `null` — only test files that call `query()` with `mode=VECTOR` need a real stub; the rest pass `null` since they never exercise that path).

**Never:**
- Do not use graph traversal, community summaries, `GraphStorePort`, or any GraphRAG-specific logic inside `AnswerVectorBaseline` — the whole point is a deliberately plain vector-only path.
- Do not add a new HTTP endpoint — `VECTOR` is a new mode value on the existing `/api/corpora/{corpusId}/query` POST endpoint.
- Do not recompute or re-project embeddings during answer time — `EmbeddedChunk.embedding()` (already persisted by Story 8-1) is what gets compared; no call to `ConstructVectorIndex` at query time.
- Do not change the order or names of existing `RetrievalStep.Kind` enum values — append only.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| No chunks in store for corpus | `VectorStorePort.chunks(corpusId)` returns empty | `VectorBaselineAnswer.noChunksYet()` — `noChunks=true`, `reason` explains vector index not ready | No exception |
| Fewer than k chunks available | Store has 3 chunks, `TOP_K=5` | Returns all 3, ranked by similarity — no error, no padding | No exception |
| Query matches chunks well | Question shares tokens with chunk texts | Top-k chunks returned in descending similarity order; `SYNTHESIS` step contains synthesized text | N/A |
| Query embedding is all zeros | `EmbeddingPort` returns zero vector | Similarity with all chunks is `0.0`; all chunks score equally; top-k taken in iteration order; no NaN/Infinity | No exception |
| Chunk embedding is all zeros | One chunk has zero embedding | Similarity with that chunk is `0.0`; still ranked (at bottom); no NaN/Infinity | No exception |
| `LlmPort` is null | Constructed with `null` llmPort | Safe default used (same null-guard pattern as `AnswerDriftSearch`); synthesis falls back to default method behavior | No exception |

</frozen-after-approval>

## Code Map

- `graphrag-core/src/main/java/com/graphraglens/core/domain/RetrievalStep.java` — add `VECTOR_QUERY_EMBEDDED` and `VECTOR_CHUNK` to `Kind` enum after `SYNTHESIS`
- `graphrag-core/src/main/java/com/graphraglens/core/port/LlmPort.java` — add `default String synthesizeFromChunks(String question, List<Chunk> chunks)` method; `Chunk` import from `com.graphraglens.core.domain`
- `graphrag-core/src/main/java/com/graphraglens/core/usecase/VectorBaselineAnswer.java` (new) — mirrors `GlobalSearchAnswer`/`DriftSearchAnswer` shape: `record VectorBaselineAnswer(boolean noChunks, String answer, String reason, List<RetrievalStep> steps)` with `matched(...)` and `noChunksYet()` factories
- `graphrag-core/src/main/java/com/graphraglens/core/usecase/AnswerVectorBaseline.java` (new) — constructor: `(EmbeddingPort, VectorStorePort, LlmPort)`, null-guard llmPort via `DEFAULT_LLM_PORT` exactly like `AnswerDriftSearch`; `answer(String question, String corpusId)` method; private `cosineSimilarity(float[] a, float[] b)` helper
- `graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java` — add `@Bean AnswerVectorBaseline answerVectorBaseline(EmbeddingPort, VectorStorePort, LlmPort)` following existing bean patterns
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` — add `AnswerVectorBaseline answerVectorBaseline` field + constructor param; extend mode validation to include `"VECTOR"`; add `vectorBaselineResponse(String question, String corpusId)` private method; dispatch from `query()` when `mode == "VECTOR"`
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` — update constructor call sites to add `AnswerVectorBaseline` param
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerGlobalSearchTest.java` — update constructor call sites
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerDriftSearchTest.java` — update constructor call sites
- `graphrag-core/src/test/java/com/graphraglens/core/usecase/AnswerVectorBaselineTest.java` (new) — hand-written stubs (no mocking framework), mirrors `AnswerDriftSearchTest` style, covers the I/O matrix rows above

## Tasks & Acceptance

**Execution:**
- [ ] `graphrag-core/.../domain/RetrievalStep.java` — add `VECTOR_QUERY_EMBEDDED`, `VECTOR_CHUNK` to `Kind` enum — new step kinds needed by the vector trace
- [ ] `graphrag-core/.../port/LlmPort.java` — add `synthesizeFromChunks` default method — synthesis seam without breaking existing implementors
- [ ] `graphrag-core/.../usecase/VectorBaselineAnswer.java` (new) — answer result record with `noChunks`/`matched`/`noChunksYet` — same shape as `GlobalSearchAnswer`/`DriftSearchAnswer`
- [ ] `graphrag-core/.../usecase/AnswerVectorBaseline.java` (new) — core use case: embed → cosine rank → top-k → synthesize → return traced answer
- [ ] `graphrag-web/.../config/ParserConfig.java` — new `answerVectorBaseline` bean
- [ ] `graphrag-web/.../CorpusController.java` — inject `AnswerVectorBaseline`, add `VECTOR` mode, add `vectorBaselineResponse` dispatch
- [ ] `CorpusControllerTest.java`, `CorpusControllerGlobalSearchTest.java`, `CorpusControllerDriftSearchTest.java` — update constructor call sites
- [ ] `graphrag-core/.../usecase/AnswerVectorBaselineTest.java` (new) — unit tests covering the I/O matrix

**Acceptance Criteria:**
- Given a corpus with a built vector index, when `POST /api/corpora/{corpusId}/query` is called with `mode: "VECTOR"`, then the response contains `answerId`, `traceId`, `traceStepCount`, `answer`, and `"mode": "VECTOR"` — identical shape to LOCAL/GLOBAL/DRIFT responses.
- Given `VectorStorePort.chunks(corpusId)` returns empty, when `VECTOR` mode is queried, then the response contains `noAnswer: true` and a `reason` explaining the vector index is not ready — never a 4xx or 5xx error.
- Given a corpus with chunks, when `AnswerVectorBaseline.answer(question, corpusId)` runs, then the returned `steps` list starts with exactly one `VECTOR_QUERY_EMBEDDED` step, followed by up to k `VECTOR_CHUNK` steps in descending similarity order, followed by exactly one `SYNTHESIS` step.
- Given `mode` is `"VECTOR"`, when the existing LOCAL/GLOBAL/DRIFT tests run after the constructor-signature change, then all previously passing assertions still pass unchanged.
- Given any chunk or query embedding is a zero vector, when cosine similarity is computed, then the result is `0.0` — no `NaN`, no `Infinity`, no exception.

## Implementation Notes

- Added `VECTOR_QUERY_EMBEDDED` and `VECTOR_CHUNK` to `RetrievalStep.Kind` after `SYNTHESIS` — no existing values changed.
- `LlmPort.synthesizeFromChunks` added as a `default` method; default body concatenates chunks with `\n---\n` separator and ignores the `question` (real implementations override to call the model). All existing `LlmPort` implementors inherit the default unchanged.
- `AnswerVectorBaseline.cosineSimilarity` uses `Math.min(a.length, b.length)` for mismatched vectors — safe given single-adapter wiring in this system; guard returns `0.0` when either norm is zero or non-finite.
- `CorpusController.vectorBaselineResponse` mirrors `driftSearchResponse` exactly; `noChunks` maps to `noAnswer: true` in the HTTP response.
- **Review patch:** Added `CorpusControllerVectorBaselineTest` covering VECTOR happy path (with chunks), `noAnswer` shape (empty store), and invalid mode validation — all 3 pass.

## Spec Change Log

## Review Triage Log

### 2026-09-20 — Blind Hunter review
- `[false]` cosineSimilarity truncates mismatched-dimension vectors — all embeddings produced by the single `EmbeddingPort` bean, so query and stored chunks are always same dimension; not a reachable state.
- `[false]` `VectorBaselineAnswer.reason()` null on happy path — exact same contract as `DriftSearchAnswer`/`GlobalSearchAnswer` (pre-existing established pattern); mirrors the codebase convention.
- `[medium]` `[patch]` No web-layer VECTOR test — real gap; added `CorpusControllerVectorBaselineTest` covering happy path, noChunks/noAnswer shape, and invalid mode validation.
- `[false]` TOP_K non-configurable magic number — intentional per spec boundary ("k=5 constant, `private static final int TOP_K = 5`").
- `[low]` `[reject]` `synthesizeFromChunks` default ignores `question` — Javadoc already says real LLM implementations override; cosmetic gap only.
- `[low]` `[reject]` `CorpusController` class-level Javadoc stale — pre-existing, not introduced by this story.
- `[low]` `[reject]` `captureTrace` Javadoc omits VECTOR — minor comment inaccuracy, no runtime impact.
- `[low]` `[reject]` `AnswerVectorBaseline.answer` does not null-check `corpusId` — `corpusId` always comes from `corpus.id()` which is non-null by construction; not a reachable null.
- `[low]` `[reject]` `emptyStore()` stub relies on interface default — the default exists (`return List.of()`); no runtime risk.
- `[false]` `noChunksYet()` reason string embeds deployment assumptions — exact same pattern as `noCommunitiesYet()` in `DriftSearchAnswer`/`GlobalSearchAnswer`.

## Verification

**Commands:**
- `mvn -pl graphrag-core -am test -Dtest=AnswerVectorBaselineTest,AnswerLocalSearchTest,AnswerDriftSearchTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: all pass
- `mvn -pl graphrag-web -am test -Dtest=CorpusControllerTest,CorpusControllerGlobalSearchTest,CorpusControllerDriftSearchTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: all pass, including any new VECTOR-mode test
