# Epic 8 Context: Vector-RAG Comparison Baseline *(v1.1)*

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Epic 8 adds a deliberately plain vector-similarity baseline that can be run on demand for a question that GraphRAG has already answered. Its purpose is not benchmarking or improving retrieval quality, but making the contrast visible: developers and viewers should be able to see what gets chunked, embedded, retrieved, and synthesized in a conventional vector pipeline, then compare that replay directly with GraphRAG’s graph-based trace on the same question.

## Stories

- Story 8.1: Build the Vector Index Alongside the Knowledge Graph
- Story 8.2: Implement AnswerVectorBaseline
- Story 8.3: Trigger the Vector Baseline On Demand from an Answer
- Story 8.4: Capture the Vector Trace
- Story 8.5: Render the Embedding Space and Replay the Vector Trace

## Requirements & Constraints

The system must build a vector index from each ingested corpus by chunking the corpus, embedding each chunk, and keeping that work independent from entity/relationship extraction. Vector indexing must run alongside graph ingestion rather than blocking it or being blocked by it.

The baseline answer path must stay intentionally simple: embed the query, retrieve the top-k similar chunks, and synthesize an answer from those chunks alone. It must not use graph traversal, community summaries, or any hidden GraphRAG logic. This feature exists to show a plain vector-RAG path, not to create a hybrid mode.

Vector comparison is strictly on demand. A GraphRAG answer must appear first, and only then may the user trigger “Compare with Vector Search” for that exact question. The vector baseline must never run automatically for every query, because the comparison is meant to be a deliberate teaching moment and should not double cost by default.

The vector pipeline must produce its own replayable trace. That trace needs to cover the meaningful stages of the vector flow: corpus chunking, per-chunk embedding, query embedding, similarity-based retrieval/ranking, and answer synthesis. Ranked retrieval steps must carry the retrieved chunk identity and similarity score so the replay explains why those chunks were selected.

The embedding-space visualization must be stable across questions. Chunk embeddings are projected into 2D once during ingestion and reused later, so switching tabs, replaying traces, or comparing multiple questions never reshuffles the corpus layout. The view is explanatory, not evaluative.

This epic remains illustrative rather than benchmark-oriented. It is in scope to visualize vector retrieval mechanics; it is out of scope to introduce scoring, evaluation dashboards, automatic side-by-side execution, or provider/model comparison features.

## Technical Decisions

The project keeps the existing architecture boundaries: vector work belongs in `graphrag-core` use cases and ports, while technology-specific code stays in existing adapters. New vector capabilities are introduced through `EmbeddingPort` and `VectorStorePort`, with `ConstructVectorIndex` and `AnswerVectorBaseline` as the core use cases.

No new service or container is introduced for vector search. Embeddings are produced through the existing LangChain4j/OpenAI adapter, and vector persistence/search is handled by the existing Neo4j adapter using Neo4j’s native vector index. This preserves the two-container deployment and avoids leaking provider- or database-specific types into the core.

The vector index build is a first-class ingestion step, but it must remain decoupled from graph extraction. Corpus chunking, embedding, vector storage, and 2D projection persistence happen during ingestion-time processing without gating entity extraction or vice versa.

Vector results and traces are separate artifacts, not extensions of the original GraphRAG answer. A vector comparison run generates its own `answerId` and `traceId`. The vector trace follows the same replay infrastructure as other traces, but it remains an independent trace rather than being merged into the GraphRAG trace for the question.

Trace modeling stays ordered and step-based. In particular, the vector trace introduces retrieval steps that explicitly represent chunks retrieved via similarity, including their scores, so replay remains concrete and scrubbable rather than collapsing into a single summary result.

## UX & Interaction Patterns

The Vector Space experience lives on the main screen as a tab beside the Knowledge Graph view, not as a separate page. That tab appears only after the user explicitly triggers the comparison from an existing answer.

The compare action belongs in the chat UI on answers that already expose replay. Its copy should make clear that it reruns the same question through plain similarity retrieval, not a new general search mode.

The scatter plot must present chunk points immediately at their settled positions, then replay overlays the live query point and highlights top-k neighbors step by step with similarity labels. The transport model matches the existing replay controls so the interaction feels like the same explanatory system, just for a different retrieval method.

The visual language must reuse existing meanings instead of inventing new ones for the vector view: the current/live query uses the app’s active color, and retrieved hits use the accent color. The Vector Space tab, Compare CTA, and replay controls should feel like extensions of the current interface, not a second product.

Basic keyboard reachability still applies to the new controls, especially the Compare CTA, Vector Space tab switch, and replay transport.

## Cross-Story Dependencies

Story 8.1 depends on the ingestion pipeline from Epic 2, because vector indexing is built from ingested corpus content and runs alongside corpus processing.

Stories 8.2 through 8.5 depend on the existing query and replay foundations from Epics 3 through 5: there must already be answered questions to compare against, a trace model and trace-fetching pattern to extend, and transport/replay behavior to reuse.

Story 8.3 depends on Story 8.2 for the actual vector-answer execution path, while Stories 8.4 and 8.5 depend on the vector run producing a distinct trace and persisted embedding-space data from Story 8.1.

The entire epic also depends on the existing main-screen canvas model from Epic 6 and the v1.1 mode-expansion work from Epic 7, because the comparison is presented within the same single-screen teaching workflow rather than as a separate application surface.
