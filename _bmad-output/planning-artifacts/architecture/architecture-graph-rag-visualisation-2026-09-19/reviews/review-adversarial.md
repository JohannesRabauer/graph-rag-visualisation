---
name: 'Adversarial Review — ARCHITECTURE-SPINE'
type: review
lens: adversarial
target: architecture/architecture-graph-rag-visualisation-2026-09-19/ARCHITECTURE-SPINE.md
created: 2026-09-19
---

# Adversarial Review — GraphRAG Lens Architecture Spine

## Method

For each candidate hole, I construct two units one level down (two contributors, or two modules built independently against the spine) that each satisfy every stated AD to the letter, and show they still produce incompatible systems — a clash the spine claims (via its "Prevents" clauses) or implicitly needs to close off, but does not.

I checked all 9 ADs plus the Consistency Conventions table against the 17 FRs. The spine is unusually disciplined about **which framework touches what** (AD-1/2/3/9) and about **transport/toggle semantics** (AD-5/6/7/8). Its blind spot is consistent: it says almost nothing about **shared data shape** — the actual Neo4j schema for the domain nouns it names (Entity, Relationship, Community), and the actual wire contracts (SSE payload shape, trace addressing, port dispatch). Every finding below lives in that gap.

## Verdict

**Not spine-complete.** The ADs correctly lock down framework boundaries and transport choice, but leave the graph schema, the multi-adapter dispatch mechanism, and the SSE/trace wire contracts unconstrained — each of these is a place where two AD-compliant contributors can build incompatible systems without violating a single rule.

---

## Finding 1 (Highest severity) — No entity-identity/deduplication rule: two AD-compliant Neo4j write paths produce structurally different graphs

**Binds violated in spirit, not letter:** AD-1, AD-2. **Touches:** FR-4, FR-16, FR-6 (community quality depends on graph shape).

- **Unit A** (implements `IngestCorpus`'s write-through to `Neo4jGraphStoreAdapter`): on each LLM extraction, `MERGE (e:Entity {name: $canonicalName})` — entities are deduplicated by exact-match name across the whole Corpus, so "Sherlock Holmes" mentioned in five stories is one node with five sets of relationships.
- **Unit B** (same seam, different contributor): `CREATE (e:Entity {...})` per extraction call, with no merge — every LLM extraction produces a fresh node, so "Sherlock Holmes" becomes five disconnected nodes, one per document.

Both units are pure "Neo4j Java Driver + hand-written Cypher" (AD-2 satisfied to the letter), both keep `graphrag-core` framework-free (AD-1 satisfied — the dedup choice lives entirely inside the adapter's Cypher, which core never sees), and both correctly persist "nodes and relationships in Neo4j" per FR-4. Yet the resulting Knowledge Graphs are structurally incompatible: Unit A's graph is community-detectable in the way the PRD's demo narrative assumes (GDS Leiden clusters around genuinely shared entities); Unit B's graph fragments the same character across documents, degrading FR-6 detection quality and making FR-16/FR-17 ("click an Entity, see its connections") show a different, wrong-looking picture depending purely on which contributor wrote the adapter. **AD-2's own "Prevents" clause names exactly this class of problem ("two contributors picking incompatible persistence styles") but only for OGM-vs-raw-Cypher — it never reaches the dedup/identity question, which is the persistence-style divergence that actually matters here.**

---

## Finding 2 — Community write-back schema is unconstrained: node-per-community vs. property-per-entity

**Binds violated in spirit, not letter:** AD-2, AD-4. **Touches:** FR-6, FR-10, FR-16, domain model's own listing of `Community` as a first-class type.

- **Unit A** (`Neo4jGraphStoreAdapter`'s Leiden write-back): runs `gds.leiden.write(..., writeProperty: 'communityId')`, the idiomatic GDS pattern — community membership becomes an integer property on each `:Entity` node. No `:Community` node ever exists in the graph.
- **Unit B** (same seam): after GDS Leiden produces cluster assignments, writes explicit `(:Community {id, summary})` nodes and `(:Entity)-[:BELONGS_TO]->(:Community)` relationships, matching the domain model's listing of `Community` as a peer of `Entity`/`Relationship`.

Both are AD-2-compliant (driver + hand-written Cypher, no OGM) and both satisfy AD-4 to the letter — AD-4 only constrains how relationships are *projected into the GDS call* ("regardless of how they're stored/directed elsewhere"), it says nothing about how the *algorithm's output* is written back. Yet `AnswerGlobalSearch` (FR-10, "aggregating over Community summaries") and the Explore page's "Communities always visible" (FR-16) need to query one specific shape. A contributor building `AnswerGlobalSearch` against Unit A's schema will look for a `:Community` node holding a summary and find none — there is nowhere to even store a community summary text in Unit A's schema. A contributor building the Explore-page community rendering against Unit B's schema will query `:Community` nodes that don't exist under Unit A. This is a direct instance of "two contributors picking incompatible persistence styles" that AD-2's own rationale claims to prevent, but its rule text only reaches the OGM-vs-Cypher axis, not the write-back shape.

---

## Finding 3 — Community-summary generation: unowned by any use case, so its LLM-call site and persistence are unspecified

**Binds violated in spirit, not letter:** AD-3, AD-6. **Touches:** FR-10 ("aggregating over Community summaries" implies each Community has a summary, which must come from an LLM call, but no FR or AD says who produces it or when).

- **Unit A**: `DetectCommunities` (per AD-6, "runs automatically and asynchronously immediately after Knowledge Graph construction completes, unconditionally") also calls `LlmPort` once per detected Community immediately after Leiden clustering, to generate and persist a summary — so every ingestion run pays LLM cost for summaries whether or not the user ever asks a Global Search question.
- **Unit B**: `AnswerGlobalSearch` generates community summaries lazily, on first query, calling `LlmPort` at query time and caching or persisting the result then.

Both units satisfy AD-6 to the letter (`DetectCommunities` in Unit A still "runs unconditionally" — summary generation is additional work inside it, not a gate on whether detection runs; Unit B doesn't touch `DetectCommunities` at all) and both satisfy AD-3 (`LlmPort` is the only LLM access point in both). But they diverge on: (a) whether a Community always has a summary the instant detection finishes, (b) where summary text is persisted (if at all — Unit B might treat it as transient, colliding with Finding 2's schema question), and (c) the latency/cost profile of a fresh ingestion vs. a first Global Search query. A frontend built assuming Unit A's "summary always ready by the time detection SSE-completes" will show blank/broken summaries against a backend built as Unit B.

---

## Finding 4 — `DocumentParserPort` has no defined multi-implementation dispatch rule, so AD-9's own boundary can be satisfied while re-violating AD-1

**Binds violated in spirit, not letter:** AD-9's rule text ("`IngestCorpus` calls the port, never a concrete parser"), AD-1 (zero framework deps in core). **Touches:** FR-1, FR-2.

Once there are two adapter classes (`PlainTextDocumentParserAdapter`, `PdfDocumentParserAdapter`) implementing one `DocumentParserPort`, something has to pick the right one per uploaded file. The spine never says what.

- **Unit A**: adds `boolean supports(String filename)` to the `DocumentParserPort` interface itself (defined in `graphrag-core`); `IngestCorpus` iterates injected implementations and calls `.supports(...)` — dispatch logic stays entirely inside the core-owned port contract, framework-free.
- **Unit B**: leaves `DocumentParserPort` with only a `parse(...)` method; dispatch is done in `graphrag-web`'s controller via Spring `@Qualifier`/bean-name matching on file extension, and `graphrag-web` picks which adapter bean to hand to `IngestCorpus` before calling it.

Unit A satisfies AD-9's rule text ("`IngestCorpus` calls the port, never a concrete parser" — literally true, it calls `.supports()`+`.parse()` on port instances) and keeps AD-1 intact. Unit B *also* satisfies AD-9's rule text just as literally (`IngestCorpus` still only ever calls the `DocumentParserPort` interface it's handed — it never references `PdfDocumentParserAdapter` by name) — but the file-type-selection knowledge has leaked into `graphrag-web` via Spring wiring, meaning `IngestCorpus` is not actually driving file-type resolution as FR-1's "unsupported extension is rejected with a clear message" implies it should (that validation logic now lives in two different modules depending on which contributor built it, and adding a new parser under Unit B requires editing `graphrag-web`'s dispatch code — silently reintroducing the exact "future file type requires changes outside the parsing adapter" problem AD-9 was written to prevent, just one layer further out than AD-9 checks for).

---

## Finding 5 — No SSE event/payload contract: two AD-7-compliant implementations produce incompatible wire formats

**Binds violated in spirit, not letter:** AD-7 (only says "SSE, not WebSocket"; says nothing about event shape). **Touches:** FR-6/FR-7 (community animation), Capability Map row "Live ingestion progress."

- **Unit A** (backend `graphrag-web`): a single `/api/progress` SSE stream, one connection per session, JSON payloads `{"phase": "ingest"|"construct"|"detect", "percent": 0-100, "message": "..."}`.
- **Unit B** (backend, different contributor, or frontend built independently against "SSE" alone): three separate SSE endpoints (`/api/ingest/events`, `/api/construct/events`, `/api/detect/events`), each emitting bare named events (`event: progress\ndata: 42\n\n`) with no JSON envelope and no phase discriminator.

Both are indisputably "Server-Sent Events (`SseEmitter`)" per AD-7's rule text, and both avoid introducing WebSocket. A frontend contributor building against Unit A's contract cannot consume Unit B's backend at all (wrong endpoint count, wrong payload shape, no phase field to key the animation timeline on) — yet nothing in AD-7 or the Consistency Conventions table specifies event names, payload shape, or single-vs-multiplexed streams the way it does specify the API *error* JSON shape (`{"error": "..."}"`). The spine wrote a wire contract for the failure path but not for the (arguably more central, PRD-climax) live-progress path.

---

## Finding 6 — Retrieval Trace has no addressing/ID contract, only a storage-location deferral

**Binds violated in spirit, not letter:** AD-5 (constrains persistence, not addressing). **Touches:** FR-12, FR-13 (Replay).

The spine's own Deferred section already flags the trace *store implementation* (`ConcurrentHashMap` vs. Caffeine) as intentionally open and low-stakes — but that is a different question from whether a trace has a stable, externally addressable ID at all, which FR-13's Replay API needs.

- **Unit A**: `AnswerLocalSearch`/`AnswerGlobalSearch` return a `traceId` alongside the answer in the query response; `graphrag-web` exposes `GET /api/traces/{traceId}` for Replay, keyed by that id, supporting multiple concurrent/past traces in memory.
- **Unit B**: the use cases populate a single `LatestTrace` slot (no id at all — "the trace" is implicitly "whatever the last query produced"); `graphrag-web` exposes `GET /api/trace/current` for Replay.

Both satisfy AD-5's actual rule text word-for-word ("held in memory, scoped to its query/answer, never persisted to Neo4j"). But a frontend built against Unit A's `{traceId}`-keyed contract cannot replay anything served by Unit B (no id in the response to pass), and Unit B's single-slot design silently breaks the moment a second query is submitted before the first's Replay is scrubbed (FR-13's "step-by-step visualization" controls now show query 2's trace under query 1's chat bubble). Nothing in AD-5 or the Capability Map's "in-memory store, replay API" phrase resolves which of these two contracts is intended.

---

## Finding 7 (lower severity) — AD-6's toggle-vs-SSE boundary is under-specified: does progress push happen when animation is off?

**Binds violated in spirit, not letter:** AD-6 + AD-7 interaction.

- **Unit A** (backend): `DetectCommunities` always triggers the same SSE progress events over the same channel regardless of the FR-7 toggle state, since AD-6 says the toggle is "read only by the presentation layer" — the backend has no reason to know or care about it, so it always pushes.
- **Unit B** (frontend, built independently): assumes that when the toggle is OFF, no detection-progress SSE events will arrive (since nothing will render them), and instead polls a REST endpoint once, after the fact, to fetch the finished Community list for that toggle-off case.

Both are compliant with AD-6's rule text (detection execution is untouched by the toggle in both; the toggle only ever gates rendering in the frontend unit) and AD-7 (both stay on SSE, Unit B doesn't add WebSocket, it just also uses a plain REST GET). But Unit B's frontend will double-fetch or race against Unit A's backend, which never stops streaming — and if a third contributor builds the backend the way Unit B's frontend assumes (suppressing SSE emission when it detects the toggle is off, e.g. via a query param the frontend passes), that backend variant *also* looks AD-6-compliant on a shallow read even though it re-introduces exactly the coupling AD-6 exists to forbid ("the toggle only gates its animation" — not what the backend transmits). The spine never pins down whether SSE emission during detection is unconditional independent of any client-supplied toggle state.

---

## Summary Table

| # | Clash | ADs that look satisfied but don't prevent it | Severity |
|---|---|---|---|
| 1 | Entity dedup: MERGE-by-name vs. CREATE-per-mention | AD-1, AD-2 | High |
| 2 | Community write-back: property vs. node+relationship | AD-2, AD-4 | High |
| 3 | Community summary: eager (in DetectCommunities) vs. lazy (in AnswerGlobalSearch) | AD-3, AD-6 | Medium-High |
| 4 | DocumentParserPort dispatch: port-level `supports()` vs. Spring-qualifier wiring in web | AD-1, AD-9 | Medium-High |
| 5 | SSE payload/endpoint shape: single multiplexed stream vs. three bare-event streams | AD-7 | Medium |
| 6 | Retrieval Trace addressing: `traceId`-keyed vs. single "current" slot | AD-5 | Medium |
| 7 | SSE emission during detection when toggle is off: always-on vs. suppressed-by-client-param | AD-6, AD-7 | Low-Medium |

## Recommendation

Add explicit conventions (not necessarily new ADs — the existing Consistency Conventions table is the right home) for: (a) entity identity/dedup rule, (b) Community's canonical Neo4j write-back shape, (c) which use case owns community-summary generation and when, (d) the `DocumentParserPort` (and by extension any future multi-implementation port) dispatch mechanism, (e) an SSE event schema (channel count, payload envelope, phase discriminator), and (f) a Retrieval Trace addressing scheme. Each is currently a place where the spine's own stated "Prevents" intent (AD-1, AD-2, AD-6 in particular) is undermined by a rule text narrower than the problem it was written to solve.
