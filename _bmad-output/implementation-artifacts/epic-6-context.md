# Epic 6 Context: Graph Exploration

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Independent of any question, users can navigate to a dedicated Explore page and freely pan, zoom, and click around the entire Knowledge Graph, with Communities always visible and a detail panel showing any Entity's connections, details, and Tags. This turns the graph from something that only lights up in response to a query into something a viewer can wander through and inspect on its own terms — the "structural curiosity" moment (UJ-3) that exists independently of the chat/query flow (Epics 3–5).

## Stories

- Story 6.1: Explore the Full Knowledge Graph
- Story 6.2: Inspect an Entity's Details

## Requirements & Constraints

- The Explore page is reached via a persistent link/tab in the main screen's app bar (exact pixel placement is a layout decision, not a UX one).
- The page renders the full Knowledge Graph, pannable and zoomable.
- Community hulls are **always visible** on this page — unlike the main screen, there is no visualization toggle here; hiding Community structure would work against this page's entire purpose.
- Clicking any Entity node reveals its connections (Relationships), details, and Tags. This is read-only for v1: editing an Entity, its Relationships, or its Tags is explicitly out of scope.
- If no Corpus has been ingested yet, the page must show a clear, plain-language empty state pointing back to the main screen's ingestion entry point — never a blank or broken canvas.
- Reads on this page must never wait for, lock against, or be blocked by in-flight ingestion — they simply reflect whatever graph state is currently committed in Neo4j, which may be partial.
- Success is judged qualitatively: the creator can confidently and correctly demonstrate/explain this exploration flow live.

## Technical Decisions

- Hexagonal boundaries apply: a dedicated `ExploreGraph` use case lives in `graphrag-core` (zero framework dependency) and is driven by `graphrag-web`; it reads via `GraphStorePort`, implemented by `graphrag-adapter-neo4j`.
- Neo4j access is exclusively the plain Neo4j Java Driver with hand-written Cypher — Spring Data Neo4j is never used, here or anywhere.
- The graph schema this page reads was written by earlier epics and must not be reinterpreted: Communities are first-class `(:Community {id, summary})` nodes related to member Entities via `[:BELONGS_TO]` — never a scalar `communityId` property on Entity. This is precisely what makes Explore's Community-hull rendering queryable directly.
- Entities were deduplicated at write time via Cypher `MERGE` keyed on normalized identity (lowercased name + type), so the graph this page renders has no duplicate nodes for the same real-world entity to worry about.
- No new REST/SSE contract is introduced beyond what `ExploreGraph` needs to serve the page/canvas data; this page is a read path only, with no mutation endpoints.
- Domain nouns in code must match the glossary exactly: Corpus, Entity, Relationship, Community, Tag. A Tag is a small, user-facing label on an Entity.
- Frontend stays Java-native: the Explore page is a server-rendered Thymeleaf template; the interactive canvas is plain, unbundled JavaScript using Cytoscape.js — no Node/npm/bundler tooling anywhere.

## UX & Interaction Patterns

- The Explore page canvas reuses the main screen's graph-canvas, node/edge, and community-hull visual tokens wholesale (faint grid background, default/active node and edge states) — no new visual language is invented for this page. There is no chat panel on this page, so the canvas takes the full frame width.
- Community hulls: soft, pale ellipses behind member nodes, each with an uppercase monospace label — rendered with the same best-effort, non-hard-gated 5-color categorical palette used elsewhere (colorblind-safety is a soft goal, not a validated/tested requirement).
- Node detail panel: slides in from the right on node click. Eyebrow heading names the Entity; body lists its Relationships and details; Tags render as small filled monospace chips in a wrapping row. It closes when clicking elsewhere on the canvas or clicking the same node again.
- Click-a-node is one of the app's core interaction primitives: on the Explore page it opens the detail panel (as opposed to the main canvas, where clicking highlights a node during Replay).
- Empty state (no Corpus ingested): plain-language message pointing back to the main screen's ingestion entry point, consistent with the "no-brainer to use" mandate.
- Overall visual register: light-mode-only, minimal, "instrument" tone — chrome recedes, the graph itself is the point.

## Cross-Story Dependencies

- Both stories in this epic depend on Epic 2 (Corpus ingestion / Knowledge Graph construction) having produced Entities and Relationships in Neo4j, and on Epic 4 (Community detection) having written `(:Community)` nodes and `[:BELONGS_TO]` relationships — Community hulls have nothing to render without it.
- Story 6.2 (Entity detail panel) depends on Story 6.1 (the Explore page and its canvas) existing first, since the panel is triggered by clicking a node on that canvas.
- This epic is otherwise independent of the chat/query flow (Epics 3 and 5): Explore works purely off already-persisted graph state and needs no query, answer, or Retrieval Trace to function.
