# Brownfield notes — Neo4j wiring & corpus persistence

## Current state (as of this spec)

- `Neo4jGraphStoreAdapter` and `Neo4jVectorStoreAdapter` (`graphrag-adapter-neo4j`) are empty
  compatibility-alias classes that extend `InMemoryGraphStoreAdapter` / `InMemoryVectorStoreAdapter`.
  No Neo4j driver dependency exists in any `pom.xml`; no Bolt connection or Cypher exists anywhere
  in the codebase today. "Wiring to Neo4j" is net-new adapter implementation, not a config/bean swap.
- `docker-compose.yml` already defines a healthy `neo4j` service (Community Edition, `graph-data-science`
  plugin enabled) and `app` already `depends_on` it with a healthcheck — this infrastructure has existed
  since early epics but was never actually consumed by the adapters.
- `ParserConfig.java` (`graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java`)
  currently `@Bean`-wires `new InMemoryGraphStoreAdapter()` / `new InMemoryVectorStoreAdapter()` directly.
- `CorpusStore.java` (`graphrag-web/src/main/java/com/graphraglens/web/CorpusStore.java`) is a
  `ConcurrentHashMap`-backed registry, documented as intentionally in-memory-only. `CorpusController`
  has no `GET /api/corpora` list endpoint — the frontend's `activeCorpusId` (a plain JS variable in
  `upload.js`) is the only notion of "which corpus is active" today, and it resets on every page load.
- `GraphStorePort` and `VectorStorePort` (`graphrag-core/src/main/java/io/graphrag/core/port/`) already
  define per-`corpusId`-scoped methods (`entities(corpusId)`, `persistChunks(corpusId, ...)`, etc.), and
  the in-memory adapters already implement that scoping correctly. The new Neo4j adapters must satisfy
  the same scoping contract — multi-corpus support at the port level already exists; it does not need
  to be invented.
- Query/vector-space/progress endpoints (`CorpusController`) already take `corpusId` as a path parameter
  per request, so the backend is already "multi-corpus" in the sense that any request can target any
  corpus by id. What's missing is (a) durability of the data those ids point to, and (b) a way to
  discover which corpus ids exist across restarts.

## Relevant existing architecture decisions (ARCHITECTURE-SPINE.md)

- **AD-1** — `graphrag-core` has zero framework dependency: no Neo4j driver import may appear there;
  all Bolt/Cypher code must live in `graphrag-adapter-neo4j` behind the existing ports.
- **AD-8** — deployment is exactly two containers (`app`, `neo4j`); no third container may be introduced.
- **AD-10** — Entity writes use Cypher `MERGE` keyed on normalized identity (lowercased name + entity
  type), never blind `CREATE`.
- **AD-11** — Communities are first-class `(:Community {id, summary})` nodes related to member Entities
  via `[:BELONGS_TO]`, not a scalar property.
- **NFR3** (single-user, local-only, no auth/multi-tenancy) is unaffected by this work — multi-corpus
  history is one user's own corpora, not multi-tenancy.
