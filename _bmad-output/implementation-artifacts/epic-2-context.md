# Epic 2 Context: Corpus Ingestion & Knowledge Graph Construction

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Epic 2 turns the app from a static shell into a real GraphRAG system: users can provide a corpus (TXT upload, PDF upload, or one-click Sherlock Holmes demo set), trigger live LLM-driven extraction, and see entities and relationships materialize in Neo4j with visible progress and explicit failure handling. This delivers the first truly demoable value state—a genuine graph built from user input—while preserving the product’s core trust model: no scripted outputs, no silent failures, and no hidden retries.

## Stories

- Story 2.1: Upload a Plain Text Corpus
- Story 2.2: Upload a PDF Corpus
- Story 2.3: Use the Built-in Demo Dataset
- Story 2.4: Extract Entities and Relationships into the Knowledge Graph
- Story 2.5: Show Live Ingestion Progress
- Story 2.6: Surface Extraction Failures Visibly

## Requirements & Constraints

- Corpus ingestion must support `.txt` and `.pdf`, plus a first-class built-in demo dataset option with equal visual weight to upload.
- Unsupported file types must be rejected with a clear, visible message; they must never be silently ignored.
- PDFs with no extractable text must produce a visible error; OCR for scanned/image-only PDFs is explicitly out of scope.
- Ingestion output must come from live LLM calls on each run; precomputed, cached, or canned extraction output is not allowed.
- Extraction failures must surface as explicit visible error states, never as silent hangs, crashes, or indefinite waiting.
- No automatic retry or fallback path is allowed for LLM failures in this epic.
- Reliability is intentionally bounded: “fail visibly” is required; “self-heal automatically” is not.
- LLM integration must remain provider-swappable and must avoid hard-wiring provider-specific assumptions outside the dedicated adapter boundary.

## Technical Decisions

- Preserve strict hexagonal boundaries:
  - `graphrag-core` owns ingestion and extraction use cases and depends only on ports.
  - Framework and library details stay in adapters.
- LLM access is port-only:
  - All extraction logic outside the LLM adapter calls `LlmPort`.
  - LangChain4j/OpenAI types are confined to `graphrag-adapter-langchain4j`.
- File parsing is adapter-scoped behind `DocumentParserPort`:
  - Separate parser adapters for plain text and PDF.
  - Parser selection is driven by adapter `supports(filename)` dispatch, not web-layer bean qualifiers.
- Neo4j persistence uses driver + Cypher only (no SDN object mapping).
- Entity writes must deduplicate by normalized identity using Cypher `MERGE` (lowercased name + type), never blind `CREATE`.
- Progress transport is SSE-only (no WebSocket):
  - Single multiplexed stream per corpus at `GET /api/corpora/{corpusId}/progress`.
  - Named events use a consistent `{"type","data"}` JSON envelope.
  - Error events are part of the same progress stream contract.
- Write/read behavior must stay live and non-blocking:
  - Graph writes commit incrementally rather than waiting for one corpus-wide transaction.
  - Downstream reads can observe partial committed graph state.
- Keep naming and API consistency strict (Corpus, Entity, Relationship terminology and consistent plain-language error shape).

## UX & Interaction Patterns

- The main screen empty state is the ingestion entry point (no setup wizard): users can upload or select demo data immediately.
- Upload and demo dataset are peer actions, not “primary + fallback.”
- During ingestion, graph growth must be visibly progressive on the canvas (nodes/edges appearing as extraction advances).
- Error presentation uses the shared error-banner pattern with plain-language messages and prominent warm alert styling.
- Corpus identity should remain visible via the corpus chip once queued/active.
- The interface stays single-screen and continuous while ingestion runs; users are not forced into modal flows.

## Cross-Story Dependencies

- Stories 2.1, 2.2, and 2.3 all feed the same queueing path that triggers Story 2.4.
- Story 2.4 is the functional core for Stories 2.5 and 2.6:
  - 2.5 depends on extraction-side events to visualize progress.
  - 2.6 depends on extraction-side failure signaling to render explicit error states.
- Story 2.5 progress plumbing should remain independently demonstrable (e.g., heartbeat/event flow) even if all extraction edge cases are not complete.
- Epic-level dependencies:
  - Depends on Epic 1 foundations (module boundaries, runtime shell, environment bootstrapping).
  - Unblocks later epics that require an ingested graph (querying, community workflows, and exploration).
