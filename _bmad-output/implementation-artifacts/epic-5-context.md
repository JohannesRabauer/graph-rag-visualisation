# Epic 5 Context: Retrieval Trace Replay

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

After a query answer arrives, the creator can scrub back and forth through exactly how it was produced — which Entities, Relationships, and Communities were touched, and in what order — turning "GraphRAG found an answer" into "here's precisely how." This is the epic's teaching payoff: the audience sees the actual retrieval path, not just the final text, and the creator can point at the highlighted graph elements as live evidence for why that specific answer came out.

## Stories

- Story 5.1: Capture the Retrieval Trace
- Story 5.2: Replay the Retrieval Trace

## Requirements & Constraints

- The system must capture, during query execution, a structured and strictly **ordered** record of every Entity, Relationship, and Community touched — order matters because Replay's step controls depend on a fixed sequence, not just a set of touched elements.
- After generation completes, the user can play back a captured trace with controls to step forward and backward one step at a time, in addition to continuous autoplay.
- Replay is explicitly **post-hoc only** — there is no live/streaming visualization of retrieval as it happens; this is an accepted v1 scope boundary, not a gap to fill.
- Trace capture applies uniformly to both Local Search and Global Search query paths.
- Primary transport actions (play/pause, step-forward, step-back) must be operable via keyboard alone, without requiring precise mouse interaction (accessibility floor — the creator may narrate hands-off during a stream).
- Steps are discrete, not continuous time — there is no meaningful "between steps" state, including when the scrubber head is dragged.

## Technical Decisions

- A Retrieval Trace is held **in memory only** — never written to Neo4j — keyed by a UUID `traceId` generated at the moment the answer is produced and returned alongside it in the query response. There is no "single current trace" slot; every query gets its own independently addressable trace, so concurrent/sequential queries never clobber each other.
- The trace is fetched via `GET /api/traces/{traceId}`, a plain REST read — not SSE and not WebSocket. Replay is post-hoc, so no real-time transport is involved.
- Restarting the app loses any in-flight/captured traces; this is accepted (no persistence/fallback safety-net for traces).
- The exact in-memory store implementation (e.g. a `ConcurrentHashMap`-backed bean vs. a small cache library) is left to implementation discretion — either satisfies the transience/addressing requirement.
- `RetrievalTrace` is a first-class domain model type in `graphrag-core`, alongside `Corpus`, `Entity`, `Relationship`, `Community`, `Tag` — use this exact noun in code, never a synonym.
- Query responses already carry `traceId` alongside `answerId`/`answer` (or the `noAnswer` variant) on both the Local Search and Global Search success paths — trace capture must populate the same `traceId` returned from those endpoints, whichever mode ran.
- A trace is still captured (and its Replay CTA still shown) even for a "no answer found" result — seeing where the search looked and came up empty is itself part of the trace's value, not a case to special-case away.

## UX & Interaction Patterns

- Every chat answer with a trace shows a **Replay CTA**: a dashed-border row reading "Replay this answer's Retrieval Trace — N steps."
- Clicking Replay opens the **scrubber**, docked below the graph canvas: transport buttons (step-back / play-pause / step-forward), a discrete per-step tick track (done/now/future ticks visually distinct), a step counter, and a plain-language caption of the current step.
- Play/pause autoplays through remaining steps; step-forward/step-back move exactly one step per press; dragging the scrubber head jumps to the *nearest* discrete step (never a "between steps" position).
- Each step highlights the relevant node/edge on the canvas using the defined visual states: **active** (current step, warm accent color/halo), **previous-step** (accent-blue ring, distinct from active), default edges vs. **traversed** (solid, accent-colored) vs. **upcoming** (dashed, default-colored).
- A floating monospace **step badge** overlays the canvas during Replay (e.g. "Step 3 / 5 — traversed outwitted"), naming the step count and the traversed relationship.
- Monospace type is reserved strictly for this kind of data (step counters, captions) — never used for prose/buttons elsewhere in the app.
- Canvas eyebrow title switches to "Knowledge Graph — Replaying Trace" while a Replay is active.
- Clicking a node during/after a Replay highlights it in context on the main canvas (distinct from the Explore page's click-to-open-detail-panel behavior, which is Epic 6's concern).

## Cross-Story Dependencies

- Story 5.1 (trace capture) is invoked from within the Local Search (Epic 3, Story 3.2) and Global Search (Epic 4, Story 4.4) use cases — it has no independent trigger of its own; both must call into trace capture as they touch graph elements.
- Story 5.2 (Replay UI) depends on the Replay CTA already rendered in the chat message thread by Story 3.3, and on a `traceId` already having been produced by Story 5.1.
- Within this epic, Story 5.1 must land before Story 5.2 can be meaningfully demoed end-to-end, since there is nothing to replay without a captured trace.
