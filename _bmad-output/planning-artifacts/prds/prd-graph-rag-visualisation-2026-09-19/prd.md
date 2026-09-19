---
title: GraphRAG Lens — PRD
status: final
created: 2026-09-19
updated: 2026-09-19
---

# PRD: GraphRAG Lens

## 0. Document Purpose

This PRD turns the finalized product brief (`_bmad-output/planning-artifacts/briefs/brief-graph-rag-visualisation-2026-09-19/brief.md`, plus its addendum) into concrete, testable requirements. It's written for the creator as both product owner and sole implementer, structured around the journeys the app enables, with functional requirements (FR-1 through FR-N) nested under the features that realize them. Implementation-level tech choices (language, frameworks, provider abstraction mechanics) are intentionally kept out of this document and live in the brief's addendum and in architecture work that follows.

## 1. Vision

GraphRAG Lens makes GraphRAG's mechanics visible instead of theoretical. It takes documents you provide — plain text or PDF, with the public-domain Sherlock Holmes stories as a built-in demo set — and builds a real Knowledge Graph of them in Neo4j through live LLM calls. It shows the graph folding into Communities, and when you ask it a question through a chat interface, it captures exactly which nodes and Communities the answer drew on. Everything shown is real, non-scripted computation — the LLM calls are genuinely live and non-deterministic — but the *visualization* of that computation is a captured, scrubbable replay rather than a live stream, chosen deliberately because it lets you rewind and revisit a step rather than watching it fly by once.

Nothing today visualizes GraphRAG's retrieval mechanics this way — existing tools are either post-hoc artifact viewers or vector-RAG-only pipeline explainers, and Neo4j's own GraphRAG tooling doesn't reach Java developers at all. It exists first to build its creator's own understanding deep enough to explain GraphRAG confidently, live, on a coding stream — and it's built cleanly enough that it could later seed a Java-native GraphRAG library, filling a gap that research confirmed is real, not assumed.

## 2. Target User

### 2.1 Jobs To Be Done

- As the creator, I need to understand GraphRAG's mechanics deeply enough to explain them correctly and confidently, live and unscripted.
- As a live-coding streamer, I need a visually engaging demo that makes graph-based retrieval legible to viewers who don't already know graph theory or GraphRAG.
- As a prospective future open-source maintainer, I need the codebase structured cleanly enough that extracting a reusable Java GraphRAG library later isn't a rewrite.

### 2.2 Non-Users (v1)

- Teams needing hosted, multi-user, or authenticated access.
- Users needing graph backends other than Neo4j.
- Users needing file types beyond plain text and PDF (Word docs, audio, video, images, scanned/OCR-only PDFs).

### 2.3 Key User Journeys

- **UJ-1. Explaining GraphRAG live.**
  - **Persona + context:** the creator, mid coding-stream, demonstrating GraphRAG to an audience.
  - **Entry state:** the app is already running (see UJ-2); no prior session state needed.
  - **Path:** Uploads a document set (plain text/PDF) or picks the built-in Sherlock Holmes demo → watches ingestion build the Knowledge Graph in Neo4j via live LLM calls → watches community detection visibly cluster the graph → types a question into the chat interface → the system answers via Local Search or Global Search and captures a step-by-step Retrieval Trace → scrubs back and forth through the Replay, showing which nodes and Communities were touched → the final answer appears in chat.
  - **Climax:** the audience (and the creator) can see exactly which part of the graph produced the answer, not just that an answer arrived.
  - **Resolution:** the creator can explain, with the Retrieval Trace as evidence, why GraphRAG produced that specific answer.
  - **Edge case:** a live LLM call fails mid-run (rate limit, API error). No retry or cached fallback is attempted — by design — but the failure must surface as a clear, visible error state, not a crash or silent hang.

- **UJ-2. Setting up before a stream.**
  - The creator clones the repo and runs a single Docker Compose command, which provisions Neo4j (and any other required infra) automatically. The only manual step is setting the `OPENAI_API_KEY` environment variable. No other configuration is needed before UJ-1 can run.

- **UJ-3. Freely exploring the Knowledge Graph.**
  - **Persona + context:** the creator (or a viewer), after a Corpus has been ingested, wanting to browse the graph itself rather than ask it a question.
  - **Entry state:** a Corpus has already been ingested via UJ-1; the Knowledge Graph exists.
  - **Path:** navigates to the Explore page → sees the full Knowledge Graph with Communities always visible → pans and zooms freely → clicks an Entity → sees its connections, details, and Tags.
  - **Climax:** the graph stops being an abstraction tied to one question's answer — it's simply there, browsable on its own terms.
  - **Resolution:** the creator (or viewer) has a concrete, self-directed sense of the graph's actual structure, independent of any specific query.

## 3. Glossary

- **Corpus / Document Set** — the documents provided for ingestion in a given run (uploaded plain text/PDF files, or the built-in demo set).
- **Demo Dataset** — the built-in, pre-selected public-domain Sherlock Holmes corpus, offered as a one-click alternative to uploading files.
- **Knowledge Graph** — the Neo4j graph of Entities and Relationships extracted from a Corpus.
- **Entity** — a node in the Knowledge Graph representing a person, place, or concept extracted from the Corpus.
- **Relationship** — an edge in the Knowledge Graph connecting two Entities.
- **Community** — a cluster of related Entities produced by community detection (Leiden-style clustering) over the Knowledge Graph.
- **Local Search** — a retrieval mode that answers entity-specific questions via neighborhood traversal around relevant Entities.
- **Global Search** — a retrieval mode that answers corpus-wide, thematic questions by aggregating over Community summaries.
- **Retrieval Trace** — the captured, ordered record of which Entities, Relationships, and Communities were touched while answering a query.
- **Replay** — the scrubbable, step-by-step visualization of a Retrieval Trace, shown after retrieval completes.
- **Tag** — a small, user-facing label on an Entity, shown alongside that Entity's connections and details on the Explore page.

## 4. Features

### 4.1 Document Ingestion

**Description:** Users provide the source material for a run, either by uploading files or selecting the built-in Demo Dataset. Realizes UJ-1, UJ-2.

#### FR-1: Upload plain text files
User can upload one or more plain text (`.txt`) files as a Corpus. Realizes UJ-1.

**Consequences (testable):**
- Uploaded files are queued for Knowledge Graph construction (FR-4).
- A file with an unsupported extension is rejected with a clear, visible message rather than silently ignored.

#### FR-2: Upload PDF files
User can upload one or more PDF files as a Corpus; the system extracts text content from each PDF prior to Knowledge Graph construction. Realizes UJ-1.

**Consequences (testable):**
- Text extracted from a PDF feeds the same construction pipeline as plain text (FR-4).
- A PDF that yields no extractable text (e.g., scanned/image-only) produces a clear, visible error rather than a silent no-op.

**Out of Scope:** OCR of scanned/image-only PDFs.

#### FR-3: Use the built-in Demo Dataset
User can select the built-in Sherlock Holmes Demo Dataset as a one-click alternative to uploading files. Realizes UJ-1, UJ-2.

### 4.2 Knowledge Graph Construction

**Description:** Ingested documents are processed via live LLM calls into Entities and Relationships persisted in Neo4j. Every run involves a genuine, non-deterministic LLM call — never cached or canned output. Realizes UJ-1.

#### FR-4: Extract entities and relationships
System extracts Entities and Relationships from an ingested Corpus via live LLM calls and persists them as nodes and relationships in Neo4j.

**Consequences (testable):**
- Each ingestion run produces graph output derived from a real LLM call for that run; no pre-computed or cached extraction is substituted for a live call.

#### FR-5: Surface extraction failures visibly
If an LLM call fails during Knowledge Graph construction, the system displays an explicit, visible error state rather than retrying automatically, silently hiding the failure, or hanging/crashing. Realizes UJ-1 edge case.

**Out of Scope:** Automatic retry; fallback to cached or canned output (deliberately excluded — see brief's accepted-risk decision).

### 4.3 Community Detection & Visualization

**Description:** The constructed Knowledge Graph is clustered into Communities, and the clustering process itself can be shown as a distinct, watchable step (on by default for a first run, toggleable after). Realizes UJ-1.

#### FR-6: Detect communities
System runs community detection (Leiden-style clustering) over the constructed Knowledge Graph.

**Consequences (testable):**
- Detected Communities are persisted so they can be used by Global Search (FR-10) and by the community-formation visualization (FR-7).

#### FR-7: Visualize community formation
User can toggle whether community formation is visualized on the main screen (e.g., nodes visibly folding into clusters, not just a static rendering of the final grouping). The toggle defaults ON for a fresh Corpus's first run — so this signature step plays automatically rather than depending on the user remembering to enable it — and is freely switchable afterward.

**Consequences (testable):**
- Toggling this control never re-runs or affects community detection itself (FR-6), which always proceeds in the background regardless of toggle state — it only shows or hides the formation animation.

### 4.4 Query Interface

**Description:** Users ask questions through a chat-style interface, explicitly choosing Local Search or Global Search via a UI toggle before each query — a deliberate choice over automatic routing, so the two methods can be shown side by side rather than hidden behind the system's own decision. Realizes UJ-1.

#### FR-8: Submit a query
User can submit a natural-language question via a chat-style interface.

#### FR-9: Answer via Local Search
User can explicitly select Local Search via a UI toggle; system answers the query using Local Search (Entity-neighborhood traversal).

**Consequences (testable):**
- The captured Retrieval Trace (FR-12) records that Local Search was the mode used for this query.
- If neighborhood traversal from the query's matched Entities yields no relevant results, the system returns a visible "no answer found" state rather than an empty or misleading response.

#### FR-10: Answer via Global Search
User can explicitly select Global Search via a UI toggle; system answers the query using Global Search (Community-summary aggregation).

**Consequences (testable):**
- The captured Retrieval Trace (FR-12) records that Global Search was the mode used for this query.
- If no Communities exist yet (e.g., detection hasn't run), the system returns a visible "no answer found" state rather than an empty or misleading response.

#### FR-11: Render the final answer in chat
The generated answer is displayed in the chat interface once retrieval and generation complete.

### 4.5 Retrieval Trace & Playback

**Description:** Retrieval is captured as a structured trace and replayed as a scrubbable, step-by-step visualization — a deliberate choice over live-streaming, since replay supports rewinding and revisiting steps. Realizes UJ-1.

#### FR-12: Capture the retrieval trace
System captures a structured, ordered Retrieval Trace (Entities, Relationships, and Communities touched, in order) during query execution (FR-9, FR-10).

#### FR-13: Replay the retrieval trace
User can play back a captured Retrieval Trace as a step-by-step visualization after the answer is generated, with controls to move forward and backward through the steps.

**Out of Scope:** Live/real-time streaming of retrieval steps as they happen — replay-after-completion only for v1.

### 4.6 Setup & Deployment

**Description:** The application and its Neo4j dependency are provisioned with a single command; the only manual step is supplying the LLM API key. Realizes UJ-2.

#### FR-14: One-command infrastructure setup
The application and Neo4j can be started via a single Docker Compose command.

#### FR-15: API key via environment variable
The OpenAI API key is supplied via an environment variable at startup; no in-app configuration UI is required for v1.

### 4.7 Graph Exploration

**Description:** A dedicated page for free-form exploration of the full Knowledge Graph, independent of any query — browsing the graph's actual structure rather than asking it a question. Realizes UJ-3.

#### FR-16: Explore the full Knowledge Graph
User can navigate to a dedicated Explore page showing the full Knowledge Graph, with pan and zoom, reached via a persistent link/tab from the main screen.

**Consequences (testable):**
- Communities are always visualized on this page (no toggle, unlike FR-7's main-screen behavior) — this page's purpose is structural exploration, so hiding Community structure would work against it.
- If no Corpus has been ingested yet, the page shows a clear empty state pointing back to the main screen's ingestion entry point, rather than a blank or broken canvas.

#### FR-17: Inspect an Entity's details
User can click any Entity on the Explore page to see its connections (Relationships), details, and Tags.

**Out of Scope:** Editing an Entity, its Relationships, or its Tags — this is a read-only exploration view for v1.

## 5. Cross-Cutting NFRs

- **UI tone:** the interface should read as modern and minimalist, running entirely in the browser with minimal setup friction (per the brief). Full visual/interaction direction is deferred to the `bmad-ux` pass — this is a pointer forward, not a spec.
- **Reliability (deliberately bounded):** the system does not implement retries or cached fallback for LLM call failures (accepted risk, per the brief) — but a failure must always surface as a clear, visible error state, never a crash or an indefinite hang.
- **Single-user, local-only:** no authentication, hosting, or multi-tenancy for v1; the app runs on a single developer machine.
- **Provider flexibility:** the LLM integration must not hardcode assumptions that would block swapping the LLM provider later (mechanism detailed in the brief's addendum).

## 6. Non-Goals (Explicit)

- Multi-user, hosted, or SaaS deployment.
- Graph databases other than Neo4j.
- File types beyond plain text and PDF (Word docs, audio, video, images); OCR of scanned PDFs.
- Formal retrieval-quality benchmarking or evaluation dashboards.
- Automatic retry/fallback/caching safety net for LLM failures.
- Live/real-time streaming visualization of retrieval (replay only).
- Packaging or publishing this as a standalone library (future — see brief's Vision).
- A marketing/showcase website, polished README, and app icon — explicitly parked for later (see addendum).
- Editing Entities, Relationships, or Tags on the Explore page (read-only for v1).

## 7. MVP Scope

### 7.1 In Scope
- Document ingestion: plain text and PDF upload, plus the built-in Sherlock Holmes Demo Dataset (FR-1–FR-3).
- Live LLM-driven Knowledge Graph construction in Neo4j, with visible failure states (FR-4–FR-5).
- Community detection, visualized as it happens (FR-6–FR-7).
- Chat-based query interface answering via Local Search and Global Search (FR-8–FR-11).
- Captured, replayable Retrieval Trace with scrub controls (FR-12–FR-13).
- One-command (Docker Compose) setup with API key via environment variable (FR-14–FR-15).
- Free-form Knowledge Graph exploration on a dedicated page, with Entity detail inspection (FR-16–FR-17).

### 7.2 Out of Scope for MVP
- Everything listed under Non-Goals above.
- Editing Entities, Relationships, or Tags on the Explore page — read-only for v1 (FR-17).
- Library extraction/publishing — deferred to a future version, once the demo itself works. *(Revisit once v1 is stable and used on-stream a few times.)*
- Marketing website, README polish, and app icon — deferred; parked in the brief's addendum as future roadmap items.

## 8. Success Metrics

**Primary**
- **SM-1**: The creator can confidently and correctly explain GraphRAG live using the app. Qualitative; validates all FRs.

**Secondary**
- **SM-2**: The demo is genuinely engaging to watch on stream. Validates FR-6, FR-7, FR-12, FR-13.

**Counter-metrics (do not optimize)**
- **SM-C1**: Do not add retries, caching, or fallback behavior to make failures "disappear" for the sake of a smoother-looking stream — that would undermine the real, non-scripted ethos the whole project is built on. Counterbalances SM-2.

## 9. Open Questions

1. Exact Replay UI controls (play/pause/step granularity/speed) — likely detailed during the UX pass (`bmad-ux`) rather than here.
2. Minimum Java and Neo4j version targets — deferred to architecture (`bmad-architecture`).

## 10. Assumptions Index

None outstanding — the one open assumption (§4.4, Local vs. Global Search selection) was confirmed during review: explicit UI toggle, not automatic routing.
