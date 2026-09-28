---
id: SPEC-neo4j-corpus-persistence
companions: [brownfield.md, ../../planning-artifacts/architecture/architecture-graph-rag-visualisation-2026-09-19/ARCHITECTURE-SPINE.md]
sources: []
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Wire real Neo4j persistence and add corpus history

## Why

Restarting the app loses everything: the extracted knowledge graph, embeddings, and even the memory
of which corpora were ever ingested — because `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter` are
placeholder classes that silently delegate to in-memory maps, despite `docker-compose.yml` already
running a real Neo4j instance alongside the app for exactly this purpose. This is a pain to solve for
a single developer who wants to keep working across app restarts without re-ingesting and re-paying for
LLM extraction every time, and an opportunity to finally use infrastructure that has sat unused since
early epics. The user wants this done thoroughly: real Neo4j-backed storage, plus the ability to see and
switch between every corpus ever ingested rather than losing that history to a restart.

## Capabilities

- **CAP-1**
  - **intent:** The app persists a corpus's extracted Entities, Relationships, Communities, and CommunityMemberships to the running Neo4j instance instead of JVM heap, scoped per `corpusId`, via a real `GraphStorePort` implementation using the Neo4j Java driver (Cypher `MERGE` per AD-10, first-class `(:Community)` nodes per AD-11).
  - **success:** After ingesting a corpus, inspecting Neo4j directly (Browser or a GDS Leiden run) against that `corpusId` shows the same Entity/Relationship/Community nodes the app's UI displays, and they are still present after restarting the app container without re-ingesting.
- **CAP-2**
  - **intent:** The app persists a corpus's embedded chunks and fitted 2D projection model to Neo4j instead of JVM heap, scoped per `corpusId`, via a real `VectorStorePort` implementation.
  - **success:** After ingesting a corpus and restarting the app, the vector-space view and the vector-baseline query mode for that `corpusId` work identically to before the restart, with no re-embedding.
- **CAP-3**
  - **intent:** The app connects to the docker-compose `neo4j` service using externally configurable Bolt URI and credentials, and fails fast with a clear startup error if Neo4j is unreachable rather than silently degrading to in-memory behavior.
  - **success:** Starting the full docker-compose stack requires no manual connection setup; stopping the `neo4j` container and starting `app` alone produces an explicit connection-failure error, not silent degraded behavior.
- **CAP-4**
  - **intent:** Corpus bookkeeping (id, derived name, document filenames, workflow status, createdAt, lastActivatedAt) is read and written directly against Neo4j `CorpusMeta` nodes at the same lifecycle points `CorpusStore` currently updates in-memory maps — `CorpusStore`'s in-memory maps are removed outright, not kept as a cache layer.
  - **success:** After restarting the app, previously-ingested corpora still appear with their correct name and status, without needing to be re-uploaded.
- **CAP-5**
  - **intent:** On app startup, the frontend's active corpus is automatically set to whichever corpus has the most recent `lastActivatedAt`, so the user resumes where they left off without manual selection.
  - **success:** Stopping and restarting the whole docker-compose stack reopens the UI already pointed at the same corpus, and its graph/vector-space, that was active before the restart.
- **CAP-6**
  - **intent:** A new `GET /api/corpora` endpoint returns every retained corpus (id, name, status, createdAt, lastActivatedAt) sourced from `CorpusMeta` nodes, ordered most-recently-activated first.
  - **success:** After ingesting three corpora across two app restarts, `GET /api/corpora` returns all three with correct metadata.
- **CAP-7**
  - **intent:** The frontend renders the corpus history from CAP-6 as a selectable list; clicking an entry reassigns the active corpus to it and re-renders the graph canvas, vector-space view, and query panel against it, and records that corpus's `lastActivatedAt` as now.
  - **success:** With two previously-ingested corpora, clicking the non-active one swaps the displayed graph/vector-space/query results to that corpus's data within the same page load — no re-ingestion, no full page reload.
- **CAP-8**
  - **intent:** On app startup, before serving any request, every `CorpusMeta` node still in `BUILDING` status is transitioned to `FAILED`, since surviving to the next startup in `BUILDING` can only mean the previous process died mid-ingestion.
  - **success:** Killing the app process while a corpus is mid-ingestion, then restarting it, shows that corpus as `FAILED` in the history list rather than stuck `BUILDING` forever.

## Constraints

- `graphrag-core` stays framework-free (AD-1): no Neo4j driver import anywhere in `graphrag-core`; all Bolt/Cypher code lives in `graphrag-adapter-neo4j` behind `GraphStorePort`/`VectorStorePort`.
- Entity writes use Cypher `MERGE` on normalized identity, never blind `CREATE` (AD-10).
- Communities are written as first-class `(:Community {id, summary})` nodes with `[:BELONGS_TO]` relationships, not a scalar property (AD-11).
- Deployment stays exactly two containers — `app` and `neo4j` (AD-8); corpus registry metadata is persisted as Neo4j nodes alongside the graph/vector data, not a third store or container.
- The new Neo4j adapters must satisfy the per-`corpusId` scoping contract `GraphStorePort`/`VectorStorePort` already define (see brownfield.md) — multi-corpus scoping is an existing contract to fulfill, not a new concept to design.

## Non-goals

- Retaining original uploaded document bytes or supporting re-ingestion/rebuild-from-scratch after restart — only already-extracted graph/vectors/metadata are restored.
- Corpus deletion or any retention/pruning policy — every corpus ingested is kept indefinitely.
- Embedded/in-process Neo4j — only the existing external docker-compose `neo4j` service is supported.
- Any change to NFR3 (single-user, local-only, no auth/multi-tenancy) — corpus history is one user's own corpora, not multi-tenancy.
- Persisting `RetrievalTrace`/query-replay history (AD-5, FR-12–13) — stays in-memory as today; a separate, unaddressed feature.
- A data-migration tool for pre-existing in-memory corpora — nothing survives a restart today, so there is nothing to migrate; this only affects corpora ingested after this ships.
- Durable history for demo/offline corpora (`CorpusStore.markOffline`, Story 9.1) — these stay a transient, in-memory-only construct for the current process lifetime, visible in the history list only until the next restart, never persisted to Neo4j and never reappearing afterward.
- A cache layer in front of Neo4j for the corpus registry — CAP-4 reads and writes `CorpusMeta` directly; no in-memory read cache is introduced for it.

## Success signal

A user ingests two corpora, restarts the full docker-compose stack, and on reopening the app is looking at the same corpus (graph, vector-space, query answers) that was active before restart, with the other corpus one click away in a history list showing both — with zero re-ingestion and zero LLM/embedding calls re-run.
