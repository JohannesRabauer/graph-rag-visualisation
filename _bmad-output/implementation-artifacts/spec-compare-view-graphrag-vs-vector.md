---
title: 'Compare View: GraphRAG vs Vector Search'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: '95488dab4aa87c907521d688a9318c3101620617'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: ['oversized']
deferred:
  - summary: >-
      Token usage per side is not shown in the comparison key figures.
    evidence: |-
      No code reads langchain4j ChatResponse.tokenUsage(); carrying it through SynthesizedAnswer and all four answer records is a cross-cutting change, agreed out of scope for this fast-tracked story.
    severity: low
  - summary: >-
      Both replays run one after the other via the existing graph and vector replays, not simultaneously in the comparison view.
    evidence: |-
      replay.js, vector-space.js and GraphCanvas are singletons bound to fixed DOM ids; side-by-side replay needs instance factories.
    severity: low
---

<intent-contract>

## Intent

**Problem:** "Compare with Vector Search" is confusing.
- The vector side is not a real answer. Even with an API key, `LlmPort.synthesizeFromChunks` just joins the chunk texts, while GraphRAG answers are LLM-written and cited.
- The result is spread over three places: an extra chat message, a panel under the graph answer, and the Vector Space tab.
- Nothing tells the audience **where one approach is better and why**.

**Approach:**
- Give the vector side the same footing: an LLM-written answer with `[n]` citations over its top-k chunks, using the same synthesis and citation machinery as GraphRAG (Stories 15.2/15.3).
- Add one dedicated **Compare** tab, opened by the Compare button. It shows both answers side by side, the sources of each side (with overlap markers), key figures per side, a short verdict naming the concrete difference and its reason, and buttons to replay either side.
- The old panel under the answer and the extra chat message go away.

## Boundaries & Constraints

**Always:**
- **Equal footing.** `AnswerVectorBaseline` gains an `LlmPort`-synthesizing path. When `llmPort.synthesizesAnswers()`, it builds the top-k chunks (k = 5, unchanged) as citable `ContextItem`s and calls `llmPort.synthesizeAnswer`.
  - Each chunk is a `TEXT_UNIT`-kind item whose `textUnitId` is the chunk id, labelled "Source passage" in the prompt.
  - Citations are resolved with `CitationResolver` into `Citation(chunkId, documentName, excerpt)`.
  - Not-in-context uses the same normalized sentinel and maps to a vector `noAnswer` with a reason.
  - Offline (non-synthesizing port) keeps today's joined-text answer and `citations: []`, with the trace unchanged.
  - The answer prompt wording must fit both sides: "retrieved context", not "knowledge graph".
- **Chunks know their document.** `Chunk` gains `documentName`, which `ConstructVectorIndex` fills from `UploadedDocument.filename()`.
  - It is persisted on `(:Chunk)` and read back with `coalesce(..., '')` for older corpora.
  - The existing record constructor is kept as an overload.
  - The misleading `ordinal` Javadoc is fixed: the ordinal is corpus-wide.
- **Compare endpoint.** `POST /api/corpora/{corpusId}/compare` takes `{question, mode}`, where mode is LOCAL, GLOBAL or DRIFT and defaults to LOCAL. It applies the same validation and offline/building/failed rules as `/query`. It runs **both** sides fresh, timing each with `System.nanoTime`, and returns:
  - `graph`: `{mode, noAnswer, answer|reason, citations, traceId, traceStepCount, stats}`
  - `vector`: `{noAnswer, answer|reason, citations, traceId, traceStepCount, queryProjection, stats}`
  - `stats`: `{contextItems, distinctDocuments, latencyMs}`. For graph, `contextItems` counts ENTITY, RELATIONSHIP, COMMUNITY and TEXT_UNIT steps; for vector, VECTOR_CHUNK steps. `distinctDocuments` counts the documents among the side's retrieved passages: graph TEXT_UNIT documents, vector chunk documents.
  - `overlap`: for each vector chunk citation, `sharedWithGraph: true` when the chunk's whitespace-normalized text is contained in the text of a Text Unit the graph side retrieved (a TEXT_UNIT step), from the same document. It is also listed per graph citation.
  - `verdict`: `{text, source: "llm"|"rule"}`.
- **Verdict.** A new `LlmPort.compareAnswers(question, graphAnswer, vectorAnswer, facts)`, where `facts` is a small core record holding both stats and the overlap counts.
  - Its default is deterministic (`source: "rule"`). It states which side retrieved more distinct documents and context items and how many passages overlap, with no claim about correctness.
  - `OpenAiLlmPort` overrides it with one short JSON-mode call (`source: "llm"`, at most 2 sentences). It names the concrete difference and the reason, grounded in the given facts and answers.
  - A verdict-call failure does not fail the comparison. It falls back to the rule text and is logged.
  - All other LLM failures surface as the existing 502 `{error}` shape.
- **UI.**
  - **The Compare button** POSTs `/compare`. No answer message is appended to the chat. The button becomes a link "Comparison ready — open Compare", and the view opens on the first successful compare.
  - **Tab bar:** gains a third, initially hidden `Compare` tab. Tab switching and arrow/Home/End keys handle 3 tabs.
  - **The Compare view shows:**
    - the question;
    - a two-column grid (GraphRAG {mode} | Vector Search) with each answer, its `[n]` markers rendered as in Story 15.4 (marker buttons plus a Sources list), and an "also used by the other side" badge on overlapping sources;
    - a key-figure row per side;
    - the verdict, with a small "LLM verdict" / "Rule-based summary" label;
    - "Replay GraphRAG" and "Replay Vector" buttons. These start the existing replays, which switch to the graph and Vector Space tabs as today.
  - **Vector citations open their chunk text** (from the citation excerpt plus a new `GET /api/corpora/{id}/chunks/{chunkId}` returning `{id, documentName, ordinal, text}`). Graph citations use the existing text-unit endpoint.
  - The Vector Space tab still shows the scatter and gets the vector answer text, as today.
  - **Removed:** `renderComparePanel` and its CSS, and the extra VECTOR chat message.
  - Text is set via `textContent` only.
- **Help.** `vector-vs-graphrag.html` and `vector-space.html` describe the Compare tab, the cited vector answer and the verdict. Add the new view to `trace-replay.html` or `reading-an-answer.html` where relevant. `HelpRegistryGuardTest` stays green.

**Never:**
- No change to the GraphRAG modes' answers, the query contract for LOCAL/GLOBAL/DRIFT, the vector top-k or chunking size.
- No automatic compare.
- No retries.
- No `innerHTML` with model or corpus text.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Live compare | synthesizing LLM, ready corpus | both answers cited; stats; overlap flags; verdict source llm | none |
| Offline compare | stub LLM | vector = joined text, citations []; graph = templated; verdict source rule | none |
| Vector not in context | LLM NOT_IN_CONTEXT on chunks | vector.noAnswer + reason; graph shown normally | none |
| No chunks yet | vector index empty | vector.noAnswer (existing no-chunks reason) | none |
| Overlap | chunk text inside a graph-retrieved TU of same doc | sharedWithGraph true on that vector citation and the matching graph citation | none |
| Verdict failure | compareAnswers throws | verdict falls back to rule text | logged, not fatal |
| Invalid mode | mode VECTOR or unknown | 400 with existing mode message style | — |
| Offline/building/failed corpus | as /query | same 409s as /query | — |
| Old corpus chunks | no documentName stored | documentName "" → shown as "Unknown document" | none |

</intent-contract>

## Code Map

- `graphrag-core/.../usecase/AnswerVectorBaseline.java` -- TOP_K=5 at L10, `answer()` at L30-68, `synthesizeFromChunks` at L59, SYNTHESIS step at L60. Add the synthesizing path, built like `LocalContextAssembler.addTextUnit`/`number` (L144-164).
- `graphrag-core/.../usecase/VectorBaselineAnswer.java:25` -- `(noChunks, answer, reason, steps, queryProjection)`. Add `citations` and a not-in-context factory, keeping the old constructor and factories.
- `graphrag-core/.../domain/Chunk.java:13` -- add `documentName`. Id format `corpusId::chunk-N` (ConstructVectorIndex L65). Chunking is at L57-92.
- `graphrag-core/.../usecase/CitationResolver.java` -- package-private, `resolve(text, context, citationsByUnit)`.
- `graphrag-core/.../port/LlmPort.java` -- `synthesizeFromChunks` L112-121, `synthesizesAnswers` L128, `synthesizeAnswer` L141. Add the `compareAnswers` default.
- `graphrag-adapter-langchain4j/.../OpenAiLlmPort.java` -- `synthesizeAnswer` L333-350, `answerPrompt` L357-394 (generalize the "knowledge graph" wording; Story 15.2/15.3 prompt tests must be updated accordingly), `label()` L415-423. Add the `compareAnswers` override.
- `graphrag-adapter-neo4j/.../Neo4jVectorStoreAdapter.java` (~L92-126) and `InMemoryVectorStoreAdapter.java` -- persist and read `documentName`.
- `graphrag-web/.../CorpusController.java`:
  - `query` L335-379, `vectorBaselineResponse` L471-491, `citationPayload` L411-416, the local/global/drift response builders L388-469 (reuse them for the graph side), `captureTrace` L499-503.
  - The text-unit endpoint is at L256; add the chunk endpoint next to it.
  - `answerVectorBaseline` bean field L108; ParserConfig L126-130.
- `graphrag-web/src/main/resources/static/js/upload.js`:
  - compare CTA handler L146-194;
  - `renderComparePanel` L196-296, to remove;
  - `revealVectorSpaceTab` L393-413;
  - `switchCanvasTab` L416-466 and its keys L468-498;
  - `appendAnswer` L1721 with its compare CTA L1779-1801;
  - `buildCitationSources` L1648 and `loadPassage` ~L455, both reusable.
- `graphrag-web/src/main/resources/templates/index.html` -- tab bar L69-71, `#vector-space-panel` L164-177. Add `#tab-compare` and `#compare-panel`.
- `graphrag-web/src/main/resources/static/css/instrument.css` -- the old compare panel CSS at L3032-3105 is to be replaced.
- `graphrag-web/src/main/resources/static/js/replay.js` -- the `.replay-cta` handler L75-91 and `openReplay(traceId, projection, corpusId)`. The compare view's replay buttons can reuse this, e.g. as `.replay-cta` buttons carrying `data-trace-id`, `data-query-projection` and corpus id, or via a small exported `Replay.open`.
- Tests:
  - `VectorBaselineTriggerUiTest` -- rewrite it for the new flow; the old assertions on the extra VECTOR chat message and panel go.
  - `CorpusControllerVectorBaselineTest`, `AnswerVectorBaselineTest` -- keep; the offline behaviour is unchanged.
  - New `CompareViewUiTest`, a new controller compare test, and new core synthesis/compare tests.

## Tasks & Acceptance

**Execution:**
- Core: `Chunk`, `ConstructVectorIndex`, `VectorBaselineAnswer`, `AnswerVectorBaseline` (synthesizing path), `LlmPort.compareAnswers`, the comparison facts record, and a new `CompareAnswers` use case. The use case runs both sides with timing, computes stats, overlap and verdict, and is framework-free; the controller only maps it. Add tests for each.
- Adapters: Neo4j and in-memory vector stores (documentName); `OpenAiLlmPort` (prompt generalized, `compareAnswers`); tests.
- Web: `CorpusController` `/compare` and `/chunks/{chunkId}`, with tests.
- UI: Compare tab and view in `index.html`, `upload.js` and CSS; old panel removed; replay buttons.
- UI tests: rewritten `VectorBaselineTriggerUiTest` and new `CompareViewUiTest`. The latter covers: compare opens the Compare tab, both columns, sources with an overlap badge (inject via the `window.fetch` rewrite for the cited case), key figures, the verdict label, both replay buttons working, no extra chat message, and 3-tab keyboard navigation.
- Help pages and `graphrag-core/CHANGELOG.md`.

**Acceptance Criteria:**
- Given a ready corpus, when the presenter clicks Compare on a graph answer, then the Compare tab opens. It shows both answers side by side with their sources, key figures per side and a verdict, and the chat gets no extra message.
- Given a synthesizing LLM, when comparing, then the vector answer is LLM-written with `[n]` citations that index into its `citations`, each a chunk among its `VECTOR_CHUNK` steps.
- Given offline mode, when comparing, then both sides render (templated graph answer, joined vector answer), the verdict is rule-based and labelled so, and all existing tests not tied to the removed panel pass.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 30 findings — high 0, medium 13, low 15, false 2, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) Chunks spanning two Text Units never count as overlap — chunks are ≤500 chars and Text Units overlap by 600 chars, so every chunk lies fully inside at least one Text Unit of its document
  - `[medium]` `[patch]` (blind) Legacy corpora always show 0 overlap and 1 document — an empty documentName is treated as unknown: text-only match, excluded from the distinct count; test
  - `[medium]` `[patch]` (blind) firstSentences cuts at abbreviations — abbreviation and initial guard; tests
  - `[low]` `[reject]` (blind) The verdict prompt can be steered by its inputs — the presenter's own corpus; the verdict is labelled as LLM
  - `[low]` `[reject]` (blind) Citation counts are not in ComparisonStats — shown in the UI from the response; the verdict uses passages and documents
  - `[low]` `[patch]` (blind) Graph contextItems counts steps, not distinct items — counts distinct (kind, identifier); test
  - `[low]` `[reject]` (blind) The whole vector store is re-read per compare and per chunk click — demo scale; a single-chunk port lookup would add port surface
  - `[medium]` `[patch]` (blind) Concurrent comparisons overwrite each other — latest-request token plus corpus check
  - `[low]` `[patch]` (blind) /query VECTOR not-in-context drops queryProjection — mode and queryProjection added to that shape; tests
  - `[low]` `[patch]` (blind) A cached comparison can't be refreshed; the re-run isn't indicated — fresh-run note and Run again button
  - `[low]` `[reject]` (blind) Test gaps for spanning chunks and verdict truncation — spanning is impossible (row 1); truncation is low value
  - `[low]` `[reject]` (blind) ComparisonFacts.graphMode is a String — only fed by CompareAnswers with a validated Mode
  - `[low]` `[reject]` (blind) Latency is measured sequentially, verdict not timed — graph runs first; documented as indicative in the help
  - `[medium]` `[patch]` (edge) firstSentences cuts at abbreviations — same root cause as row 3
  - `[medium]` `[patch]` (edge) Legacy chunks give zero overlap — same root cause as row 2
  - `[medium]` `[patch]` (edge) Legacy chunks count as one document — same root cause as row 2
  - `[low]` `[reject]` (edge) Two documents with the same filename — rare; filenames are distinct per upload in practice
  - `[false]` `[reject]` (edge) The vector store changes between the answer and the re-read — a READY corpus's chunks are immutable
  - `[medium]` `[patch]` (edge) A corpus switch while a compare is in flight — response dropped when the corpus changed
  - `[medium]` `[patch]` (edge) Two compares in flight; the earlier one resolves last — same root cause as row 8
  - `[low]` `[patch]` (edge) A cached comparison's replay traces expire after a restart — Run again re-runs the comparison
  - `[medium]` `[patch]` (edge claim) Later compares don't open the Compare tab — the tab now always opens on success or when the link is clicked
  - `[medium]` `[patch]` (verification-gap) GLOBAL/DRIFT comparisons are untested — core DRIFT and GLOBAL cases plus a UI Global case
  - `[medium]` `[patch]` (verification-gap) /query VECTOR with a synthesizing port is untested — controller tests added
  - `[low]` `[patch]` (verification-gap) The compare-view replay passage caption is untested — UI test with a TEXT_UNIT trace
  - `[medium]` `[patch]` (verification-gap other) Legacy overlap — same root cause as row 2
  - `[medium]` `[patch]` (intent) Offline compare shows no sources for either side — Retrieved passages lists per side, with an offline note for the graph side
  - `[low]` `[reject]` (intent) The rule verdict gives counts, not a content reason — agreed: a deterministic offline version built from the key figures
  - `[low]` `[reject]` (intent) Replays leave the compare view — agreed sequential scoping; deferred
  - `[low]` `[reject]` (intent) The live path is tested only via stubs and a fetch rewrite — no key in CI; recorded as residual risk

## Verification

Run Maven with `OPENAI_API_KEY` unset and `-Dapi.version=1.44`.

**Commands:**
- `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44` -- expected: BUILD SUCCESS (all modules, UI tests included)

## Auto Run Result

Status: done

**Summary:** "Compare with Vector Search" now opens a dedicated **Compare** tab and adds nothing to the chat. A new `POST /api/corpora/{id}/compare`, backed by the framework-free `CompareAnswers` use case, answers both sides fresh and times each.
- **Equal footing:** with a synthesizing LLM, the vector side is LLM-written with `[n]` chunk citations, using the same prompt and `CitationResolver`.
- **Per side:** citations, retrieved passages with overlap badges, and key figures (distinct context items, distinct documents, citations, latency).
- **Verdict:** an LLM verdict that names the concrete difference and the reason, at most 2 sentences and abbreviation-safe, with a rule-based fallback.
- **Replay and re-run:** "Replay GraphRAG" and "Replay Vector" buttons, a fresh-run note, and "Run again".
- **Data:** chunks now carry `documentName`. A new `/chunks/{chunkId}` endpoint serves chunk text.
- **Removed:** the old compare panel and the extra VECTOR chat message.

**Review findings:** 30 findings. 19 rows patched, covering 11 distinct fixes:
1. overlap and document counts for legacy unnamed chunks;
2. distinct context items;
3. abbreviation-safe verdict trimming;
4. retrieved-passages lists, including offline;
5. the Compare tab opening on every compare;
6. a latest-wins guard plus a corpus check;
7. fresh-run note and Run again;
8. `/query` VECTOR not-in-context keeping `queryProjection`;
9. GLOBAL and DRIFT compare tests;
10. `/query` VECTOR synthesizing tests;
11. the compare-replay passage caption test.

2 deferred (token usage; simultaneous replays). 11 rejected; reasons are in the Review Triage Log.

**Follow-up review recommendation:** true. Several medium entries were patched. Named unverified risk: the live path (real LLM vector synthesis and verdict) has only been exercised through stubs and an in-page response rewrite.

**Verification:** `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44`: BUILD SUCCESS. Test counts: core 200, neo4j 66, langchain4j 37, parsing 10, web 213, 0 failures.

**Residual risks:**
- Corpora indexed before this change have unnamed chunks: overlap falls back to text-only matching, and they show as "Unknown document".
- The GraphRAG column is a fresh run and can differ from the chat answer.
- Latency is measured sequentially, graph first.
