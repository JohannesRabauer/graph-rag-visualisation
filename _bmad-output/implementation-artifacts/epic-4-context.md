# Epic 4 Context: Community Detection, Visualization & Global Search

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Building on an ingested Corpus, the Knowledge Graph is automatically clustered into Communities — watchable by default on a first run, toggleable to compare with/without — and once Community summaries exist, users can also ask corpus-wide thematic questions via Global Search. This is what turns the graph from a pile of individually-extracted facts into something with visible thematic structure, and unlocks the second of the two retrieval modes the app exists to demonstrate side by side.

## Stories

- Story 4.1: Detect Communities in the Knowledge Graph
- Story 4.2: Generate and Persist Community Summaries
- Story 4.3: Toggle Community Formation Visualization
- Story 4.4: Answer via Global Search

## Requirements & Constraints

- Community detection (Leiden-style clustering) runs over the constructed Knowledge Graph; detected Communities must be persisted so Global Search and the visualization can both use them.
- Users can toggle whether community-formation is *visualized* on the main screen. The toggle defaults ON for a fresh Corpus's first run (so the signature clustering moment plays automatically) and is freely switchable afterward. Toggling never re-runs or affects detection itself — detection always proceeds in the background regardless of toggle state.
- Users can explicitly select Global Search via a UI toggle; the system then answers using Community-summary aggregation rather than entity-neighborhood traversal.
- If no Communities exist yet when Global Search is invoked (detection/summary generation not yet complete), the system must return a visible "no answer found" state — never an empty or misleading response, and never the generic error shape.
- Generation failures (the LLM call used for summary generation, or for answering) must surface as a clear, visible error state — no automatic retry, no cached/canned fallback (this is a deliberately accepted risk, not an oversight).
- The LLM integration must not hardcode provider-specific assumptions that would block swapping providers later — summary generation goes through the same abstraction as all other LLM access.
- Success is judged partly on this epic being genuinely engaging to watch on stream (the clustering animation and the with/without toggle demo) — do not "smooth over" real failures to make the demo look better.

## Technical Decisions

- Relationships fed into the GDS Leiden call are always projected as `UNDIRECTED`, regardless of how they're stored/directed elsewhere in the graph — GDS Leiden requires undirected input.
- `DetectCommunities` runs automatically and asynchronously immediately after Knowledge Graph construction completes, unconditionally — never gated by any UI toggle. As part of that same run, it also generates and persists each Community's summary. `AnswerGlobalSearch` only ever *reads* existing summaries; it never generates one on demand, so answer latency and summary existence never depend on query order.
- The community-visualization toggle is read only by the frontend — the backend has no knowledge of its state at all and emits the same progress events regardless of whether the toggle is on or off.
- Each detected Community is written as its own first-class `(:Community {id, summary})` node, related to member Entities via `[:BELONGS_TO]` relationships — never a scalar `communityId` property on Entity.
- Community/detection progress is pushed over the same single multiplexed SSE stream per Corpus (`GET /api/corpora/{corpusId}/progress`) used for ingestion, as named events with a `{"type", "data"}` envelope.
- Query contract: `POST /api/corpora/{corpusId}/query` with `{question, mode}`. A successful Global Search answer uses the exact same `{"answerId", "traceId", "answer"}` shape as Local Search. A "no Communities yet" outcome uses the distinct `{"answerId", "traceId", "noAnswer": true, "reason"}` shape. An actual LLM-call failure uses the `{"error": "<message>"}` shape, also emitted as an `error` SSE event.
- Query reads (including Global Search) never lock against or wait for in-flight ingestion or detection — they read whatever graph/Community state is currently committed, which may be partial.
- Module boundaries apply as elsewhere: Neo4j access (including the GDS Leiden call) goes only through `graphrag-adapter-neo4j` via the plain driver + hand-written Cypher/GDS procedure calls, never Spring Data Neo4j; summary generation goes only through `LlmPort`, implemented solely by `graphrag-adapter-langchain4j`; `graphrag-core` stays framework-free.

## UX & Interaction Patterns

- Community-visualization toggle sits near/above the graph canvas on the main screen. Defaults ON for a fresh Corpus's first run; freely toggled afterward with no confirmation step. It reuses the Local-Search accent tint (not a third color) since it's orthogonal to the Local/Global mode toggle.
- When ON, Communities visibly fold into soft pale hull ellipses (categorical 5-color palette, colorblind-distinguishability is a best-effort goal, not a hard requirement) with monospace labels, animated as detection progress events arrive; when OFF, the same detection keeps running underneath with no animation shown.
- The graph canvas's legend row lists visible Community names with color swatches whenever hulls are shown.
- Global Search reuses the existing Local/Global mode toggle and inline plain-language hint (updates immediately on switch) and the existing chat message-rendering from Epic 3 — no UI changes needed there for a successful answer.
- "No Communities yet" and generation-failure states follow the same visible, plain-language patterns already established for Local Search's no-answer and Epic 2's extraction-failure error banner — name what happened, never fail silently.
- The community-visualization toggle must be reachable and operable via keyboard alone (accessibility floor).

## Cross-Story Dependencies

- Story 4.1 (detection) must complete before Story 4.2 (summaries) can generate anything — summary generation reads the Communities 4.1 wrote.
- Story 4.2 (persisted summaries) is a hard prerequisite for Story 4.4 (Global Search), which only ever reads existing summaries and never generates one on demand.
- Story 4.3 (visualization toggle) is a frontend-only concern layered on top of the same SSE progress events Story 4.1 emits; it has no backend dependency and doesn't affect detection's execution.
- Story 4.4 depends on Epic 3's chat infrastructure (Story 3.1's mode toggle and request contract, Story 3.3's answer-rendering) and must not require changes to that rendering path.
- The epic as a whole is triggered by Epic 2's Knowledge Graph construction (Story 2.4) completing — detection starts automatically once construction finishes, per Corpus.
- Story 5.1 (Epic 5, Retrieval Trace capture) records Communities touched by Global Search, so this epic's Community nodes are a dependency for that later trace capture, though no code changes are required here to support it.
