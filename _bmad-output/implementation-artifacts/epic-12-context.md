# Epic 12 Context: Durable Neo4j Persistence & Corpus History

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Today, restarting the app loses everything: `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter` are empty compatibility-alias classes that silently delegate to in-memory maps, and the corpus registry itself (`CorpusStore`) is a plain `ConcurrentHashMap` — despite `docker-compose.yml` already running a real Neo4j instance for exactly this purpose. This epic wires genuine Neo4j-backed persistence for the knowledge graph, the vector index, and the corpus registry, then adds a history list/switcher so every previously-ingested corpus (graph, vectors, and bookkeeping) survives an app restart and can be reselected without re-ingesting or re-paying for LLM/embedding calls. It supersedes Story 9.3's "warn on restart-loss" resolution for corpus/graph/vector state (the warning stays accurate only for `RetrievalTrace`, which remains out of scope) and complements, rather than duplicates, Story 10.4's "load a new corpus while one is active" flow — this epic adds reselecting a *previous* corpus, not replacing the active one.

## Stories

- Story 12.1: Wire a Real Neo4j Adapter for the Knowledge Graph
- Story 12.2: Wire a Real Neo4j Adapter for the Vector Index
- Story 12.3: Add Neo4j Connection Configuration with a Fail-Fast Startup Check
- Story 12.4: Replace `CorpusStore` with a Durable Neo4j-Backed Corpus Registry
- Story 12.5: Reconcile Interrupted Corpora to `FAILED` on Startup
- Story 12.6: Add Explicit Corpus Activation and History List Endpoints
- Story 12.7: Build the Corpus History Switcher and Auto-Restore on Load

## Requirements & Constraints

- After ingesting a corpus and restarting the app container (Neo4j untouched), that corpus's graph, vector-space view, and vector-baseline query mode must work identically to before restart — no re-ingestion, no re-embedding, no re-run LLM calls.
- Previously-ingested corpora must still appear (correct name/status) after restart without being re-uploaded; a corpus history endpoint must list every retained corpus ordered by most-recently-activated.
- On startup, the frontend must auto-restore whichever corpus was most recently activated, and clicking a different corpus in the history list must re-render the graph canvas, vector-space view, and query panel against it in the same page load (no full reload, no re-ingestion).
- An unreachable Neo4j must abort startup with a clear, loud error — never a silent degrade back to in-memory behavior.
- A corpus still `BUILDING` when the process died must surface as `FAILED` after restart, never stuck `BUILDING` forever; the existing 409-on-`BUILDING`/`FAILED` query gate (AD-16) must keep working unchanged.
- Non-goals (do not build): retaining original uploaded document bytes or any re-ingest-from-scratch path; corpus deletion or retention/pruning; embedded/in-process Neo4j; any change to single-user/no-auth posture (NFR3); persisting `RetrievalTrace`/query-replay history; a data-migration tool for pre-existing in-memory corpora; a read cache in front of Neo4j for the registry; durable history for demo/offline corpora (they remain session-only, in-memory, never reappearing after restart).
- Success signal: ingest two corpora, restart the full docker-compose stack, reopen the app already pointed at the corpus that was active before restart, with the other corpus one click away in the history list — zero re-ingestion, zero re-run LLM/embedding calls.

## Technical Decisions

- `graphrag-core` stays framework-free: no Neo4j driver import anywhere in it; all Bolt/Cypher lives in `graphrag-adapter-neo4j` behind `GraphStorePort`/`VectorStorePort`. Deployment stays exactly two containers (`app`, `neo4j`) — the corpus registry is Neo4j nodes, not a third store.
- All Neo4j access goes through the plain `org.neo4j.driver:neo4j-java-driver` with hand-written Cypher — never Spring Data Neo4j. A single `Driver` bean is configured from `NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD` env vars (defaults matching `docker-compose.yml`), added to the `app` service like `OPENAI_API_KEY` already is. `driver.verifyConnectivity()` runs at `ApplicationReadyEvent`; failure aborts startup with a logged error, never a silent in-memory fallback. `ParserConfig`'s `@Bean` methods switch to constructing the real Neo4j adapters; the in-memory adapters remain only for the adapter module's own unit tests.
- Every node/constraint is scoped by an explicit `corpusId` property, and every uniqueness constraint/MERGE key includes it — never identity-only: `Entity` on `(corpusId, normalizedIdentity)`, `Community` on `(corpusId, id)` (per-run community ids like `"community-0"` are not globally unique across corpora), `Relationship` on `(corpusId, source, type, target)`, `CommunityMembership` on `(corpusId, communityId, entityIdentity)`, `Chunk` on `(corpusId, chunk.id)`, `CorpusMeta` keyed on `corpusId` alone. Constraints are declared idempotently at adapter startup. Entities/Communities are still written via `MERGE` never blind `CREATE`; Communities remain first-class `(:Community {id, summary})` nodes with `[:BELONGS_TO]` relationships.
- `GraphStorePort`'s corpus-scoped methods must be directly `@Override`-n with real Cypher: the interface's unscoped single-argument overloads are `default` methods that silently fall back to unscoped writes unless overridden, so the Neo4j adapters must instead make those overloads throw `UnsupportedOperationException` (every real call site always has a `corpusId` on hand).
- Vector storage: exactly one vector index spans every corpus's `Chunk` nodes (never one per corpus, since corpora are retained indefinitely), filtered by `corpusId` at query time via in-index filtering, using the current Cypher `SEARCH` clause against a `LIST<FLOAT>` embedding property (Community Edition; never the deprecated `db.index.vector.queryNodes`/`queryRelationships` procedures or Enterprise-only native `VECTOR` typing). The fitted `ProjectionModel` is one `(:ProjectionModel {corpusId, mean, pc1, pc2})` node per corpus, MERGE-keyed on `corpusId` alone.
- Corpus registry: `CorpusStore` is deleted outright, no cache layer in front of Neo4j. `Neo4jCorpusRegistry` is a plain Spring-managed class in `graphrag-adapter-neo4j` (deliberately not a `graphrag-core` port, since this is demo-app bookkeeping, not a GraphRAG-library concern), persisting `CorpusMeta` nodes (id, derived name, document filenames, workflow status, `createdAt`, `lastActivatedAt`). `CorpusWorkflowStatus` moves alongside it. Demo/offline corpora (`markOffline`) are the one exception, staying in a small in-process set, lost on restart as today.
- `lastActivatedAt` is initialized equal to `createdAt` at creation time (never null) — otherwise a freshly-ingested corpus would sort behind older ones and auto-restore would pick the wrong one. Both timestamps are written from `graphrag-web`'s own `Instant.now()` as a Cypher parameter, never Neo4j's server-side `datetime()`.
- Corpus activation is explicit, never inferred from query traffic: `POST /api/corpora/{corpusId}/activate` is the only thing that updates `lastActivatedAt`, called exactly twice by the frontend (switcher selection, and once on page load for the auto-restored corpus). Query/vector-space/progress endpoints never touch it; no server-side "current active corpus" singleton exists — ordering/auto-restore is pure frontend logic over `GET /api/corpora` (sorted by `lastActivatedAt` descending).
- Startup reconciliation: an `ApplicationReadyEvent` listener, after the connectivity check succeeds and before HTTP traffic is served, transitions every `CorpusMeta` still `BUILDING` to `FAILED` via a single conditional Cypher write per corpus inside Neo4j's own transaction (never a read-then-write), so it stays correct even if two `app` containers briefly overlap during a redeploy.
- The existing 409-on-`BUILDING`/`FAILED` query gate in `CorpusController` is unchanged in behavior — only where status is read from/stored moves to `Neo4jCorpusRegistry`.

## UX & Interaction Patterns

- The history list (name, status, timestamps) renders on main-screen load; a `FAILED` corpus (including one reconciled by the startup sweep) must be visibly distinguishable from `READY`/`BUILDING`. Demo/offline corpora appear in the list only for the current session, never reappearing after a restart.
- Selecting a non-active corpus from the list reassigns the active corpus and re-renders the graph canvas, vector-space view, and query panel in place — no full page reload, no re-ingestion — mirroring how Story 10.4's restart-with-a-new-corpus flow already tears down and rebuilds this same UI state (EventSource, canvas, chat thread, mode toggles, Vector Space tab/panel) without a reload. That story's relocated, always-reachable restart control (paired visibility with the corpus chip in the app bar) is the sibling flow for *replacing* the active corpus; this epic's switcher is for *reselecting a previous* one — the two should coexist without one undoing the other's state resets.

## Cross-Story Dependencies

- Story 12.2 depends on Story 12.3 only for the shared `Driver` bean/adapter-module scaffolding; otherwise its Cypher/index work is independently testable from Story 12.1.
- Story 12.3 is the switch-flip: it wires the adapters Stories 12.1/12.2 build into `ParserConfig` and makes connection failure visible; until it lands, the new adapters exist but aren't actually in the running app.
- Story 12.5's reconciliation listener must run after Story 12.3's connectivity check succeeds and before HTTP traffic is served, and depends on Story 12.4's registry existing to have `CorpusMeta` nodes to reconcile.
- Story 12.6 (backend history/activate endpoints) depends on Story 12.4's registry for its data source; Story 12.7 (frontend switcher) depends on Story 12.6's endpoints and reuses/coordinates with Story 10.4's existing restart-button UI pattern rather than competing with it.
