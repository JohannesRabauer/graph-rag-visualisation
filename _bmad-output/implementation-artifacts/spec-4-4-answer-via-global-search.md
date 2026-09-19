---
title: 'Answer via Global Search'
type: 'feature'
created: '2026-09-19'
status: 'in-progress'
route: 'oneshot'
review_loop_iteration: 0
context: []
baseline_commit: '2db6fcf6282f0f997f83e49e86b955b0db5e6b43'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `POST /api/corpora/{corpusId}/query` already accepts `mode: "GLOBAL"`, but `CorpusController.buildAnswer()` runs the exact same naive best-sentence-match heuristic over raw document text for both LOCAL and GLOBAL modes — it never reads the Community summaries `DetectCommunities`/Story 4.2 already generate and persist, so Global Search isn't actually distinct from Local Search, and there is no way to signal "no Communities exist yet" (detection/summary generation still running) — a `GLOBAL` query today just returns a generic sentence-match answer or the same not-quite-right fallback text as Local Search, contradicting AD-13's contract.

**Approach:** Add a `graphrag-core` use case (`AnswerGlobalSearch`, matching the architecture spine's stated Local/Global use-case pair) that reads `graphStorePort.communities()` — never generating a summary on demand (AD-6) — and either aggregates the summaries into a plain-language answer (scored against the question the same way Local Search already scores sentences, so the demo stays deterministic and provider-agnostic) or reports that no Communities exist yet. Wire `CorpusController`'s `GLOBAL` branch to it: on a match, respond with the existing `{"answerId","traceId","answer"}` shape (AD-13); when no Communities exist, respond with the distinct `{"answerId","traceId","noAnswer":true,"reason"}` shape instead of the generic error/fallback text. `upload.js`'s chat handler already falls back gracefully when `answer` is absent; extend it minimally to prefer `reason` in that case so the "no Communities yet" message is the actual reason, not a generic placeholder.

## Boundaries & Constraints

**Always:** `AnswerGlobalSearch` only reads already-persisted `Community` summaries via `GraphStorePort.communities()` — it never triggers `DetectCommunities`/summary generation itself (AD-6). The success response shape is byte-identical in structure to Local Search's (`answerId`/`traceId`/`answer`/`mode`) — Story 3.3's chat rendering needs no changes. The no-Communities-yet response uses the distinct `noAnswer: true` + `reason` shape (AD-13) — never the generic `{"error": ...}` shape, since this is an expected, normal outcome, not a failure. `LOCAL` mode's existing behavior is untouched.

**Never:** Do not change `DetectCommunities`, community detection/summary generation, or when/how they run. Do not add per-Corpus data isolation to `GraphStorePort` (same accepted, pre-existing global-store limitation as Story 4.3). Do not implement Retrieval Trace capture/replay (Epic 5) — `traceId` stays a bare generated UUID, as it already is for Local Search. Do not touch the SSE progress stream or the graph canvas.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Global Search, Communities exist and one matches the question | `mode: "GLOBAL"`, corpus has 1+ persisted Communities | `200` with `{"answerId","traceId","answer","mode":"GLOBAL"}`, answer text drawn from a Community summary | N/A |
| Global Search, Communities exist but none score any match | `mode: "GLOBAL"`, corpus has Communities, question shares no keywords with any summary | `200` with `{"answerId","traceId","answer","mode":"GLOBAL"}`, answer explains no clear community match was found (still a normal answer, not `noAnswer`) | N/A |
| Global Search, no Communities exist yet | `mode: "GLOBAL"`, `graphStorePort.communities()` is empty (detection/summary generation not yet complete) | `200` with `{"answerId","traceId","noAnswer":true,"reason"}`, plain-language reason; chat renders the reason text, not a generic placeholder | N/A |
| Local Search, any state | `mode: "LOCAL"` | Unchanged existing behavior | N/A |

</frozen-after-approval>

## Implementation Notes
