# Epic 2 Context: Corpus Ingestion & Knowledge Graph Construction

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Let a user bring their own documents (plain text or PDF) or pick the built-in Sherlock Holmes Demo Dataset, and watch a real Knowledge Graph get built in Neo4j through live, non-deterministic LLM extraction — with visible, honest errors if a file is rejected or an LLM call fails, never a silent hang or crash. This is the first epic where a user has genuine graph data to show for their input, and it establishes the LLM-provider abstraction and live-progress plumbing that later epics (Query, Community Detection) build on.

## Stories

- Story 2.1: Upload a Plain Text Corpus
- Story 2.2: Upload a PDF Corpus
- Story 2.3: Use the Built-in Demo Dataset
- Story 2.4: Extract Entities and Relationships into the Knowledge Graph
- Story 2.5: Show Live Ingestion Progress
- Story 2.6: Surface Extraction Failures Visibly

## Requirements & Constraints

- Users can upload one or more `.txt` files, one or more PDF files, or one-click the built-in Sherlock Holmes Demo Dataset as their Corpus; all three paths feed the same construction pipeline and are presented with equal visual weight — neither upload nor the Demo Dataset is the "default" or fallback option.
- An unsupported file extension must be rejected with a clear, visible message, never silently ignored.
- A PDF that yields no extractable text (e.g. scanned/image-only) must produce a clear, visible error; OCR is explicitly out of scope.
- Every ingestion run performs a genuine, live LLM call to extract Entities and Relationships — no cached, canned, or pre-computed extraction is ever substituted.
- If an LLM call fails during construction (rate limit, API error, etc.), the system must show an explicit, visible error state. No automatic retry and no fallback to cached/canned output — this is a deliberate, accepted risk (do not "smooth over" failures for a better-looking demo).
- The LLM integration must not hardcode assumptions that would block swapping the provider later (this epic is where that abstraction boundary is first established).
- Success criteria this epic serves: the creator can explain GraphRAG live using genuinely live computation, and a failure surfacing honestly is itself acceptable — not a defect to engineer away.

## Technical Decisions

- `graphrag-core` has zero framework dependency; only `graphrag-adapter-langchain4j` may import LangChain4j/OpenAI SDK types — everything else in the ingestion path calls the core-defined `LlmPort`.
- Document parsing (plain text, PDF via Apache PDFBox 3.0.x) sits behind `DocumentParserPort` in `graphrag-adapter-parsing`. Each file-type adapter exposes its own `supports(filename): boolean`; a core-owned dispatcher selects the matching adapter. `graphrag-web` never wires a specific parser by type or name (no Spring `@Qualifier`).
- Entities are written via Cypher `MERGE` keyed on a normalized identity (lowercased name + entity type), never a blind `CREATE`, so the same entity mentioned twice resolves to one node. All Neo4j access goes through the plain Neo4j Java Driver + hand-written Cypher (never Spring Data Neo4j).
- Each Entity/Relationship write commits its own Neo4j transaction as soon as it's extracted — never batched into one Corpus-wide transaction — so partial graph state is visible/queryable mid-ingestion.
- Live ingestion progress is pushed over a single multiplexed SSE stream per Corpus: `GET /api/corpora/{corpusId}/progress`, named events (e.g. `entity-extracted`, `ingestion-complete`, `error`) each as `{"type": "<event-name>", "data": {...}}`. No WebSocket, no second progress endpoint.
- An LLM-call failure emits an `error` SSE event on that Corpus's progress stream and is logged; API error responses use a single consistent shape `{"error": "<plain-language message>"}`.
- Deployment stays exactly two Docker Compose services (`app`, `neo4j` with GDS plugin) — the Demo Dataset ships bundled inside the `app` service, not a third service.

## UX & Interaction Patterns

- The idle/empty main-screen state doubles as the Corpus-upload/Demo-Dataset-selection screen — no gated setup wizard.
- Upload and Demo Dataset are presented together as two equally-weighted paths into the same pipeline.
- Once a Corpus is chosen, the Corpus chip (app-bar pill) appears, naming the Corpus and document count (e.g. "Sherlock Holmes — Demo Dataset · 12 documents").
- Ingestion-in-progress: the graph canvas visibly builds — nodes/edges appear live as Entities/Relationships are extracted (faint-grid canvas, uppercase eyebrow state title, e.g. "Building the Knowledge Graph — extracting Entities and Relationships…"). Chat remains usable during ingestion in later epics; nothing here should gate on ingestion completing.
- Upload-rejected state: plain-language error naming the specific problem (e.g. "That file type isn't supported — plain text or PDF only" / "No text could be read from that PDF — is it a scanned image?"), shown via the Error banner component, never a silent no-op.
- LLM-call-failure state: Error banner with a plain-language message (e.g. "The LLM call failed (rate limit or API error). Nothing was retried — try again when ready.") — no icon glyphs beyond the warm active-color cue, no automatic retry.
- Voice/tone: tutorial-clear microcopy, Glossary terms (Corpus, Entity, Relationship) named consistently, never paraphrased.

## Cross-Story Dependencies

- Story 2.4 (extraction) requires a Corpus already queued by 2.1, 2.2, or 2.3.
- Story 2.5 (live progress) is intentionally independently demoable (e.g. via a heartbeat event) and should not depend on every extraction edge case in Story 2.4 being finished.
- Story 2.6 (failure surfacing) depends on the LLM-call pipeline built in Story 2.4 and the SSE channel built in Story 2.5.
- This epic establishes the `LlmPort`/LangChain4j adapter boundary and the SSE progress-envelope pattern that Epic 4 (Community Detection) reuses for its own progress events and summary generation.
- Epic 4's `DetectCommunities` runs automatically immediately after this epic's Knowledge Graph construction completes (unconditionally, regardless of any visualization toggle).
- Epic 6 (Graph Exploration) requires a Corpus to have been ingested here before its page shows real content.
