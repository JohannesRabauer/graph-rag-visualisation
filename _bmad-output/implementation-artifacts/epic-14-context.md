# Epic 14 Context: Real Communities — GDS Leiden & Grounded Summaries

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Replace the connected-components BFS that `DetectCommunities` has always used with real modularity-based clustering (Neo4j GDS Leiden), which the requirements and earlier stories always called for and whose plugin is already installed in `docker-compose.yml`. Connected components produce one giant Community for any well-linked area and a separate Community for every isolated pair, which scatters the canvas on real corpora (e.g. cryptids). The epic then writes each Community's summary and a short title from the content Epic 13 now provides (Entity/Relationship descriptions) instead of member names alone. Communities then read as topics, the legend reads like a table of contents, and Global and DRIFT Search have meaningful summaries to match against.

## Stories

- Story 14.1: Detect Communities with GDS Leiden
- Story 14.2: Write Community Summaries from Their Content

## Requirements & Constraints

- Community detection must be modularity-based (GDS Leiden), not connected components: a densely linked region may split into several Communities, and disconnected parts are never forced into one. Use one flat level only. Hierarchical/multi-level Communities are out of scope.
- Detected Communities stay persisted so Global Search, DRIFT Search, and the formation visualization can use them.
- Each Community's summary is written from its members' descriptions and the Relationships between them, not from names alone.
- Failures are visible, never silent. If GDS is unavailable, ingestion fails with a visible error state; there is no fallback to connected components on the Neo4j path. No automatic retry of LLM calls.
- Results must be reproducible for demos, so Leiden uses a fixed `randomSeed`.
- Offline mode (no API key), the in-memory adapter, and existing tests must keep behaving as before.
- LLM access stays behind the port so the provider can be swapped later.

## Technical Decisions

- **Port boundary:** `DetectCommunities` gets memberships from a new `GraphStorePort.detectCommunities(corpusId)`. The port's **default implementation** is the existing connected-components algorithm, so the in-memory adapter and offline mode need no change. Only `Neo4jGraphStoreAdapter` overrides it with GDS.
- **GDS call:** project only that corpus's Entities and their relationships as an `UNDIRECTED` graph (GDS Leiden needs undirected input, whatever the stored direction), weighted by the Relationship `weight` property (an integer: the number of Text Units the Relationship was extracted from). Run `gds.leiden.stream` with a fixed `randomSeed`. Always drop the projection in a `finally`, even when the call fails.
- **Corpus scoping:** projection names include the `corpusId` so concurrent or other corpora never collide. Every node and MERGE key is scoped by `corpusId`: Community is `(corpusId, id)` and CommunityMembership is `(corpusId, communityId, entityIdentity)`. Community ids are per-run sequential strings (`community-0`, ...), so they are unique only within a corpus.
- **Isolated Entities:** an Entity with no relationships becomes its own single-member Community. Leiden's projection will not group these, so handle them explicitly.
- **Output shape unchanged:** each Community is still its own `(:Community)` node linked to its members via `[:BELONGS_TO]`, never a scalar `communityId` on Entity. Each write commits in its own transaction.
- **Summary timing:** detection and summary generation run automatically and asynchronously right after graph construction, unconditionally, as one run. Global/DRIFT Search only read existing summaries and never generate them on demand. The backend never knows the visualization toggle's state.
- **Summary input:** `LlmPort.summarizeCommunity` receives the members with descriptions plus the Relationships whose *both* endpoints are members, with descriptions. Input is capped, keeping the highest-`weight` Relationships first, so each call stays bounded.
- **Summary output:** a short title (≤6 words) plus a two-to-four-sentence summary. `Community` gains a `title` field, persisted on the `(:Community)` node. The offline stub returns a deterministic title and summary.

## UX & Interaction Patterns

- The canvas legend row and the uppercase monospace hull labels show the Community **title**. When the title is empty (older corpora, offline stub), fall back to the current summary-derived label.
- The Entity/Community detail panel shows the full summary.
- Hull styling is unchanged: pale tints from the five-color categorical palette, visible only when the community-visualization toggle is ON (or when a Replay step forces them visible). The toggle never re-runs or gates detection.
- Leiden typically yields more, smaller Communities than before. The legend and hull rendering must stay readable at higher Community counts, since the palette cycles beyond five.

## Cross-Story Dependencies

- Depends on Epic 13: Entity/Relationship `description`, `sourceTextUnitIds`, and Relationship `weight` must already exist and be persisted. `weight` drives both the Leiden weighting and the summary input cap.
- Story 14.2 depends on 14.1: summaries are generated over the Leiden memberships, within the same detection run.
- Builds on Epic 12's Neo4j persistence (corpus-scoped constraints, real `Neo4jGraphStoreAdapter`). The integration test needs the real Neo4j container with GDS. It should show two dense groups joined by a single edge splitting into two Communities, where connected components would give one.
- Feeds Epic 15: Global and DRIFT Search (semantic matching and grounded answers) consume these summaries and titles.
