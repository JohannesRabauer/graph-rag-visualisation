---
title: 'Replace the Vector Space tab with a similarity-ranking replay in Compare'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: 'bcd4e920c8cf53028785d75f8b2e9f3c22d72ac7'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      Backend 2-D projection (TwoDProjection, ProjectionModel, queryProjection, GET /vector-space) stays although no UI uses it any more.
    evidence: |-
      Removing it touches core, both vector stores and the API contract; kept to limit this story to the presentation change. Candidate for a later clean-up.
    severity: low
---

<intent-contract>

## Intent

**Problem:** The Vector Space tab is a 2-D PCA scatter of the chunk embeddings. It explains vector search poorly:
- the 2-D distances misrepresent the real cosine neighbours;
- the dots are anonymous (no document, excerpt or hover);
- offline the hashed embeddings make the cloud near-random;
- its answer text now duplicates the Compare tab.

The user chose option A: remove the tab and explain the vector side where it is compared.

**Approach:**
- Vector search's whole mechanism is "rank every chunk by cosine similarity, take the top 5", so show the **ranking**.
- The vector side of the Compare tab gains a **Similarity ranking**: a bar list of the highest-scoring chunks with rank, score bar, document and excerpt, the top-5 marked, and a visible cut-off line after rank 5 ("— top 5 used · cut-off —").
- "Replay Vector" replays the vector trace **inside the Compare tab** by filling that ranking step by step, instead of switching to a scatter.
- The Vector Space tab, its panel, `vector-space.js` and its help topic are removed.

## Boundaries & Constraints

**Always:**
- **Core.** `AnswerVectorBaseline` already scores every chunk.
  - Expose the ranking: `VectorBaselineAnswer` gains `ranking`, a list of a new domain record `RankedChunk(int rank, String chunkId, String documentName, String excerpt, double score, boolean used)` holding the top `RANKING_SIZE = 12` chunks by score (descending, same ordering and tie-break as the top-k selection), and `scoredChunkCount`, the total number of chunks scored.
  - `used` is true exactly for the top-k chunks (ranks 1..5) that feed the answer.
  - Excerpts use the same 200-character rule as citations.
  - Old constructors and factories are kept; `noChunksYet` has an empty ranking and 0.
  - The trace (`VECTOR_QUERY_EMBEDDED`, `VECTOR_CHUNK`×k, `SYNTHESIS`) is unchanged.
- **API.** The `/compare` response's `vector` side gains `ranking: [{rank, chunkId, documentName, excerpt, score, used}]` and `scoredChunkCount`. `/query` VECTOR gains the same fields additively. Existing fields stay.
- **Compare tab, vector column.**
  - A "Similarity ranking" section shows the ranking rows. Each row has the rank, a horizontal score bar whose width is relative to the top score, the score to 3 decimals, the document name ("Unknown document" when empty), and the excerpt.
  - The top-k rows are visually marked, followed by a cut-off line labelled "top 5 used for the answer".
  - Below the list: "{scoredChunkCount} chunks scored · showing the top {ranking.length}".
  - Clicking a row opens the chunk text, as the retrieved-passage rows do.
  - All text goes through `textContent`.
- **Vector replay inside Compare.** "Replay Vector" opens the existing replay scrubber (`replay.js`) while staying on the Compare tab, with the scrubber visible there. Steps map onto the ranking:
  - `VECTOR_QUERY_EMBEDDED`: the caption "Embedded the question; scoring {n} chunks" (the existing "embedded query" wording must remain in the caption).
  - `VECTOR_CHUNK` i: highlights ranking row i as current, earlier hits as previous, with the caption showing rank and score.
  - `SYNTHESIS`: highlights the answer in the vector column.

  Closing the replay clears the highlights. The graph replay ("Replay GraphRAG") behaves as today and switches to the Knowledge Graph tab.
- **Removed.**
  - The `#tab-vector-space` button, `#vector-space-panel`, `vector-space.js` (with its script tag), `revealVectorSpaceTab` and the Vector Space branch of `switchCanvasTab`. The tab system now knows Knowledge Graph and Compare, and the arrow, Home and End keys work with 2 tabs.
  - `static/help/vector-space.html` and its registry entry or `data-help` button.
  - The related CSS.
  
  `HelpRegistryGuardTest` stays green.
- **Help.**
  - `vector-vs-graphrag.html` explains the similarity ranking, the cut-off and the in-tab vector replay.
  - It says why there is no 2-D map: distances in a 2-D projection don't reflect real similarity.
  - Remove references to the Vector Space tab from all help topics and the README.

**Never:**
- No change to the vector retrieval itself (top-k = 5, cosine, chunking), the GraphRAG replay, or the answers.
- No new libraries.
- No backend removal of the projection (deferred).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Normal corpus | 40 chunks | ranking has 12 rows, rows 1–5 `used`, cut-off after row 5, "40 chunks scored · showing the top 12" | none |
| Small corpus | 3 chunks | ranking has 3 rows, all `used`, no cut-off line | none |
| No chunks | empty index | vector noAnswer as today, no ranking section | none |
| Ties | equal scores | same order as the top-k selection (deterministic) | none |
| Replay | click Replay Vector | stays on Compare; rows light up 1..5 with captions; close clears | none |
| Legacy chunk | documentName "" | "Unknown document" | none |

</intent-contract>

## Code Map

- `graphrag-core/.../usecase/AnswerVectorBaseline.java` -- TOP_K=5 and scoring in `answer()`; the private `ScoredChunk` record holds the scores.
- `graphrag-core/.../usecase/VectorBaselineAnswer.java` -- the record and its factories (already extended with citations and not-in-context).
- `graphrag-core/.../domain/` -- new `RankedChunk`.
- `graphrag-core/.../usecase/CompareAnswers.java` -- `VectorSide` and the vector-side mapping.
- `graphrag-web/.../CorpusController.java` -- `vectorBaselineResponse` and the `/compare` vector payload builder.
- `graphrag-web/src/main/resources/static/js/upload.js`:
  - tab refs L40-42, `revealVectorSpaceTab` ~L625, `switchCanvasTab` ~L647-745 (3 tabs; reduce to 2);
  - reset paths ~L1571 and ~L2492;
  - the compare view renderer (the vector column, retrieved passages, `compareReplayButton`).
- `graphrag-web/src/main/resources/static/js/replay.js`:
  - `isVectorTrace` L53/166;
  - `open()` ~L206, which switches the tab: for vector traces it must switch to `compare`;
  - VectorSpace calls L227 and L340-345, to be replaced by a call into a small compare-ranking highlighter, e.g. `window.CompareRanking.highlightStep(steps, index)` and `.clear()` exported from upload.js.
- `graphrag-web/src/main/resources/static/js/vector-space.js` -- delete.
- `graphrag-web/src/main/resources/templates/index.html` -- the tab at L71, the panel at L165-177, and the script tag.
- `graphrag-web/src/main/resources/static/css/instrument.css` -- the `.vector-space-*` rules (remove) and the compare view styles (extend).
- Help: `static/help/vector-space.html` (delete), `vector-vs-graphrag.html`; grep the help pages for "Vector Space".
- Tests that reference the Vector Space tab, which must be updated: `CompareViewUiTest`, `DemoScriptScreenshotsUiTest`, `EntitySearchUiTest`, `HelpPaneUiTest`, `LoadNewCorpusUiTest`, `VectorBaselineTriggerUiTest`. Also update `AnswerVectorBaselineTest`, `CompareAnswersTest`, `CorpusControllerCompareTest` and `CorpusControllerVectorBaselineTest` for the ranking.

## Tasks & Acceptance

**Execution:**
- Core: `RankedChunk`; the `VectorBaselineAnswer` ranking; `AnswerVectorBaseline` populating it. Add tests for each matrix row except UI.
- Web: ranking in `/compare` and `/query` VECTOR, with controller tests.
- UI: the ranking section, the in-tab vector replay, removal of the Vector Space tab, panel, script, CSS and help, and 2-tab keyboard navigation.
- UI tests:
  - update the tests listed above;
  - extend `CompareViewUiTest` to check the ranking rows, cut-off and footer;
  - the vector replay must stay on Compare and highlight rows 1..k in order, with the "embedded query" caption, and close must clear;
  - assert that `#tab-vector-space` no longer exists.
- Help, README and `graphrag-core/CHANGELOG.md`, including the record-pattern note for `VectorBaselineAnswer`.

**Acceptance Criteria:**
- Given a comparison, when the Compare tab shows the vector column, then a similarity ranking lists the top chunks with rank, score bar, document and excerpt, marks the 5 used ones, and shows a cut-off line and the number of scored chunks.
- Given the Compare tab, when "Replay Vector" is clicked, then the replay runs on the Compare tab and lights up the ranking rows hit by hit.
- Given the app, when any corpus is loaded or compared, then no Vector Space tab exists, and all tests pass.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 28 findings — high 0, medium 5, low 21, false 2, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) The caption reads "scoring every chunks" when the count is unknown — now "every chunk"
  - `[low]` `[patch]` (blind) The caption repeats itself ("embedded query: Embedded the question") — reworded to "embedded query — scoring n chunks"
  - `[low]` `[patch]` (blind) The hit score is regex-parsed from the label (locale/notation) — taken from the ranking data by chunkId
  - `[low]` `[reject]` (blind) A vector trace replayed from outside Compare — no VECTOR chat answers exist any more; Compare is the only entry point
  - `[medium]` `[patch]` (blind) An open vector replay goes stale when the comparison is re-rendered — replay closed before the ranking rebuilds
  - `[low]` `[patch]` (blind) trace-replay help says "scores are not recorded" — narrowed to graph traces
  - `[low]` `[defer]` (blind) Dead backend projection path — already deferred in the spec frontmatter
  - `[low]` `[patch]` (blind) A UI test depends on the orphaned /vector-space endpoint — uses vector.scoredChunkCount
  - `[low]` `[patch]` (blind) Used and current state shown by styling only — visually hidden text plus aria-current
  - `[low]` `[reject]` (blind) ranking() depends on an in-place sort side effect — private, single caller; covered by ordering tests
  - `[false]` `[reject]` (blind) RankedChunk accepts NaN scores — cosine is NaN-guarded (existing test: zero vectors give score=0.000)
  - `[low]` `[reject]` (blind) Weak tie-break test — the ordering equivalence with the top-k is covered by the used==VECTOR_CHUNK test
  - `[low]` `[patch]` (blind) Dead CSS transition and undefined --replay-h — cleaned up
  - `[low]` `[patch]` (blind) Footer "showing the top 3" on a small corpus — "showing all n"
  - `[low]` `[patch]` (blind) Docs may still mention the Vector Space tab — grep and update
  - `[medium]` `[patch]` (edge) Stale replay over re-rendered rows — same root cause as row 5
  - `[low]` `[patch]` (edge) "every chunks" — same root cause as row 1
  - `[low]` `[patch]` (edge) A pending fetch applies the previous openSurface — reset at openReplay start
  - `[low]` `[patch]` (edge) Cut-off placed by usedCount, not the last used row — placed after the last used row
  - `[low]` `[defer]` (edge deletion) Orphaned endpoint and queryProjection — same as row 7
  - `[false]` `[reject]` (edge claim) Cut-off label text differs from the spec — the spec phrasing was descriptive ("top 5 used for the answer"); the code matches the Always section
  - `[medium]` `[patch]` (verification-gap) No test hides the graph scrubber when switching to Compare — UI test added
  - `[medium]` `[patch]` (verification-gap) No test for a failed vector replay staying on Compare — 404 UI test added
  - `[low]` `[patch]` (verification-gap) The chunk count in the caption is not asserted — assertion tightened
  - `[medium]` `[patch]` (intent) The ranking doesn't "fill in one after another" during replay — replay state: bars start empty and fill per hit; tests added
  - `[low]` `[defer]` (intent) "Entirely" could include the backend projection — same as row 7
  - `[low]` `[reject]` (intent) Cut-off on the real demo is only conditionally asserted — the demo has 3 chunks; the 12-row case is covered with injected data and core tests
  - `[low]` `[reject]` (intent) Offline scores still measure word overlap — stated in help; inherent to the offline embeddings

## Verification

**Commands:**
- `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44` -- expected: BUILD SUCCESS

## Auto Run Result

Status: done

**Summary:** The Vector Space tab is gone: its 2-D PCA scatter, `vector-space.js`, the help topic and the CSS were removed. The vector side is now explained in the Compare tab with a **Similarity ranking**, the top 12 chunks by cosine similarity. Each row shows rank, score bar, score, document and excerpt. The 5 chunks used for the answer are marked, and a cut-off line follows the last used row. A footer reads "{n} chunks scored · showing the top 12 / all n".

"Replay Vector" now replays on the Compare tab. The first step ("embedded query — scoring n chunks") shows an empty ranking. Each hit fills its row's bar in rank order, with its score taken from the ranking data. The cut-off appears once the last used row is filled. Stepping back unfills rows, and closing restores the static view.

Core: `RankedChunk`, plus `VectorBaselineAnswer.ranking` / `scoredChunkCount`. The API adds `ranking` and `scoredChunkCount` on `/compare` and on `/query` VECTOR.

**Review findings:** 28 findings. 21 rows patched, covering 14 distinct fixes. The main ones:
- progressive fill-in during replay (from the intent audit);
- closing a stale replay before the ranking re-renders;
- caption wording and grammar;
- scores taken from the data;
- cut-off by the last used row;
- footer wording;
- ARIA;
- help consistency;
- UI tests for the graph scrubber on the Compare tab, a failed vector replay, and the caption count.

3 rows deferred (the backend projection, as already in the spec). 6 rejected; reasons are in the Review Triage Log.

**Follow-up review recommendation:** false. Only presentation code changed. All patched medium items are pinned by tests except one: hiding the cut-off during replay can't be exercised on the 3-chunk demo, and that is a cosmetic CSS rule.

**Verification:** `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44`: BUILD SUCCESS. Test counts: core 208, neo4j 66, langchain4j 37, parsing 10, web 216, 0 failures.

**Residual risks:**
- The backend still computes and serves the 2-D projection; it is unused by the UI (deferred).
- The ranking has not been inspected visually in a normal browser with a large corpus.
