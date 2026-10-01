# Epic 15 Context: Grounded, Cited Answers

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Local, Global, and DRIFT Search currently find seeds by keyword overlap (`KeywordMatcher`) against Entity names and Community summaries, and answer with a fixed sentence template. This epic makes them find their starting points by meaning (embeddings), has the LLM write the answer from the context actually retrieved, and cites the exact Text Units used. Citations appear in the chat and as their own steps in Replay. The point for a technical audience is to show "this answer came from these sentences." The epic builds on Epic 13 (Entity and Relationship descriptions, Text Units, provenance) and Epic 14 (Leiden Communities with content-based summaries and titles).

## Stories

- Story 15.1: Match Questions to Entities and Communities by Meaning
- Story 15.2: Synthesize Cited Local Search Answers
- Story 15.3: Synthesize Cited Global and DRIFT Answers
- Story 15.4: Show Citations in Chat and Source Passages in Replay

## Requirements & Constraints

- **Generated answers:** Local, Global, and DRIFT answers are written by the LLM from the retrieved context: Entity and Relationship descriptions, Community summaries, and source Text Units. They are not template sentences. Offline/demo mode (no API key) keeps its deterministic templated answers.
- **Grounded citations:** answers cite the Text Units they rely on. Every cited Text Unit must also appear as a step in that answer's Retrieval Trace, so nothing is cited that the search never touched. The user can open a citation and read the passage.
- **No unsupported answers:** if the context does not support an answer, return the existing "no answer found" state, not a made-up answer.
- **Semantic matching:** a question with no words in common with an Entity's name, but matching its description, must still find that Entity (e.g. "who designed the language?" should find the language's creator). Offline mode keeps keyword matching.
- **Bounded reliability:** LLM and embedding failures show a visible error and are never retried automatically. Do not hardcode provider assumptions; all LLM access goes through the port.
- **Offline and tests stay network-free:** with no embedding model or API key configured, behaviour and existing tests are unchanged.

## Technical Decisions

- **Seed matching:**
  - After extraction, embed each Entity's `name + description` via `EmbeddingPort` and store it as an `embedding` property (`LIST<FLOAT>`).
  - After community detection, embed each Community's summary the same way.
  - Use one Neo4j vector index per label (`Entity`, `Community`). Each index spans all corpora and is filtered by `corpusId` at query time with the Cypher `SEARCH ... WHERE` clause. This is the same pattern the Vector Baseline uses for `Chunk`s. Do not create one index per corpus, and do not use the deprecated `db.index.vector.queryNodes`.
  - Embed the question once. Local takes the top 3 Entities; Global and DRIFT take the top 3 Communities. Record the seeds as trace steps in similarity order.
  - Fall back to `KeywordMatcher` when no embedding model is configured.
- **Answer synthesis:**
  - Each use case first assembles its context and records every item as a trace step as it is added:
    - Local: seed Entities, their one-hop Relationships, and the Text Units those cite, capped and ordered by highest `weight` first.
    - Global: the top Community summaries plus each Community's highest-`weight` member Text Units, capped.
    - DRIFT: each branch's Local-style context under its `SUB_QUESTION_SPAWNED` step. The final synthesis uses the union of the branch contexts.
  - Then call the new `LlmPort.synthesizeAnswer(question, context)`. Its prompt numbers each context item and requires inline `[n]` citations.
  - The adapter drops any citation that was not in the given context. A model answer of "not in the context" maps to `noAnswer`.
- **New trace step kind:** `TEXT_UNIT` carries the Text Unit id and a short excerpt. It extends the existing step set (Entity, Relationship, Community, `SUB_QUESTION_SPAWNED`, chunk-via-similarity). The trace stays one ordered step sequence. It is held in memory, addressed by `traceId`, and never persisted to Neo4j.
- **Query contract:** the success response gains an additive `citations` array of `{textUnitId, documentName, excerpt}`. The endpoint, the `mode` enum (`LOCAL|GLOBAL|DRIFT`), the `noAnswer` shape, and the `{"error": ...}` shape are all unchanged.
- **Boundaries:**
  - Core has no framework dependency.
  - LangChain4j/OpenAI types stay in the langchain4j adapter, which implements both `EmbeddingPort` and the LLM port.
  - Neo4j access is the plain driver plus Cypher in the neo4j adapter.
  - Every node and index query is scoped by `corpusId`.
  - The Docker setup stays at exactly two containers, so no new vector store.
- **Separate data:** Text Units (~6,000 characters, Epic 13) are separate from the Vector Baseline's 500-character `Chunk`s. Do not merge them. The Vector Baseline trace stays a separate trace.
- **Older corpora:** corpora persisted without descriptions or embeddings must still load and answer. Treat missing values as empty, never as an error.

## UX & Interaction Patterns

- **Chat:**
  - Each `[n]` marker is a small button.
  - A "Sources" list under the answer shows `{documentName} · {excerpt}` for each citation.
  - Activating a marker or a source opens the passage text in a popover or the detail panel, reusing `GET /api/corpora/{corpusId}/text-units/{textUnitId}` (404 shows "Passage not available").
- **Replay:**
  - The caption for a `TEXT_UNIT` step reads "Read passage {ordinal} of {documentName}" plus the excerpt.
  - Entities citing that passage are highlighted on the canvas.
  - Steps stay discrete, and each has a plain-language caption.
  - DRIFT keeps its fixed traversal order: community pass, then branches in the order spawned, then synthesis. Synthesis is reached only after all branches.
- **Offline:** answers without citations render exactly as today.
- **Help articles:** update `reading-an-answer` and `trace-replay` to explain citations and passage steps.

## Cross-Story Dependencies

- **15.1:** provides the seeds for 15.2 and 15.3, but those stories must also work with the keyword fallback.
- **15.2:** introduces `synthesizeAnswer`, citation filtering, the `TEXT_UNIT` step, and the `citations` response field. 15.3 reuses all of them, and DRIFT branches reuse Local context assembly.
- **15.4:** depends on 15.2 and 15.3 for `citations` and `TEXT_UNIT` steps.
- **Epic 13:** 15.4 reuses Epic 13's text-unit endpoint and its Entity–passage links (`MENTIONED_IN`, `sourceTextUnitIds`).
- **Epic 13 data:** Entity and Relationship descriptions, `weight`, and `(:TextUnit)` nodes are required context sources.
- **Epic 14:** Community summaries and titles are required for Global and DRIFT context and for Community embeddings.
