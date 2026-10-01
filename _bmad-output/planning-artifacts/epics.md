---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-graph-rag-visualisation-2026-09-19/prd.md
  - _bmad-output/planning-artifacts/architecture/architecture-graph-rag-visualisation-2026-09-19/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/ux-designs/ux-graph-rag-visualisation-2026-09-19/DESIGN.md
  - _bmad-output/planning-artifacts/ux-designs/ux-graph-rag-visualisation-2026-09-19/EXPERIENCE.md
---

# GraphRAG Lens - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for GraphRAG Lens, decomposing the requirements from the PRD, UX Design, and Architecture into implementable stories.

## Requirements Inventory

### Functional Requirements

FR1: User can upload one or more plain text (`.txt`) files as a Corpus.
FR2: User can upload one or more PDF files as a Corpus; the system extracts text content from each PDF prior to Knowledge Graph construction.
FR3: User can select the built-in Sherlock Holmes Demo Dataset as a one-click alternative to uploading files.
FR4: System extracts Entities and Relationships from an ingested Corpus via live LLM calls and persists them as nodes and relationships in Neo4j.
FR5: If an LLM call fails during Knowledge Graph construction, the system displays an explicit, visible error state rather than retrying automatically, silently hiding the failure, or hanging/crashing.
FR6: System runs community detection (Leiden-style clustering) over the constructed Knowledge Graph.
FR7: User can toggle whether community-detection formation is visualized on the main screen. The toggle defaults ON for a fresh Corpus's first run and is freely switchable afterward.
FR8: User can submit a natural-language question via a chat-style interface.
FR9: User can explicitly select Local Search via a UI toggle; system answers the query using Local Search (Entity-neighborhood traversal).
FR10: User can explicitly select Global Search via a UI toggle; system answers the query using Global Search (Community-summary aggregation).
FR11: The generated answer is displayed in the chat interface once retrieval and generation complete.
FR12: System captures a structured, ordered Retrieval Trace (Entities, Relationships, Communities touched, in order) during query execution.
FR13: User can play back a captured Retrieval Trace as a step-by-step visualization after the answer is generated, with controls to move forward and backward through the steps.
FR14: The application and Neo4j can be started via a single Docker Compose command.
FR15: The OpenAI API key is supplied via an environment variable at startup; no in-app configuration UI is required for v1.
FR16: The main screen's own graph canvas is pannable and zoomable at all times — no separate page or mode is needed to reach this capability. *(Updated 2026-09-20: originally a dedicated Explore page reached via a persistent link/tab; merged into the main screen since it duplicated a slightly more capable version of the same canvas behind a second page.)*
FR17: User can click any Entity on the main screen's graph canvas, at any time, to see its connections (Relationships), details, and Tags.
FR18 (v1.1): User can explicitly select DRIFT via the mode choice; system runs a Community-summary pass, spawns targeted Local Search sub-questions, re-ranks, and synthesizes a final answer.
FR19 (v1.1): System chunks and embeds the ingested Corpus into a vector index, independent of Entity/Relationship extraction.
FR20 (v1.1): User can trigger a Vector Baseline answer for a question already asked via GraphRAG, on demand via a "Compare with Vector Search" action — never automatic.
FR21 (v1.1): The Vector Baseline's pipeline (chunking, embedding, query embedding, similarity ranking, synthesis) is captured as its own Vector Trace, replayable step-by-step with the same transport controls as the existing Retrieval Trace.
FR22 (v1.1): Corpus chunk embeddings are visualized as a 2D-projected scatter (settled once at ingestion, stable across questions); a query's embedding is plotted live with its top-k nearest chunks highlighted and scored.
FR23 (v1.2): The Corpus is split into overlapping Text Units (~1–2k tokens); Entities and Relationships are extracted with one LLM call per Text Unit, against a fixed Entity type list, and ingestion progress reports each Text Unit as it completes.
FR24 (v1.2): Each Entity and Relationship carries an LLM-written description and the ids of the Text Units it was extracted from; the Entity detail panel shows both.
FR25 (v1.2): Mentions of the same Entity across Text Units (differing in case, punctuation, whitespace, or extracted type) resolve to one Entity node.
FR26 (v1.2): Each Community's summary is written from its members' descriptions and internal Relationships, not member names alone.
FR27 (v1.2): Local, Global, and DRIFT answers are written by the LLM from the retrieved context and cite the Text Units they rely on; every citation was recorded in that answer's Retrieval Trace.
FR28 (v1.2): Local, Global, and DRIFT Search find their starting Entities/Communities by embedding similarity to the question, with keyword matching as the offline fallback.

### NonFunctional Requirements

NFR1 (UI tone): The interface reads as modern and minimalist, running entirely in the browser with minimal setup friction. Full visual/interaction spec lives in DESIGN.md/EXPERIENCE.md.
NFR2 (Reliability, deliberately bounded): No automatic retry or cached fallback for LLM call failures — a failure must always surface as a clear, visible error state, never a crash or an indefinite hang.
NFR3 (Single-user, local-only): No authentication, hosting, or multi-tenancy for v1; the app runs on a single developer machine.
NFR4 (Provider flexibility): The LLM integration must not hardcode assumptions that would block swapping the LLM provider later.

### Additional Requirements

- No named scaffolding/starter template beyond the framework choice itself: greenfield Maven multi-module Java project (Spring Boot 4.1.x on Java 25 LTS). Relevant to Epic 1 Story 1 (project setup).
- Hexagonal Architecture module boundaries: `graphrag-core` (domain + use cases + ports, zero framework dependency), `graphrag-adapter-neo4j`, `graphrag-adapter-langchain4j`, `graphrag-adapter-parsing`, `graphrag-web` (AD-1).
- Neo4j access exclusively via the plain Neo4j Java Driver + hand-written Cypher — Spring Data Neo4j is never used (AD-2).
- All LLM access goes through an application-defined `LlmPort`, implemented by a single LangChain4j-based adapter (AD-3).
- Relationships fed into GDS Leiden community detection are projected as `UNDIRECTED` (AD-4).
- Entities are deduplicated via Cypher `MERGE` keyed on a normalized identity (lowercased name + type), never blind `CREATE` (AD-10).
- Communities are written as first-class `(:Community {id, summary})` nodes related to member Entities via `[:BELONGS_TO]` — not a scalar property on Entity (AD-11).
- `DetectCommunities` runs automatically and asynchronously immediately after Knowledge Graph construction completes, unconditionally, and also generates + persists each Community's summary as part of that same run; the visualization toggle only gates client-side animation and is unknown to the backend (AD-6).
- Document parsing (plain text, PDF via Apache PDFBox) sits behind `DocumentParserPort`, with each adapter exposing its own `supports(filename)` dispatch — no Spring `@Qualifier` wiring in `graphrag-web` (AD-9).
- Live ingestion/construction/detection progress is pushed via a single multiplexed Server-Sent Events stream per Corpus (`GET /api/corpora/{corpusId}/progress`), named events with a `{"type", "data"}` envelope — WebSocket is not used anywhere (AD-7, AD-12).
- Query is `POST /api/corpora/{corpusId}/query` with `{question, mode}`; responses distinguish a successful answer, a "no answer found" result, and an LLM-failure error — each its own shape (AD-13).
- Query reads never lock against or wait for in-flight ingestion writes; each Entity/Relationship/Community write commits its own Neo4j transaction (AD-14).
- Retrieval Traces are transient, in-memory, addressed by a UUID `traceId` returned with the answer, fetched via `GET /api/traces/{traceId}`, and are an ordered sequence of steps (AD-5).
- Deployment is exactly two Docker Compose services: `app` (Spring Boot) and `neo4j` (Community Edition with the GDS plugin enabled) — no separate frontend container (AD-8).
- Frontend is Java-native: Thymeleaf server-rendered page shell plus plain, unbundled JavaScript (Cytoscape.js) for the interactive graph canvas — no Node/npm/bundler toolchain anywhere in the project (AD-15).
- Stack: Java 25 (LTS), Spring Boot 4.1.x (Spring Framework 7), Thymeleaf, Neo4j 2026.x Community Edition + GDS plugin, Neo4j Java Driver (latest at implementation start), LangChain4j (latest at implementation start), Apache PDFBox 3.0.x, Cytoscape.js (current), Maven multi-module build.

### UX Design Requirements

UX-DR1: Implement the "Instrument" design token set exactly as specified in DESIGN.md — light-mode-only palette (paper/panel neutrals, `accent` blue for Local Search, `global` teal for Global Search, `active` warm color for "currently active"), system-sans UI typography with monospace reserved strictly for data/step labels.
UX-DR2: Build the Chat panel component (left rail, 340px width, persistent question/answer thread).
UX-DR3: Build the Local/Global/Drift Search mode choice (restyled 2026-09-20 from segmented tabs to a radio choice of colored dots + labels, positioned directly above the composer) with an inline, plain-language explanatory hint that updates immediately when the mode changes.
UX-DR4: Build the Composer (text input + send button, plain placeholder copy).
UX-DR5: Build the Graph canvas component (faint grid background, uppercase eyebrow state title, legend row listing visible Community names with color swatches).
UX-DR6: Implement node/edge visual states: default node, active (currently-visited) node, previous-step node, default edge, traversed edge, upcoming/dashed edge.
UX-DR7: Implement Community hulls (soft pale ellipses with monospace labels), shown on the main screen's canvas whenever the community-visualization toggle is ON — the single toggle that governs this everywhere (updated 2026-09-20: there is no longer a separate always-on exploration view).
UX-DR8: Build the Step badge overlay shown during Retrieval Trace Replay (step counter + traversed-relationship name).
UX-DR9: Build the Retrieval Trace scrubber: transport buttons (step-back / play-pause / step-forward), a discrete per-step tick track, a step counter, and a plain-language caption of the current step.
UX-DR10: Build the Corpus chip (app-bar pill naming the active Corpus and document count).
UX-DR11: Build the Community-detection visualization toggle, defaulting ON for a fresh Corpus's first run.
UX-DR12: Build the Error banner component for LLM-call or extraction failures (plain-language message, no icon glyphs beyond the warm-color cue).
UX-DR13: Build the Node detail panel (main-screen graph canvas, slides in from the right on node click at any time; Entity heading, connections/Relationships, details, and Tags as chips; coexists with the Retrieval Trace scrubber — opening one never closes the other).
UX-DR14: Implement the 5-color categorical Community palette as a best-effort, non-hard-gated colorblind-distinguishability goal.
UX-DR15: Implement the single continuous main-screen Information Architecture — no gated setup wizard; the empty state doubles as the Corpus-upload/Demo-Dataset-selection screen.
UX-DR16: *(Superseded 2026-09-20)* Originally specified a separate Explore page as a second surface, reached via a persistent link/tab. Merged into the main screen's own always-interactive canvas — see UX-DR5/UX-DR7/UX-DR13. There is exactly one surface for graph interaction now.
UX-DR17: Implement every State Pattern from EXPERIENCE.md: idle/empty, upload-rejected, ingestion-in-progress, community-detection-in-progress, LLM-call-failure, no-answer-found, populated/answered, and the main screen's before-any-node-selected state.
UX-DR18: Implement the Voice and Tone microcopy patterns exactly as specified (tutorial-clear language, Glossary terms named consistently every time, the idle-state differentiation subtitle).
UX-DR19: Implement Interaction Primitives: click-a-node (main-canvas detail panel vs. Replay highlight), immediate-effect toggle switches with no confirmation step, scrubber drag/step, and the equally-weighted upload-vs-Demo-Dataset choice.
UX-DR20: Implement the Accessibility Floor: basic contrast floor for text/labels, basic keyboard reachability for primary actions (submit question, toggle Local/Global, toggle community visualization, scrubber play/pause/step).
UX-DR21: Implement the three Key Flows end-to-end as testable journeys: UJ-1 (Explaining GraphRAG live), UJ-2 (Setting up before a stream), UJ-3 (Freely exploring the Knowledge Graph).

### FR Coverage Map

FR1: Epic 2 - Upload plain text files as a Corpus
FR2: Epic 2 - Upload PDF files as a Corpus
FR3: Epic 2 - Select the built-in Sherlock Holmes Demo Dataset
FR4: Epic 2 - Extract Entities/Relationships into Neo4j via live LLM calls, with live progress
FR5: Epic 2 - Surface extraction failures visibly
FR6: Epic 4 - Detect Communities and generate their summaries
FR7: Epic 4 - Toggle community-formation visualization (defaults ON first run)
FR8: Epic 3 - Submit a query via chat interface
FR9: Epic 3 - Answer via Local Search
FR10: Epic 4 - Answer via Global Search
FR11: Epic 3 - Render the final answer in chat
FR12: Epic 5 - Capture the Retrieval Trace
FR13: Epic 5 - Replay the Retrieval Trace
FR14: Epic 1 - One-command infrastructure setup
FR15: Epic 1 - API key via environment variable
FR16: Epic 6 - Explore the full Knowledge Graph
FR17: Epic 6 - Inspect an Entity's details
FR18: Epic 7 - Answer via DRIFT Search
FR19: Epic 8 - Build the vector index
FR20: Epic 8 - Answer via Vector Baseline, on demand
FR21: Epic 8 - Capture and replay the Vector Trace
FR22: Epic 8 - Visualize the embedding space
FR6 (re-delivered, v1.2): Epic 14 - Real GDS Leiden clustering (v1 shipped connected components)
FR11 (re-delivered, v1.2): Epic 15 - LLM-generated answers (v1 shipped templated sentences)
FR23: Epic 13 - Per-passage extraction with live per-passage progress
FR24: Epic 13 - Descriptions and source passages for Entities/Relationships
FR25: Epic 13 - Entity resolution across passages
FR26: Epic 14 - Community summaries from content
FR27: Epic 15 - Grounded, cited answers
FR28: Epic 15 - Semantic seed matching

NFR1 (UI tone): Established in Epic 1 (design tokens/shell), enforced across all epics.
NFR2 (Reliability, bounded): Enforced in Epic 2 (extraction failures) and Epic 3 (generation failures).
NFR3 (Single-user, local-only): Enforced in Epic 1 (deployment topology, no auth).
NFR4 (Provider flexibility): Established in Epic 2 (first epic to introduce the LlmPort/LangChain4j adapter boundary).

> **Revised after party-mode review** (Winston, John, Sally, Amelia): Query Interface (Local Search) moved ahead of Community Detection so a working chat loop is demoable one epic sooner — Global Search stays with Community Detection since it structurally depends on Community summaries. Stories 2.4 and 3.1 (old numbering) were each doing too much for one dev session and are split. See `.memlog.md`-equivalent reasoning inline per story.

## Epic List

### Epic 1: Foundation & One-Command Setup
Establishes the project skeleton (Hexagonal module layout, Spring Boot + Thymeleaf shell, base design tokens) and delivers the PRD's own Setup & Deployment capability: anyone can clone the repo, run a single Docker Compose command, and reach the app's empty-state main screen — Neo4j (with the GDS plugin) running alongside it, ready to accept a Corpus. Realizes UJ-2.
**FRs covered:** FR14, FR15

### Epic 2: Corpus Ingestion & Knowledge Graph Construction
Users can bring their own documents (plain text or PDF) or pick the built-in Sherlock Holmes Demo Dataset, and watch a real Knowledge Graph get built in Neo4j via live LLM extraction — with visible, honest errors if a file is rejected or an LLM call fails, never a silent hang. This is the first epic where a user has genuine graph data to show for their input.
**FRs covered:** FR1, FR2, FR3, FR4, FR5

### Epic 3: Query Interface — Ask & Get Answers
Users can ask a natural-language question through a chat interface and get a real, generated Local Search answer — the fastest path from "I have a graph" to "I can talk to it," reachable without waiting on Community detection.
**FRs covered:** FR8, FR9, FR11

### Epic 4: Community Detection, Visualization & Global Search
Building on an ingested Corpus, the Knowledge Graph is automatically clustered into Communities — watchable by default on a first run, toggleable to compare with/without — and once Community summaries exist, users can also ask corpus-wide thematic questions via Global Search.
**FRs covered:** FR6, FR7, FR10

### Epic 5: Retrieval Trace Replay
After an answer arrives, users can scrub back and forth through exactly how it was produced — which Entities, Relationships, and Communities were touched, in order — turning "GraphRAG found an answer" into "here's precisely how."
**FRs covered:** FR12, FR13

### Epic 6: Graph Exploration
Independent of any question, the main screen's own graph canvas is pannable, zoomable, and clickable at all times — Communities follow the same visualization toggle as the rest of the screen, and clicking any Entity opens a detail panel with its connections, details, and Tags, all without navigating to a separate page. *(Updated 2026-09-20: originally a dedicated Explore page; merged into the main screen — see epics.md's Epic 6 section below.)*
**FRs covered:** FR16, FR17

### Epic 7: DRIFT Search *(v1.1)*
A third query mode: a Community-summary pass spawns targeted Local Search sub-questions, re-ranks them, and synthesizes an answer — replayed as a branching tree rather than a flattened linear sequence, so its multi-stage shape is visible, not hidden.
**FRs covered:** FR18

### Epic 8: Vector-RAG Comparison Baseline *(v1.1)*
On demand, from an already-answered question, users can trigger a plain vector-similarity baseline and watch its mechanics step by step — chunking, embedding, query embedding, similarity ranking — in a dedicated Vector Space tab with a 2D embedding scatter, directly comparable to GraphRAG's own trace for the same question.
**FRs covered:** FR19, FR20, FR21, FR22

### Epic 13: Knowledge Graph Quality — Per-Passage Extraction with Descriptions & Provenance *(v1.2)*
The graph is extracted passage by passage against a fixed type list, every Entity and Relationship gets a description and its source passages, and duplicates are resolved — so long documents are covered end to end, the graph visibly grows during ingestion, and viewers can check any Entity against the text.
**FRs covered:** FR23, FR24, FR25 (hardens FR5)

### Epic 14: Real Communities — GDS Leiden & Grounded Summaries *(v1.2)*
Communities come from GDS Leiden instead of connected components, and their summaries are written from members' descriptions and Relationships — topics instead of "whatever happens to be connected".
**FRs covered:** FR6 (re-delivered), FR26

### Epic 15: Grounded, Cited Answers *(v1.2)*
Searches find their starting points by meaning, the LLM writes the answer from the retrieved context, and every answer cites the passages it used — visible in the chat and as their own steps in Replay.
**FRs covered:** FR11 (re-delivered), FR27, FR28

<!-- Repeat for each epic in epics_list (N = 1, 2, 3...) -->

## Epic 1: Foundation & One-Command Setup

Establishes the project skeleton (Hexagonal module layout, Spring Boot + Thymeleaf shell, base design tokens) and delivers the PRD's own Setup & Deployment capability: anyone can clone the repo, run a single Docker Compose command, and reach the app's empty-state main screen — Neo4j (with the GDS plugin) running alongside it, ready to accept a Corpus. Realizes UJ-2.

### Story 1.1: Project Skeleton & Module Boundaries

As a developer,
I want the Maven multi-module project scaffolded with the Hexagonal Architecture boundaries and empty port interfaces defined,
So that all future work has a consistent home and `graphrag-core` stays framework-free from day one (AD-1).

**Acceptance Criteria:**

**Given** a fresh clone of the repository
**When** the project is built
**Then** it contains five Maven modules: `graphrag-core`, `graphrag-adapter-neo4j`, `graphrag-adapter-langchain4j`, `graphrag-adapter-parsing`, `graphrag-web`
**And** `graphrag-core`'s `pom.xml` declares no dependency on Spring, the Neo4j Java Driver, or LangChain4j
**And** `graphrag-core` defines the empty `GraphStorePort`, `LlmPort`, and `DocumentParserPort` interfaces
**And** the project builds successfully with Maven on Java 25
**And** `graphrag-web`'s `pom.xml` and directory layout contain no `package.json`, Node/npm tooling, or JS bundler configuration anywhere in the project — the frontend is Java-native from day one: a `spring-boot-starter-thymeleaf` dependency and a `src/main/resources/static/js/` directory for plain, unbundled JavaScript (AD-15)

### Story 1.2: One-Command Local Environment

As the creator,
I want to start the whole app and Neo4j with a single `docker compose up`,
So that setting up before a stream requires no manual step beyond providing my OpenAI API key (FR14, FR15).

**Acceptance Criteria:**

**Given** Docker and Docker Compose are installed and `OPENAI_API_KEY` is set in the environment
**When** I run `docker compose up`
**Then** exactly two services start: `app` and `neo4j` (AD-8)
**And** the `neo4j` service has the GDS plugin enabled
**And** the `app` service reads the OpenAI API key from the `OPENAI_API_KEY` environment variable, with no in-app configuration UI for it (FR15)
**And** no third service (e.g. a separate frontend dev server) is defined in `docker-compose.yml`

### Story 1.3: Empty-State Main Screen

As the creator,
I want the main screen to load in a resting, empty state once the app is running,
So that opening the app for the first time already shows the right entry point, with no separate setup screen (FR14, UX-DR15).

**Acceptance Criteria:**

**Given** the app is running via Docker Compose and no Corpus has been ingested yet
**When** I navigate to the app's root URL
**Then** the Thymeleaf-rendered page shell loads with the "Instrument" design tokens applied (UX-DR1) — light-mode palette, system-sans typography
**And** the canvas shows the idle-state copy pattern ("Knowledge Graph — Resting") with its eyebrow label
**And** the idle-state subtitle carries the differentiation line from EXPERIENCE.md's Voice and Tone section (UX-DR18)
**And** the page renders correctly with no upload or ingestion logic wired yet (that begins in Epic 2)
**And** body text and control labels read clearly against their surfaces at normal viewing/streaming distance — the basic contrast floor (UX-DR20)

## Epic 2: Corpus Ingestion & Knowledge Graph Construction

Users can bring their own documents (plain text or PDF) or pick the built-in Sherlock Holmes Demo Dataset, and watch a real Knowledge Graph get built in Neo4j via live LLM extraction — with visible, honest errors if a file is rejected or an LLM call fails, never a silent hang. This is the first epic where a user has genuine graph data to show for their input.

### Story 2.1: Upload a Plain Text Corpus

As the creator,
I want to upload one or more plain text files as my Corpus,
So that I can bring my own material into GraphRAG Lens (FR1).

**Acceptance Criteria:**

**Given** the app's empty state
**When** I upload one or more `.txt` files
**Then** the files are queued for Knowledge Graph construction (FR4)
**And** the Corpus chip in the app bar appears, naming the uploaded file(s) (UX-DR10)
**And** if I instead upload a file with an unsupported extension, a clear, visible message is shown (via the Error banner pattern, UX-DR12) rather than the file being silently ignored

### Story 2.2: Upload a PDF Corpus

As the creator,
I want to upload one or more PDF files as my Corpus,
So that I'm not limited to plain text source material (FR2).

**Acceptance Criteria:**

**Given** the app's empty state
**When** I upload one or more PDF files
**Then** the system extracts their text content via Apache PDFBox (`graphrag-adapter-parsing`, AD-9) and feeds it into the same construction pipeline as plain text (FR4)
**And** the Corpus chip appears, naming the uploaded PDF(s) (UX-DR10)
**And** if a PDF yields no extractable text (e.g. scanned/image-only), a clear, visible error is shown rather than a silent no-op — OCR is explicitly out of scope

### Story 2.3: Use the Built-in Demo Dataset

As the creator,
I want to select the built-in Sherlock Holmes Demo Dataset with one click,
So that I have a "no-brainer" way to try the app without preparing my own files (FR3).

**Acceptance Criteria:**

**Given** the app's empty state
**When** I click the Demo Dataset option
**Then** the bundled Sherlock Holmes corpus is queued for Knowledge Graph construction (FR4), identically to an uploaded Corpus
**And** the Corpus chip appears, naming it "Sherlock Holmes — Demo Dataset" (UX-DR10)
**And** this option is presented with equal visual weight to the upload option, not as a secondary/fallback choice (UX-DR19)

### Story 2.4: Extract Entities and Relationships into the Knowledge Graph

As the creator,
I want the system to extract Entities and Relationships from my Corpus via live LLM calls and write them into Neo4j,
So that I have a real, non-scripted Knowledge Graph to demonstrate and query (FR4).

**Acceptance Criteria:**

**Given** a Corpus has been queued (from Story 2.1, 2.2, or 2.3)
**When** construction runs
**Then** the system calls `LlmPort` (implemented by the LangChain4j/OpenAI adapter, AD-3) to extract Entities and Relationships, never referencing LangChain4j or OpenAI types outside that one adapter
**And** each Entity is written via Cypher `MERGE` keyed on a normalized identity (lowercased name + type), never a blind `CREATE`, so the same entity mentioned twice resolves to one node (AD-10)
**And** the LLM call is genuinely live for this run — no cached or pre-computed extraction is substituted

### Story 2.5: Show Live Ingestion Progress

As the creator,
I want to watch the graph canvas grow with nodes and edges as extraction happens,
So that ingestion feels real and observable rather than a black-box wait (FR4).

**Acceptance Criteria:**

**Given** Knowledge Graph construction (Story 2.4) is running
**When** an Entity or Relationship is written to Neo4j
**Then** a named Server-Sent Event is pushed on `GET /api/corpora/{corpusId}/progress` (AD-7, AD-12)
**And** the graph canvas renders the corresponding node/edge appearing live (UX-DR5)
**And** this progress-plumbing story is independently demoable (e.g. via a heartbeat event) without depending on every extraction edge case in Story 2.4 being finished first

### Story 2.6: Surface Extraction Failures Visibly

As the creator,
I want a clear, visible error if an LLM call fails during Knowledge Graph construction,
So that a failure is honest and obvious rather than a silent hang or crash (FR5).

**Acceptance Criteria:**

**Given** Knowledge Graph construction is in progress
**When** an LLM call fails (e.g. rate limit, API error)
**Then** an `error` Server-Sent Event is emitted on the Corpus's progress stream (AD-12)
**And** the frontend displays the Error banner component (UX-DR12) naming what failed, in plain language
**And** the system does not automatically retry or substitute cached/canned output (NFR2) — this is a deliberate, accepted risk, not an oversight

## Epic 3: Query Interface — Ask & Get Answers

Users can ask a natural-language question through a chat interface and get a real, generated Local Search answer — the fastest path from "I have a graph" to "I can talk to it," reachable without waiting on Community detection (Epic 4).

### Story 3.1: Submit a Question via Chat

As the creator,
I want a chat-style interface where I can type a question,
So that I can ask GraphRAG Lens about my ingested Corpus (FR8).

**Acceptance Criteria:**

**Given** a Corpus has been ingested (Epic 2)
**When** I type a question into the Composer and submit it
**Then** the question appears in the Chat panel's message thread, alongside the Local/Global Search mode toggle with its inline, plain-language hint (UX-DR2, UX-DR3, UX-DR4)
**And** the request is sent as `POST /api/corpora/{corpusId}/query` with body `{"question": "...", "mode": "LOCAL" | "GLOBAL"}` (AD-13) — the Global option is wired up but not answerable until Epic 4
**And** submitting the question is reachable via keyboard alone, without requiring precise mouse interaction (accessibility floor, UX-DR20)

### Story 3.2: Answer via Local Search

As the creator,
I want to explicitly select Local Search and get an answer via entity-neighborhood traversal,
So that I can demonstrate targeted, entity-specific retrieval (FR9).

**Acceptance Criteria:**

**Given** I have the Local/Global Search toggle set to Local (UX-DR3) and I submit a question
**When** `AnswerLocalSearch` runs
**Then** it reads whatever Knowledge Graph state is currently committed, without waiting for or locking against any in-flight ingestion (AD-14)
**And** on success, the response is `{"answerId", "traceId", "answer"}` (AD-13)
**And** if neighborhood traversal from the matched Entities yields nothing relevant, the response is instead the distinct `{"answerId", "traceId", "noAnswer": true, "reason"}` shape — never the generic error shape (AD-13, FR9 consequence)

### Story 3.3: Render the Final Answer in Chat

As the creator,
I want the generated answer displayed in the chat once it's ready, tagged with which search mode produced it,
So that I immediately see the result alongside my question, and know how it was produced (FR11).

**Acceptance Criteria:**

**Given** a query (Story 3.2) has completed successfully
**When** the answer is returned
**Then** it renders in the Chat panel's message thread, tagged with its search mode (`{components.message-answer}`, UX-DR2)
**And** a Replay CTA appears, offering the step-by-step Retrieval Trace (leads into Epic 5)
**And** if the LLM call fails during answer generation itself, the same `{"error": "..."}` shape and Error banner used for extraction failures (Story 2.6) apply here too, with no automatic retry (NFR2, extending FR5's principle to generation failures)
**And** this rendering is mode-agnostic, so Epic 4's Global Search (Story 4.4) reuses it without changes

## Epic 4: Community Detection, Visualization & Global Search

Building on an ingested Corpus, the Knowledge Graph is automatically clustered into Communities — watchable by default on a first run, toggleable to compare with/without — and once Community summaries exist, users can also ask corpus-wide thematic questions via Global Search.

### Story 4.1: Detect Communities in the Knowledge Graph

As the creator,
I want the system to automatically cluster the Knowledge Graph into Communities,
So that Community structure exists for visualization, Global Search, and exploration (FR6).

**Acceptance Criteria:**

**Given** Knowledge Graph construction (Story 2.4) has completed for a Corpus
**When** community detection runs
**Then** it starts automatically and asynchronously, unconditionally — never gated by any UI toggle state (AD-6)
**And** relationships fed into the GDS Leiden call are projected as `UNDIRECTED` (AD-4)
**And** each detected Community is written as a first-class `(:Community {id})` node related to its member Entities via `[:BELONGS_TO]` — never a scalar property on Entity (AD-11)

### Story 4.2: Generate and Persist Community Summaries

As the creator,
I want each Community to have a generated summary immediately after detection,
So that Global Search (Story 4.4) can answer from precomputed summaries rather than generating one per query (FR6).

**Acceptance Criteria:**

**Given** Communities have been detected (Story 4.1)
**When** summary generation runs, as part of the same unconditional pipeline
**Then** each Community's summary is generated via `LlmPort` and persisted onto its `(:Community)` node
**And** `AnswerGlobalSearch` (Story 4.4) only ever reads this summary — it never generates one on demand (AD-6)

### Story 4.3: Toggle Community Formation Visualization

As the creator,
I want a toggle on the main screen controlling whether I see the community-formation animation, defaulting on for a Corpus's first run,
So that the signature "communities folding into clusters" moment plays automatically for a first-time viewer, while I can still turn it off afterward to demonstrate "with vs. without" (FR7).

**Acceptance Criteria:**

**Given** a fresh Corpus is being ingested for the first time
**When** community detection (Story 4.1) begins
**Then** the community-visualization toggle defaults to ON, and the frontend animates Communities visibly folding into hulls as the detection progress events arrive (UX-DR7, UX-DR11)
**And** when I switch the toggle OFF, the animation and hull overlay stop being shown, but detection continues unaffected underneath
**And** toggling the switch never re-runs detection, and the backend has no knowledge of the toggle's state at all — the same progress events are emitted regardless (AD-6)
**And** the toggle is reachable and operable via keyboard alone (accessibility floor, UX-DR20)

### Story 4.4: Answer via Global Search

As the creator,
I want to explicitly select Global Search and get an answer aggregated from Community summaries,
So that I can demonstrate corpus-wide, thematic retrieval as distinct from Local Search (FR10).

**Acceptance Criteria:**

**Given** I have the Local/Global Search toggle (Story 3.1, UX-DR3) set to Global and I submit a question
**When** `AnswerGlobalSearch` runs
**Then** it reads the Community summaries generated and persisted in Story 4.2 — it never generates a summary on demand (AD-6)
**And** on success, the response uses the same `{"answerId", "traceId", "answer"}` shape as Local Search (AD-13), rendered by the existing chat UI from Story 3.3 with no changes needed there
**And** if no Communities exist yet (detection or summary generation hasn't completed), the response is the distinct `{"answerId", "traceId", "noAnswer": true, "reason"}` shape — never the generic error shape (AD-13, FR10 consequence)

## Epic 5: Retrieval Trace Replay

After an answer arrives, users can scrub back and forth through exactly how it was produced — which Entities, Relationships, and Communities were touched, in order — turning "GraphRAG found an answer" into "here's precisely how."

### Story 5.1: Capture the Retrieval Trace

As the creator,
I want the system to capture an ordered record of which Entities, Relationships, and Communities were touched while answering a query,
So that I can later show exactly how that answer was produced, not just that it was (FR12).

**Acceptance Criteria:**

**Given** a query (Story 3.2 or 4.4) is executing
**When** Local Search or Global Search touches an Entity, Relationship, or Community
**Then** that touch is appended as one step to an ordered, in-memory Retrieval Trace — never an unordered set (AD-5)
**And** the trace is addressed by a UUID `traceId`, generated when the answer is produced and returned alongside it
**And** the trace is never persisted to Neo4j and is fetchable only via `GET /api/traces/{traceId}` (AD-5)

### Story 5.2: Replay the Retrieval Trace

As the creator,
I want to play back a captured Retrieval Trace step by step, with controls to move forward and backward,
So that I can show, live, exactly how GraphRAG arrived at an answer (FR13).

**Acceptance Criteria:**

**Given** an answer with a Replay CTA (Story 3.3) is showing in chat
**When** I click the Replay CTA
**Then** the Retrieval Trace scrubber appears below the graph canvas, fetched via `GET /api/traces/{traceId}` (UX-DR9)
**And** play/pause autoplays through the steps, step-forward/step-back move exactly one step per press, and dragging the scrubber head jumps to the nearest discrete step
**And** each step highlights the relevant node/edge on the canvas (active, previous-step, and traversed/upcoming edge states, UX-DR6) alongside a plain-language step-badge caption (UX-DR8)
**And** Replay is available only after generation completes — there is no live/streaming visualization of retrieval as it happens (explicitly out of scope for v1)
**And** the play/pause and step-forward/step-back transport controls are reachable and operable via keyboard alone (accessibility floor, UX-DR20)

## Epic 6: Graph Exploration

*(Updated 2026-09-20: originally a dedicated Explore page reached via a persistent link/tab; merged into the main screen since it duplicated a slightly more capable version of the same canvas — pan/zoom plus a node-click detail panel — behind a second page and a second navigation step. Both stories below are rewritten to match; their intent, FR coverage, and read-only scope are otherwise unchanged.)*

Independent of any question, the main screen's own graph canvas is pannable, zoomable, and clickable at all times — Communities follow the same visualization toggle as everywhere else on that screen, and clicking any Entity opens a detail panel with its connections, details, and Tags, all on the one surface.

### Story 6.1: Explore the Knowledge Graph on the Main Screen

As the creator,
I want the main screen's own graph canvas to be pannable and zoomable at all times, with Communities visualized the same way as everywhere else on that screen,
So that I can browse the graph's actual structure, independent of any specific question, with no separate page to navigate to (FR16).

**Acceptance Criteria:**

**Given** a Corpus has produced any graph, even a partial one mid-ingestion
**When** I interact with the main screen's canvas
**Then** it is pannable and zoomable at all times, queried via `graphrag-adapter-neo4j` (AD-2) as part of the graph the canvas already holds — no separate page, link, or navigation step is needed to reach this capability
**And** Community hulls follow the same community-visualization toggle as the rest of the main screen (FR-7, UX-DR7) — there is no separate always-on exploration view
**And** if no Corpus has been ingested yet, the canvas shows the main screen's own idle/empty state, rather than a blank or broken canvas

### Story 6.2: Inspect an Entity's Details on the Main Screen

As the creator,
I want to click any Entity on the main screen's graph canvas, at any time, to see its connections, details, and Tags,
So that I can understand any part of the graph on demand, without asking a question and without leaving the main screen (FR17).

**Acceptance Criteria:**

**Given** the main screen's graph canvas is showing any Knowledge Graph
**When** I click an Entity node — during ingestion, mid-Replay, or idle
**Then** a detail panel slides in from the right, showing that Entity's Relationships, type/details, and Tags as chips (UX-DR13)
**And** clicking the same node again closes it; clicking a different node swaps the panel's contents directly, no separate close step needed
**And** clicking a Community hull is a distinct action (focuses/fits that Community) and never opens or closes the detail panel
**And** the panel coexists with the Retrieval Trace Replay scrubber on the same canvas — opening one never closes the other
**And** editing an Entity, its Relationships, or its Tags is not possible — this is a read-only view for v1

## Epic 7: DRIFT Search *(v1.1)*

A third query mode alongside Local and Global Search, added post-MVP via a sprint-change proposal. A Community-summary pass spawns targeted Local Search sub-questions, re-ranks them, and synthesizes an answer — replayed as a branching tree, not flattened, so the multi-stage shape stays visible.

### Story 7.1: Add DRIFT to the Mode Choice & Query Contract

As the creator,
I want a third "Drift" option on the Local/Global mode choice, with its own explanatory hint and color,
So that DRIFT is selectable and demonstrable exactly like the other two modes (FR18, AD-18).

**Acceptance Criteria:**

**Given** the mode choice directly above the composer (Story 3.1, restyled 2026-09-20 to a radio-style choice of colored dots + labels — `DESIGN.md` `components.mode-choice`, not the earlier segmented-tab `mode-toggle`)
**When** I select Drift
**Then** its dot fills and its label recolors to the Drift accent (`{colors.drift}` = Rose `#C0225F`, `components.mode-choice.active-drift-dot`/`active-drift-foreground`) — consistent with how Local and Global already render as filled dot + colored label, not a filled background segment
**And** the inline hint updates to "DRIFT runs a community pass, spawns targeted sub-questions, then re-ranks and synthesizes."
**And** the query request extends to `{"question": "...", "mode": "LOCAL" | "GLOBAL" | "DRIFT"}` (AD-18)

### Story 7.2: Implement AnswerDriftSearch

As the creator,
I want the system to answer a DRIFT-mode query by running a Community pass, spawning Local Search sub-questions, re-ranking, and synthesizing,
So that I can demonstrate a third, hybrid retrieval mechanism (FR18).

**Acceptance Criteria:**

**Given** the Drift mode is selected and I submit a question
**When** `AnswerDriftSearch` runs
**Then** it reads existing Community summaries (Story 4.2) to select candidate communities, generates targeted sub-questions from them via `LlmPort`, answers each via the existing `AnswerLocalSearch` logic, re-ranks the results, and synthesizes one final answer
**And** on success, the response uses the same `{"answerId", "traceId", "answer"}` shape as Local/Global (AD-13/AD-18)
**And** if the Community pass yields no viable sub-questions, the response is the distinct `{"answerId", "traceId", "noAnswer": true, "reason"}` shape, naming DRIFT specifically (FR18 consequence)

### Story 7.3: Capture the DRIFT Trace

As the creator,
I want the system to capture DRIFT's multi-stage retrieval as an ordered trace, including each spawned sub-question,
So that its branching shape can later be replayed, not just its final answer (FR18, AD-18).

**Acceptance Criteria:**

**Given** `AnswerDriftSearch` (Story 7.2) is executing
**When** the Community pass completes and sub-questions are spawned
**Then** a "sub-question-spawned" step is appended to the trace for each one, carrying its question text and parent community (AD-18's new step kind)
**And** each sub-question's own Local Search steps are appended in order beneath it, followed by a final re-rank/synthesize step
**And** the whole sequence remains one ordered trace, addressed by a single `traceId` (AD-5, AD-18)

### Story 7.4: Replay the DRIFT Trace as a Branching Tree

As the creator,
I want to replay a DRIFT answer as a branching tree — community pass, fanned sub-questions, convergence — rather than a flattened line,
So that the audience sees DRIFT's actual multi-stage shape, not just a longer version of Local Search's replay (FR18, UX `components.drift-tree`).

**Acceptance Criteria:**

**Given** a DRIFT answer's Replay CTA is clicked
**When** the trace (Story 7.3) is fetched
**Then** the canvas renders the community-pass root, a fan of branch lines to each sub-question node, and a converging final node (DESIGN.md `components.drift-tree`)
**And** the existing transport controls (step-forward/back, play/pause, scrubber-drag-to-nearest-tick) step through the tree in one fixed traversal order: community pass → each branch in spawn order → convergence
**And** a branch not yet reached renders with the existing "upcoming" edge treatment; a resolved branch keeps a small "✓ resolved" caption once passed

## Epic 8: Vector-RAG Comparison Baseline *(v1.1)*

A deliberately plain vector-similarity baseline, triggered on demand from an already-answered question, so its mechanics — what gets vectorized, what the query looks like, what gets retrieved — are watchable step-by-step next to GraphRAG's own trace, for direct comparison. Illustrative only; not a scored benchmark.

### Story 8.1: Build the Vector Index Alongside the Knowledge Graph

As the creator,
I want the ingested Corpus chunked and embedded into a vector index, independent of Entity/Relationship extraction,
So that a plain vector-similarity baseline exists to compare against GraphRAG (FR19, AD-17).

**Acceptance Criteria:**

**Given** a Corpus has been queued (Epic 2)
**When** `ConstructVectorIndex` runs alongside `IngestCorpus`
**Then** the Corpus is chunked, each chunk is embedded via `EmbeddingPort` (implemented by `graphrag-adapter-langchain4j`, AD-17), and persisted via `VectorStorePort` (implemented by `graphrag-adapter-neo4j`'s native vector index, AD-17 — no new container)
**And** a 2D projection of the chunk embeddings is computed once during this step and persisted alongside them (AD-17) — never recomputed per query
**And** this step does not block or gate Entity/Relationship extraction, and vice versa

### Story 8.2: Implement AnswerVectorBaseline

As the creator,
I want a plain top-k similarity search and synthesis over the vector index,
So that I have a genuine (not simulated) vector-RAG answer to compare against GraphRAG's (FR20).

**Acceptance Criteria:**

**Given** the vector index (Story 8.1) exists for a Corpus
**When** `AnswerVectorBaseline` runs for a question
**Then** it embeds the query via `EmbeddingPort`, retrieves the top-k most similar chunks via `VectorStorePort`, and synthesizes an answer via `LlmPort` from those chunks alone — no graph traversal, no Community summaries
**And** the response is a separate, independent result from any GraphRAG answer — its own `answerId`/`traceId` (AD-18), never merged into the GraphRAG trace it's being compared against

### Story 8.3: Trigger the Vector Baseline On Demand from an Answer

As the creator,
I want a "Compare with Vector Search" action on any already-answered question,
So that the comparison is a deliberate, narrated moment rather than doubling the cost of every query (FR20).

**Acceptance Criteria:**

**Given** an answer with a Replay CTA is showing in chat (any mode: Local, Global, or Drift)
**When** I click the Compare CTA (DESIGN.md `components.compare-cta`)
**Then** `AnswerVectorBaseline` (Story 8.2) runs for that exact question
**And** the Vector Baseline is never triggered automatically alongside the original GraphRAG query — only via this explicit action
**And** on completion, the Vector Space tab (Story 8.5) becomes available next to Knowledge Graph

### Story 8.4: Capture the Vector Trace

As the creator,
I want the Vector Baseline's steps captured as an ordered trace — chunking, each chunk embedded, query embedded, chunks ranked/retrieved, answer synthesized,
So that it can be replayed step-by-step like the Retrieval Trace (FR21, AD-18).

**Acceptance Criteria:**

**Given** `AnswerVectorBaseline` (Story 8.2) is executing
**When** each pipeline stage completes
**Then** a "chunk-retrieved-via-similarity" step is appended for each ranked chunk, carrying its chunk id and similarity score (AD-18's new step kind), alongside steps for query embedding and answer synthesis
**And** the trace is addressed by its own `traceId`, fetchable the same way as any other trace (AD-5)

### Story 8.5: Render the Embedding Space and Replay the Vector Trace

As the creator,
I want a Vector Space tab showing corpus chunks as a 2D scatter, with the query's embedding landing live and its top-k neighbors highlighted and scored,
So that "what got vectorized" and "what was retrieved" are literally visible, not abstract (FR21, FR22, UX `components.embedding-scatter`).

**Acceptance Criteria:**

**Given** a Vector Baseline has been triggered (Story 8.3) and its trace captured (Story 8.4)
**When** I switch to the Vector Space tab (DESIGN.md `components.vector-space-tab`)
**Then** corpus chunk dots render at their settled, ingestion-time 2D positions (Story 8.1) — switching tabs or asking another question never reshuffles this layout
**And** Replay steps the query dot into the scatter, then highlights its top-k nearest chunks with connecting lines and similarity-score labels, using the same transport controls as the Retrieval Trace scrubber
**And** the query dot reuses `{colors.active}` and hit-chunk highlights reuse `{colors.accent}` — no new color vocabulary is introduced for this view (DESIGN.md)

## Epic 9: Demo Credibility & Presenter Safety Hardening

Found during a code-and-content review while drafting the live demo script (`_bmad-output/planning-artifacts/demo-script-review.md` context, GitHub issues #TBD). None of these are new functional requirements from the PRD — they are hardening work that closes gaps between what a live presenter needs and what the app currently guarantees: one still-visible unfinished surface (the Vector Space tab), several live-demo failure modes with no safety net, and a couple of stories the sprint tracker never formally closed.
**FRs covered:** none directly (hardens FR20–FR22 and NFR2/NFR3's existing intent)

### Story 9.1: Add a Demo-Safe Offline Mode

As the presenter,
I want an explicit, opt-in way to load a pre-baked corpus (graph, communities, and traces) without making live OpenAI calls,
So that a flaky network or an API rate limit can't take down a live demo, without violating the app's "never silently fall back" principle (NFR2).

**Acceptance Criteria:**

**Given** the empty state
**When** I choose the offline/demo-safe option instead of Upload or the live Demo Dataset
**Then** a pre-baked Sherlock Holmes graph, its communities/summaries, and a small set of pre-captured Retrieval Traces load without any live LLM or embedding call
**And** the option is visually and copy-wise distinct from the live Demo Dataset option, so it is never mistaken for a live run
**And** Local, Global, DRIFT, and Vector Baseline queries against this pre-baked corpus are disabled or clearly marked as replay-only, never silently answered by a live call

### Story 9.2: Improve Local Search Entity-Matching Robustness

As the creator,
I want `AnswerLocalSearch`'s entity matching to tolerate minor phrasing differences and to surface which entities it matched,
So that a slightly-off question phrasing doesn't silently fall through to "no answer found" mid-demo (hardens FR9).

**Acceptance Criteria:**

**Given** a question that references an entity by a close-but-not-exact form of its extracted name
**When** `AnswerLocalSearch` runs
**Then** matching tolerates common variations (case, partial name, simple synonyms) before falling back to "no answer found"
**And** the response or trace exposes which entity/entities were matched from the question, so a presenter can see why an answer did or didn't ground

### Story 9.3: Warn on or Persist Corpus and Trace State Across Restarts

As the creator,
I want either persisted corpus/trace state or a clear warning that a restart clears it,
So that an app restart mid-stream doesn't look like a data-loss bug (hardens NFR3).

**Acceptance Criteria:**

**Given** `CorpusStore` and `RetrievalTraceStore` hold only in-memory state
**When** the `app` container restarts
**Then** either that state survives the restart, or the UI/README explicitly documents that a restart clears corpora and traces (Neo4j's own graph data aside)
**And** this is documented in the README's running instructions, not only in code comments

### Story 9.4: Add Entity Search / Jump-to-Node on the Graph Canvas

As the creator,
I want to search for an entity by name and have the canvas pan/zoom/highlight it,
So that I can point directly at a specific character or concept instead of hunting for it visually on a dense graph (hardens FR16/FR17).

**Acceptance Criteria:**

**Given** the main screen's graph canvas is showing any Knowledge Graph
**When** I type a name-prefix into a new entity-search control
**Then** matching entities are listed, and selecting one pans/zooms the canvas to center it and briefly highlights it
**And** this control is reachable via keyboard alone (accessibility floor, UX-DR20)

### Story 9.5: Badge the Vector Space Tab as In Progress *(Superseded 2026-09-26 — moot: Stories 8.4/8.5 shipped)*

Stories 8.4 and 8.5 both shipped in full (the Vector Trace is captured and the embedding-space scatter/replay is rendered — see Epic 8), so this story's own acceptance criteria ("the badge disappears automatically once Stories 8.4/8.5 ship, with no separate cleanup story needed") is satisfied by never needing the badge in the first place. No badge was built; the Vector Space tab's placeholder copy was replaced by the real feature instead.

As the creator,
I want the Vector Space tab to visibly signal that its scatter/replay is still being built,
So that a viewer gets the same honest signal a developer reading the code already gets, rather than discovering it mid-demo (hardens NFR2's honesty principle for UI, not just backend errors).

**Acceptance Criteria:**

**Given** Stories 8.4 and 8.5 are not yet done
**When** the Vector Space tab is shown
**Then** it carries a small, plain-language "in progress" badge or note near the eyebrow, distinct from the Error banner pattern
**And** the badge disappears automatically once Stories 8.4/8.5 ship, with no separate cleanup story needed

### Story 9.6: Close Out Stories Still Marked "review"

As the team,
we want a final verification pass on every story still tagged `review` in the sprint tracker (2.1, 2.3, 4.3, 4.4, 5.1, 5.2, 6.1, 6.2),
So that "done" in the tracker actually matches what a live demo depends on.

**Acceptance Criteria:**

**Given** the sprint tracker lists these stories as `review`
**When** each is re-verified against its own acceptance criteria on the current `main`
**Then** it is moved to `done`, or its remaining gap is filed as its own story
**And** no story a presenter's script depends on is left indefinitely in `review`

### Story 9.7: Automate Demo Screenshot Capture in CI

As the team,
we want the existing UI test harness extended to capture the demo script's named screenshot moments as build artifacts,
So that the demo script's screenshots stay current for free as the UI evolves, instead of a manual re-capture pass every time.

**Acceptance Criteria:**

**Given** the existing UI tests (`DriftModeChoiceUiTest`, `DriftTreeReplayUiTest`, `MainScreenDetailPanelUiTest`, `ReplayCommunityHullVisibilityUiTest`, `ReplayRelationshipEdgeHighlightUiTest`, `VectorBaselineTriggerUiTest`)
**When** the suite runs in CI
**Then** each named demo-script moment (SCR-1 through SCR-12) is captured as a screenshot artifact, named to match this document's shot list
**And** these artifacts are retrievable from the CI run without re-running the app manually

## Epic 10: Live-Demo UX Fixes & Library Reusability Audit

Found via direct user feedback while using the app (two real bugs), two feature requests, and one audit request — filed as GitHub issues #20-#24. Not a themed epic the way 1-9 are; a grab-bag of independent, user-reported items grouped here only for sprint tracking.
**FRs covered:** none directly (bug fixes and hardening against existing FR16/FR17/FR9/FR10 UX; #24 is a documentation/audit deliverable, not a user-facing FR)

### Story 10.1: Fix Entity Search / Community Toggle Visual Overlap

As the creator,
I want the entity-search box and the community-visualization toggle to never overlap,
So that both controls are always fully visible and clickable independently (GitHub #20).

**Acceptance Criteria:**

**Given** a Corpus is loaded and the Knowledge Graph canvas is showing
**When** both `#entity-search` and `#community-toggle-wrap` are visible
**Then** their rendered bounding boxes never overlap, at any supported viewport width
**And** each control remains independently clickable — a click never lands ambiguously on the other

**Design Notes:** Root cause is CSS positioning (`instrument.css`): both anchor `position: absolute` to the same top-right corner (`.entity-search` at `top: 46px`, `.community-toggle-wrap` at `top: var(--space-3)`), with `.entity-search` at a higher `z-index` (5 vs 2). Fix by stacking vertically with real measured spacing or moving one to a different corner — verify against actual rendered heights, not eyeballed constants.

### Story 10.2: Fix the Progress Stream's False "Disconnected" Banner

As the creator,
I want the progress-stream error banner to never appear after ingestion has already completed successfully,
So that a normal, expected stream-timeout is never mistaken for a real, unrecoverable failure mid-demo (GitHub #21).

**Acceptance Criteria:**

**Given** a Corpus has reached `READY` (ingestion-complete already received)
**When** the underlying SSE connection subsequently closes (timeout or otherwise)
**Then** the "progress stream disconnected" banner is not shown, since there is nothing actually wrong
**And** a genuine disconnect during an in-progress (`BUILDING`) ingestion still shows the existing recovery banner/actions

**Design Notes:** Root cause is `CorpusProgressService.register()`'s hardcoded `new SseEmitter(30_000L)` — a fixed 30-second timeout with nothing ever calling `emitter.complete()` after `ingestion-complete`, so every stream eventually times out and fires the browser's `EventSource.onerror`, which unconditionally shows the banner (`upload.js`) with no auto-clear. Fix via either: (a) server-side `emitter.complete()` right after a terminal event, and/or (b) client-side, suppress the banner once `activeCorpusReady` is already true.

### Story 10.3: Color-Code Entity Tags by Type, Toggleable

As the creator,
I want Entity Tags to be color-coded by type, with a toggle to turn that on or off,
So that entities of different types are visually distinguishable at a glance (GitHub #22).

**Acceptance Criteria:**

**Given** a fresh Corpus's first run
**When** the main screen loads
**Then** the Tag color-coding toggle defaults to ON (resolved 2026-09-27 — matches the Community-visualization toggle's own default-ON pattern)
**Given** the entity detail panel is open for any Entity
**When** the Tag color-coding toggle is ON
**Then** the Tag chip's color is assigned deterministically per distinct type value (same type always renders the same color, across Entities and across sessions)
**And** the same type-color also applies to that Entity's node fill/border on the graph canvas itself (resolved 2026-09-27 — scope extends to the canvas, not just the Tag chip; must be reconciled visually with the existing community-hull and Replay-state colors so none of the three color languages become ambiguous together)
**And** when the toggle is OFF, every Tag chip and every graph node reverts to the current flat neutral style
**And** the toggle is reachable and operable via keyboard alone (existing accessibility floor, UX-DR20)

### Story 10.4: Allow Loading a New Corpus After One Is Already Active

As the creator,
I want to load a different Corpus (upload, live Demo Dataset, or Offline Demo) at any point in the session,
So that I'm not forced into a full page reload to start over (GitHub #23).

**Acceptance Criteria:**

**Given** a Corpus is already active (any workflow state: BUILDING, READY, or FAILED)
**When** I trigger loading a new Corpus
**Then** any open EventSource/Replay is closed, the graph canvas resets to empty, chat thread/mode state resets, and the upload/demo-dataset controls become reachable again — all without a full page reload
**And** the existing "Start over with a new corpus" button (`#workflow-restart-button`), confirmed to currently be a no-op beyond refocusing a hidden file input, either becomes this real affordance or is replaced by one
**And** triggering it first shows a confirmation step ("Loading a new Corpus will discard the current one — continue?") before anything is torn down (resolved 2026-09-27 — switching is destructive to the current graph/trace/chat state, so it is not treated as an immediate-effect action the way UX-DR19's toggles are)

### Story 10.5: Audit graphrag-core's Reusability as a Standalone Library

As the team,
we want a thorough, evidence-based audit of whether `graphrag-core` is genuinely usable by another project — not just architecturally separable — covering documentation, naming, and actual publishability,
So that "usable for other projects" is a verified fact, not an assumption (GitHub #24).

**Acceptance Criteria:**

**Given** the current state of `graphrag-core` (framework-free, enforced by `maven-enforcer-plugin`, but with no module README, no `package-info.java`, inconsistent Javadoc depth, and no publishing setup)
**When** the audit runs
**Then** it produces a findings list (confirmed-solid vs. actually-missing) and a prioritized, concrete punch list
**And** judgment calls it cannot make unilaterally (the `com.graphraglens` branding vs. a generic library identity; whether "reusable" requires actually publishing the artifact) are surfaced as explicit open questions for {user_name}, not decided silently
**And** any resulting work is filed as its own follow-up story/issue once those questions are answered — this story's own scope is the audit and punch list, not the fixes themselves

## Epic 11: Layout, Replay & Graph Navigation Live-Demo Fixes

Found via direct user feedback while using the app (three UI/layout bugs, one confirmed regression, one behavior-clarity investigation, one navigation feature request, and one recurring overlap bug) — filed as GitHub issues #30-#36. Like Epic 10, this is a grab-bag of independent, user-reported items grouped only for sprint tracking, not a themed epic.
**FRs covered:** none directly (bug fixes and hardening against existing FR8-FR13/FR16/FR20-22 UX; #33 is an investigation/clarity deliverable, not a new user-facing FR)

### Story 11.1: Scale the Main Screen to Fill the Viewport

As the creator,
I want the main screen's layout to fill the available browser viewport,
So that the app doesn't render confined to a small region of the window regardless of screen size (GitHub #30).

**Acceptance Criteria:**

**Given** the app is loaded in a browser window of any reasonably supported size
**When** the main screen renders (chat panel + graph canvas)
**Then** the layout fills the viewport's width and height (within sane min/max bounds), instead of leaving large unused margins
**And** the Cytoscape.js graph canvas resizes/re-fits along with its container rather than staying a fixed small box

**Design Notes:** Check `instrument.css` / the Thymeleaf page shell for fixed-width/height containers, and confirm Cytoscape.js is told to resize (`cy.resize()`/`cy.fit()`) on container size changes, not just on initial load.

### Story 11.2: Make the Chat History Scrollable with a Pinned Composer

As the creator,
I want the chat panel's question/answer thread to scroll independently while the search-mode choice and composer stay fixed at the bottom,
So that long chat histories remain navigable without losing access to the input controls (GitHub #31).

**Acceptance Criteria:**

**Given** the chat panel (UX-DR2) contains enough question/answer turns to exceed the visible panel height
**When** I scroll within the chat panel
**Then** only the message thread scrolls, revealing earlier turns
**And** the Local/Global/Drift mode choice (UX-DR3) and the Composer (UX-DR4) remain fixed at the bottom of the panel at all times, never scrolling out of view

**Design Notes:** Restructure the chat panel as a flex column with the thread as the single `overflow-y: auto` region and the mode choice + composer outside that scrolling region.

### Story 11.3: Fix Retrieval Trace Replay Not Rendering or Highlighting

As the creator,
I want the Replay scrubber to reliably appear after every answer and for playback to visibly traverse the graph again,
So that Retrieval Trace Replay works as it did before this regression (GitHub #32).

**Acceptance Criteria:**

**Given** an answer has just been generated with a captured Retrieval Trace
**When** the answer renders
**Then** the Replay scrubber (UX-DR9) always appears, not intermittently
**Given** the Replay scrubber is shown
**When** I step through or play the trace
**Then** the graph canvas visibly transitions node/edge visual states (UX-DR6: active, previous-step, traversed, upcoming) exactly as it did prior to the regression, and the Step badge (UX-DR8) updates in step

**Design Notes:** Root-cause first — bisect recent Epic 9/10 changes to find when this broke, and check whether the same client-side replay path is shared with DRIFT (Epic 7) and Vector (Epic 8) trace replay, since a shared regression would affect all three.

### Story 11.4: Clarify and Verify "Compare with Vector Search" Behavior

As the creator,
I want to confirm what the "Compare with Vector Search" action actually does end-to-end and make its purpose/result self-evident in the UI,
So that the feature isn't a confusing black box during a demo (GitHub #33).

**Acceptance Criteria:**

**Given** an answer has been generated via GraphRAG (Local/Global/Drift)
**When** I trigger "Compare with Vector Search" (FR20)
**Then** the investigation confirms whether the Vector Baseline answer, its Vector Trace (FR21), and the embedding-space visualization (FR22) actually render as designed (Stories 8.2-8.5)
**And** if it works as designed, a small UI affordance (inline explanation or clearer labeling) is added so the action's purpose and result are clear without external documentation
**And** if it is broken or incomplete, the concrete gap is filed as its own follow-up issue rather than patched speculatively here

**Design Notes:** This story's own scope is investigation plus, at most, a labeling/clarity fix — not a rebuild of the Vector Baseline pipeline.

### Story 11.5: Tune Graph Layout So Communities and Nodes Aren't Wildly Far Apart

As the creator,
I want related nodes and their Community to render at a proportionate, readable distance from each other,
So that the graph stays legible instead of communities scattering far apart for no apparent reason (GitHub #34).

**Acceptance Criteria:**

**Given** a Corpus of typical size is loaded with communities detected (Epic 4)
**When** the graph canvas lays out nodes
**Then** nodes belonging to the same Community render within a visually coherent, bounded distance of each other and of their Community hull (UX-DR7)
**And** the layout no longer produces outlier spacing for isolated nodes, small communities, or disconnected subgraphs without a corresponding visual reason (e.g. genuine graph distance)

**Design Notes:** Identify the current Cytoscape.js layout algorithm/config and whether it's Community-aware; tune parameters (or switch layout) once the specific cause of outlier spacing is found — don't guess-and-check blindly.

### Story 11.6: Add Zoom In/Out Controls for the Graph Canvas

As the creator,
I want visible zoom in/out (and fit-to-view) buttons on the graph canvas,
So that navigating the graph feels smooth and doesn't require a mouse wheel (GitHub #35).

**Acceptance Criteria:**

**Given** the graph canvas is showing (FR16: pannable/zoomable at all times)
**When** I look at the canvas
**Then** dedicated zoom-in, zoom-out, and fit-to-view/reset controls are visible and clickable, styled per the "Instrument" design tokens (UX-DR1)
**And** using these controls produces the same zoom behavior as the existing mouse-wheel zoom, with smooth (eased/animated) transitions
**And** mouse-wheel zoom continues to work unchanged alongside the new controls

**Design Notes:** Also review current zoom step size/sensitivity and whether transitions are animated, since "doesn't feel smooth" may be partly a tuning issue independent of the missing UI controls.

### Story 11.7: Make the Community-Formation and Color-Code-Entity-Types Toggles Non-Blocking

As the creator,
I want the community-formation-view and color-code-entity-types checkboxes to never block or overlap the graph canvas,
So that both controls stay usable without obscuring the graph underneath them (GitHub #36).

**Acceptance Criteria:**

**Given** the graph canvas is showing with both the community-formation-visualization toggle (FR7) and the color-code-entity-types toggle (Story 10.3) present
**When** either or both are rendered
**Then** neither control's bounding box overlaps graph content in a way that hides nodes/edges/hulls underneath it
**And** the controls remain independently visible and clickable at any supported viewport width
**And** the solution scales to additional floating controls being added later without requiring another one-off overlap fix (e.g. a collapsible/collapsed-icon state or a docked settings affordance, rather than more absolutely-positioned corner elements)

**Design Notes:** Directly related to the entity-search/community-toggle overlap already fixed in Story 10.1 (#20) — confirm whether this is the same class of bug resurfacing with the color-code-entity-types toggle (Story 10.3) added into the same corner, and fix the underlying layout pattern rather than adding a third one-off position.

## Epic 12: Durable Neo4j Persistence & Corpus History

Found while investigating why the app loses all state on every restart (`spec-neo4j-corpus-persistence`, `_bmad-output/specs/spec-neo4j-corpus-persistence/SPEC.md`). `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter` turned out to be empty compatibility-alias classes over the in-memory adapters — nothing has ever actually talked to the `neo4j` container `docker-compose.yml` already runs. This epic wires real Neo4j-backed persistence for the graph, vectors, and the corpus registry itself, then adds a history/switcher so every previously-ingested corpus survives an app restart and can be reselected. **Supersedes Story 9.3's "warn" resolution** (`9-3-warn-on-or-persist-corpus-and-trace-state-across-restarts`, currently `done` via a documented README warning) — corpus/graph/vector state now actually persists; Retrieval Trace state remains out of scope (spec non-goal) and Story 9.3's warning about trace loss stays accurate. Complements, doesn't duplicate, Story 10.4 (loading a *new* corpus while one is active) — this epic adds reselecting a *previous* one.
**FRs covered:** none directly (delivers `spec-neo4j-corpus-persistence`'s CAP-1–CAP-8; governed by `ARCHITECTURE-SPINE.md`'s AD-2, AD-10, AD-11, AD-16, AD-17, AD-19–AD-23; hardens NFR3's restart-durability gap beyond Story 9.3's warning-only closure)

### Story 12.1: Wire a Real Neo4j Adapter for the Knowledge Graph

As the creator,
I want `GraphStorePort` implemented against the real Neo4j Java Driver instead of the in-memory alias classes,
So that a corpus's Entities, Relationships, Communities, and Community Memberships survive an app restart (CAP-1).

**Acceptance Criteria:**

**Given** a corpus has been ingested and its Knowledge Graph constructed
**When** I inspect Neo4j directly (Browser or a GDS Leiden run) against that `corpusId`
**Then** the same Entity/Relationship/Community/CommunityMembership nodes the app's UI displays are present, using Cypher `MERGE` on the composite keys AD-20 defines — `(corpusId, normalizedIdentity)` for Entity, `(corpusId, id)` for Community, `(corpusId, source, type, target)` for Relationship, `(corpusId, communityId, entityIdentity)` for CommunityMembership — never a blind `CREATE` and never the identity-only key AD-10/AD-11 originally read as
**And** every corpus-scoped `GraphStorePort` method (`persistEntities(corpusId, ...)`, `entities(corpusId)`, etc.) is directly `@Override`-n with real corpus-scoped Cypher; the single-argument unscoped overloads throw `UnsupportedOperationException` rather than silently falling through to unscoped writes (AD-20's reviewer-closed trap)
**And** after restarting the `app` container (Neo4j untouched), that corpus's graph is displayed identically without re-ingesting or re-calling the LLM
**And** `Neo4j2026.08.1`'s GDS Leiden call still runs correctly against `UNDIRECTED`-projected relationships scoped to one `corpusId` (AD-4 unaffected by the scoping change)

**Design Notes:** `graphrag-core` must not gain a Neo4j driver import (AD-1) — all of this lives in `graphrag-adapter-neo4j`. `InMemoryGraphStoreAdapter` stays in the module for the adapter's own unit tests only; it is never wired into the running app after this story (AD-21).

### Story 12.2: Wire a Real Neo4j Adapter for the Vector Index

As the creator,
I want `VectorStorePort` implemented against real Neo4j vector storage instead of the in-memory alias class,
So that a corpus's embedded chunks and fitted 2D projection survive an app restart (CAP-2).

**Acceptance Criteria:**

**Given** a corpus has had its vector index constructed (`ConstructVectorIndex`)
**When** the app restarts
**Then** the Vector Space view and the Vector Baseline query mode for that `corpusId` work identically to before the restart, with no re-embedding
**And** chunk embeddings are stored on `Chunk` nodes MERGE-keyed on `(corpusId, chunk.id)`, indexed by exactly one vector index spanning every corpus (never one index per corpus), filtered by `corpusId` via in-index filtering at query time
**And** the adapter uses the current Cypher `SEARCH` clause against a `LIST<FLOAT>` embedding property — never the deprecated `db.index.vector.queryNodes`/`queryRelationships` procedures, and never assumes Enterprise-only native `VECTOR` typing (AD-17, corrected)
**And** the fitted `ProjectionModel` is stored as one `(:ProjectionModel {corpusId, mean, pc1, pc2})` node per corpus, MERGE-keyed on `corpusId` alone

**Design Notes:** Depends on Story 12.1 only for shared adapter-module scaffolding (Driver bean from Story 12.3); otherwise independently testable.

### Story 12.3: Add Neo4j Connection Configuration with a Fail-Fast Startup Check

As the creator,
I want the app to connect to the `docker-compose` Neo4j service via externally configurable settings, and to fail loudly if it can't,
So that an unreachable Neo4j is an obvious startup error, never a silent degrade back to in-memory behavior (CAP-3).

**Acceptance Criteria:**

**Given** the app is starting up
**When** it builds its Neo4j `Driver` bean
**Then** it uses `org.neo4j.driver:neo4j-java-driver` directly (never `spring-boot-starter-data-neo4j`), configured from `NEO4J_URI` / `NEO4J_USERNAME` / `NEO4J_PASSWORD` env vars (defaults `bolt://neo4j:7687` / `neo4j` / matching `docker-compose.yml`'s existing `NEO4J_PASSWORD` default)
**And** `driver.verifyConnectivity()` runs at `ApplicationReadyEvent`; if it fails, startup aborts with a clear, logged error
**Given** `docker-compose.yml`'s `app` service
**When** this story ships
**Then** it gains the three new env vars, sourced the same way `OPENAI_API_KEY` already is
**And** `ParserConfig`'s `@Bean` methods construct `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter` (wired with this Driver bean) instead of the in-memory classes

**Design Notes:** This is the story that actually flips the switch from "everything in-memory" to "everything Neo4j" — Stories 12.1/12.2 build the adapters, this one wires them in and makes the failure mode visible (AD-21).

### Story 12.4: Replace `CorpusStore` with a Durable Neo4j-Backed Corpus Registry

As the creator,
I want corpus bookkeeping (name, status, timestamps) persisted to Neo4j instead of an in-memory map,
So that the list of corpora I've ingested survives an app restart, not just the graph data itself (CAP-4).

**Acceptance Criteria:**

**Given** `CorpusStore`'s in-memory maps are deleted outright (no cache layer kept in front of Neo4j)
**When** `CorpusController` needs any of `put`/`markReady`/`markFailed`/`get`/`status`/`size`
**Then** it calls a new `Neo4jCorpusRegistry` (a plain Spring-managed class in `graphrag-adapter-neo4j`, **not** a new `graphrag-core` port — AD-19) which persists `CorpusMeta` nodes (`corpusId`, derived name, document filenames, workflow status, `createdAt`, `lastActivatedAt`) MERGE-keyed on `corpusId` alone
**And** `CorpusWorkflowStatus` (`BUILDING`/`READY`/`FAILED`) moves from its current home nested in `CorpusStore.java` to `graphrag-adapter-neo4j`, alongside `Neo4jCorpusRegistry`
**And** demo/offline corpora (`markOffline`, Story 9.1) are the one exception — never written as `CorpusMeta` nodes, held only in a small in-process set exactly as `CorpusStore` held them today, still lost on restart
**And** `lastActivatedAt` is initialized to the same value as `createdAt` at corpus-creation time (never left null), and both timestamps are written from `graphrag-web`'s own `Instant.now()` passed as a Cypher parameter, never Neo4j's server-side `datetime()`
**And** after restarting the app, previously-ingested corpora still appear with their correct name and status without being re-uploaded
**And** AD-16's query gate (`409` on `BUILDING`/`FAILED`) behaves identically to before — only where the status is read from and stored has changed

### Story 12.5: Reconcile Interrupted Corpora to `FAILED` on Startup

As the creator,
I want any corpus still `BUILDING` when the app starts to be automatically marked `FAILED`,
So that a crash mid-ingestion never leaves a corpus permanently stuck with no way to see it failed or retry (CAP-8).

**Acceptance Criteria:**

**Given** a corpus was `BUILDING` when the `app` process died (killed, crashed, or force-stopped)
**When** the app restarts
**Then** an `ApplicationReadyEvent` listener, running immediately after Story 12.3's connectivity check succeeds and before any HTTP traffic is accepted, transitions that corpus's `CorpusMeta` from `BUILDING` to `FAILED`
**And** this transition is one conditional Cypher write per corpus (`MATCH (c:CorpusMeta {corpusId: $id}) WHERE c.status = 'BUILDING' SET c.status = 'FAILED'`) inside Neo4j's own transaction — never a read into the JVM followed by a separate write — so it stays correct even if two `app` containers briefly overlap during a redeploy
**And** the corpus then shows as `FAILED` in the history list (Story 12.7), consistent with any other failed ingestion

### Story 12.6: Add Explicit Corpus Activation and History List Endpoints

As the creator,
I want a `GET /api/corpora` endpoint listing every retained corpus and a `POST /api/corpora/{corpusId}/activate` endpoint,
So that the frontend has what it needs to show corpus history and record which one I'm actively working on (CAP-5/CAP-6, backend half).

**Acceptance Criteria:**

**Given** three corpora have been ingested across two app restarts
**When** I call `GET /api/corpora`
**Then** it returns all three (id, name, status, `createdAt`, `lastActivatedAt`) sourced from `Neo4jCorpusRegistry`, ordered by `lastActivatedAt` descending
**Given** any retained corpus
**When** I call `POST /api/corpora/{corpusId}/activate`
**Then** that corpus's `lastActivatedAt` updates to now, and this is the *only* thing that updates it — query, vector-space, and progress traffic never touch it (AD-22)
**And** no server-side "current active corpus" singleton is introduced — activation is purely a `CorpusMeta` timestamp update, ordering is entirely how the frontend interprets `GET /api/corpora`'s response

### Story 12.7: Build the Corpus History Switcher and Auto-Restore on Load

As the creator,
I want to see every corpus I've ever ingested in a history list, click one to make it active, and have the app reopen on whichever I was last using,
So that I never lose track of past work to a restart and never have to re-ingest to get back to it (CAP-5/CAP-6/CAP-7, frontend half).

**Acceptance Criteria:**

**Given** two or more previously-ingested corpora exist
**When** the main screen loads
**Then** it calls `GET /api/corpora`, renders the results as a selectable history list (name, status, timestamps), and auto-sets `activeCorpusId` to the entry with the most recent `lastActivatedAt` — then calls `POST /api/corpora/{corpusId}/activate` for that same corpus, so opening the app itself counts as activating it
**Given** the history list is showing and a non-active corpus is clicked
**When** the click is handled
**Then** `activeCorpusId` reassigns to it, `POST .../activate` is called for it, and the graph canvas, vector-space view, and query panel all re-render against the newly-selected `corpusId` within the same page load — no full page reload, no re-ingestion
**And** a corpus showing `FAILED` (including one reconciled by Story 12.5) is visibly distinguishable in the list from `READY`/`BUILDING`, consistent with AD-16's existing query gate
**And** demo/offline corpora (Story 9.1) appear in the list only for the current session — never reappearing after a restart, since Story 12.4 never persists them

**Design Notes:** Builds on the existing corpus-chip UI pattern from Story 10.4's "start over" flow — this adds *switching to a previous* corpus alongside that story's *replace with a new* one, not a competing UI.

## Epic 13: Knowledge Graph Quality — Per-Passage Extraction with Descriptions & Provenance

Found while analysing how the graph is actually built (2026-10-01): `OpenAiLlmPort.extract` joins the whole Corpus into **one** prompt and gets back bare `{name, type}` Entities and untyped-description Relationships. On a ~9,000-word document (`demo-corpora/history-of-java.txt`) that samples a few dozen Entities, mostly from the start of the text; the graph then arrives all at once; and nothing links an Entity back to the passage it came from. This epic rebuilds extraction the way reference GraphRAG implementations do: passage by passage (Text Units), against a fixed type list, with a description and source passages for every Entity and Relationship, and with duplicates resolved before they hit Neo4j. It is the foundation Epics 14 and 15 build on — every search mode can only be as good as this graph.
**FRs covered:** FR23, FR24, FR25; hardens FR5 (truncated output). Governed by AD-24, AD-25, AD-26 (plus AD-1, AD-3, AD-10, AD-12, AD-14, AD-20).

### Story 13.1: Extract the Knowledge Graph Passage by Passage

As the presenter,
I want the Corpus split into Text Units and extracted one Text Unit at a time,
So that long documents are extracted evenly from start to end and the audience watches the graph grow passage by passage.

**Acceptance Criteria:**

**Given** a Corpus of one or more documents
**When** Knowledge Graph construction runs
**Then** a core `TextUnitSplitter` (no framework imports, AD-1) splits each document into overlapping Text Units of ~6,000 characters with ~600 characters of overlap, cutting on paragraph or sentence boundaries where one exists, each with a stable `id`, `corpusId`, `documentName`, `ordinal`, and `text`
**And** `ExtractEntitiesAndRelationships` calls a new `LlmPort.extract(TextUnit, List<String> entityTypes)` once per Text Unit, sequentially, persisting each unit's Entities and Relationships before starting the next (AD-24, AD-14)
**And** each Text Unit is persisted as `(:TextUnit {corpusId, id, documentName, ordinal, text})`, MERGE-keyed on `(corpusId, id)` (AD-20)
**And** after each Text Unit the progress stream emits `text-unit-extracted` with `{index, total, documentName}` before that unit's `entity-extracted`/`relationship-extracted` events, and the workflow status line reads "Extracting passage {index} of {total} — {documentName}"
**And** the Entity type list is one constant in `graphrag-core` (Person, Organization, Product, Technology, Version, Event, Location, Concept) and is passed to every `extract` call; any other type returned maps to Concept
**And** the offline stub (`LangChain4jLlmPort`) implements the per-Text-Unit `extract` deterministically, so the Demo Dataset, offline mode, and every existing UI test still pass without network access
**And** if any Text Unit's extraction fails, ingestion stops, the corpus is marked `FAILED`, and the existing error state shows (FR-5) — no partial "best effort" graph
**And** a unit test proves an Entity mentioned only in the last Text Unit of a multi-unit document is extracted

**Design Notes:** The old `LlmPort.extract(Corpus)` stays as a default method that loops over Text Units, so external callers keep compiling. Text Units are independent of the Vector Baseline's 500-character `Chunk`s (AD-17) — do not merge the two splitters.

### Story 13.2: Give Every Entity and Relationship a Description and Its Source Passages

As the presenter,
I want each Entity and Relationship to carry a short description and the ids of the Text Units it came from,
So that searches and summaries have real content to work with, and answers can point back at the text.

**Acceptance Criteria:**

**Given** per-Text-Unit extraction (Story 13.1)
**When** the OpenAI adapter extracts a Text Unit
**Then** its prompt names the fixed Entity type list and asks, per Entity, for `name`, `type`, `description` (one or two sentences), and per Relationship for `source`, `target`, `type`, `description`, using JSON mode
**And** `Entity` gains `description` and `sourceTextUnitIds`, and `Relationship` gains `description`, `sourceTextUnitIds`, and `weight` (number of source Text Units), with the existing two-/five-argument constructors kept as overloads (AD-25)
**And** `Neo4jGraphStoreAdapter` stores these as properties of the existing Entity nodes and relationships, plus one `(:Entity)-[:MENTIONED_IN]->(:TextUnit)` per source unit; the in-memory adapter keeps them in memory
**And** the `entity-extracted` and `relationship-extracted` SSE payloads include `description`
**And** the adapter sets an explicit max-output-token limit, and a response cut off by that limit (finish reason `length`) or that is not valid JSON fails the ingestion visibly, naming the document and passage number in the server log (FR-5)
**And** existing corpora persisted before this story still load and display (missing descriptions read as empty, never as an error)

### Story 13.3: Resolve Duplicate Entities Before They Reach Neo4j

As the presenter,
I want mentions of the same Entity across passages to land on one node,
So that per-passage extraction doesn't fragment the graph into near-duplicates.

**Acceptance Criteria:**

**Given** a Corpus where the same Entity is extracted from several Text Units, with differences in letter case, surrounding punctuation, whitespace, or extracted type
**When** each Text Unit's extraction is persisted
**Then** a core `EntityResolver` (AD-26) maps every extracted Entity onto the Corpus's already-known Entities, comparing names after Unicode normalization, case folding, whitespace collapsing, and stripping surrounding punctuation
**And** a name match with a different type resolves to the existing Entity; across the Corpus the type with the most mentions wins, ties keep the earlier one
**And** Relationship endpoints are rewritten to the resolved identities before persistence
**And** descriptions merge by appending distinct sentences (capped at ~1,000 characters), and source Text Unit ids are unioned; a Relationship extracted again increments its `weight`
**And** AD-10's `MERGE` on `name::type` stays the persistence key
**And** unit tests cover: case/punctuation variants, a type conflict, a Relationship whose endpoint is a variant, and description merging

**Design Notes:** Fuzzy or semantic merging ("Java 8" ≡ "Java SE 8") is explicitly out of scope (AD-26) — keep the rules deterministic and explainable on stage.

### Story 13.4: Show Descriptions and Source Passages in the Entity Detail Panel

As a viewer exploring the graph,
I want an Entity's panel to show what it is and where in the text it came from,
So that I can check the graph against the source.

**Acceptance Criteria:**

**Given** a Corpus ingested with descriptions and provenance (Stories 13.2–13.3)
**When** I click an Entity on the canvas
**Then** the detail panel's existing Description section shows the Entity's description (hidden when empty, as today)
**And** a new "Source passages" section lists each source Text Unit as `{documentName} · passage {ordinal}`, and expanding one shows its text, fetched from a new `GET /api/corpora/{corpusId}/text-units/{textUnitId}` endpoint (404 → "Passage not available")
**And** each Relationship row in the panel shows its description as a tooltip
**And** the help article for the Entity panel (`entity-detail`) mentions descriptions and source passages
**And** a UI test opens an Entity, expands a source passage, and sees its text

---

## Epic 14: Real Communities — GDS Leiden & Grounded Summaries

`DetectCommunities` has always been a BFS over connected components, although FR-6, AD-4, and Story 12.1's acceptance criteria all say GDS Leiden — and the GDS plugin is already installed in `docker-compose.yml`. Connected components give one giant Community for any well-linked area and a separate Community for every isolated pair, which is exactly the scattering seen on the cryptids corpus. Summaries are then written from member *names* only. This epic delivers the clustering the PRD promised and summaries written from the content Epic 13 now provides.
**FRs covered:** FR6 (re-delivered), FR26. Governed by AD-27, AD-28 (plus AD-4, AD-6, AD-11, AD-20).

### Story 14.1: Detect Communities with GDS Leiden

As the presenter,
I want Communities to come from modularity-based Leiden clustering,
So that they reflect the topics of the Corpus rather than which parts happen to be connected.

**Acceptance Criteria:**

**Given** a Corpus whose Knowledge Graph is persisted in Neo4j
**When** `DetectCommunities` runs
**Then** it gets memberships from a new `GraphStorePort.detectCommunities(corpusId)` (AD-27)
**And** `Neo4jGraphStoreAdapter` implements it by projecting only that corpus's Entities and relationships as an `UNDIRECTED` GDS graph weighted by `weight` (AD-4, AD-25), named with the `corpusId` (AD-20), running `gds.leiden.stream` with a fixed `randomSeed`, and dropping the projection in a `finally` even when the call fails
**And** Entities with no relationships each become a single-member Community
**And** the port's default implementation is the existing connected-components algorithm, so the in-memory adapter, offline mode, and existing tests are unchanged
**And** an integration test against the real Neo4j container (with GDS) shows two densely linked groups joined by a single edge splitting into two Communities, where connected components would give one
**And** if GDS is unavailable, ingestion fails visibly (FR-5) rather than silently falling back

### Story 14.2: Write Community Summaries from Their Content

As the presenter,
I want each Community's summary to be written from its members' descriptions and internal Relationships,
So that Global and DRIFT Search match against meaningful summaries and the legend reads like a table of contents.

**Acceptance Criteria:**

**Given** Communities detected over Entities with descriptions (Epic 13, Story 14.1)
**When** summaries are generated (still during detection, AD-6)
**Then** `LlmPort.summarizeCommunity` receives the members with descriptions and the Relationships whose both endpoints are members, with descriptions, capped by highest `weight` first (AD-28)
**And** it returns a short title (≤6 words) and a two-to-four-sentence summary; `Community` gains `title`, persisted on the `(:Community)` node
**And** the canvas legend and hull labels show the title (falling back to the current summary-derived label when the title is empty, e.g. older corpora or the offline stub)
**And** the Entity/Community detail panel shows the full summary
**And** the offline stub returns a deterministic title and summary so existing tests keep passing

---

## Epic 15: Grounded, Cited Answers

Local, Global, and DRIFT Search currently match the question by keyword overlap (`KeywordMatcher`) against Entity names and Community summaries, and answer with a fixed sentence template ("Across the corpus, the strongest signal is that …"). FR-11 promised a *generated* answer. With Epic 13's descriptions and passages and Epic 14's summaries, the searches can retrieve real context, have the LLM write the answer, and cite the exact passages — which the Retrieval Trace can then show step by step. That is the most convincing thing to show a technical audience: "this answer came from these sentences."
**FRs covered:** FR11 (re-delivered), FR27, FR28. Governed by AD-29, AD-30 (plus AD-5, AD-13, AD-17, AD-18).

### Story 15.1: Match Questions to Entities and Communities by Meaning

As the presenter,
I want the searches to find their starting points by semantic similarity,
So that a question works even when it shares no words with an Entity's name.

**Acceptance Criteria:**

**Given** a Corpus ingested with an embedding model configured
**When** extraction and detection complete
**Then** each Entity's `name + description` and each Community's summary is embedded via `EmbeddingPort` and stored as an `embedding` property, with one Neo4j vector index per label (`Entity`, `Community`) spanning all corpora and filtered by `corpusId` (AD-30, AD-17's pattern)
**And** Local Search takes the top 3 Entities by similarity to the embedded question as seeds, and Global/DRIFT take the top 3 Communities, each recorded as trace steps in that order
**And** with no embedding model configured (offline/stub), all three modes fall back to `KeywordMatcher` exactly as today, so existing tests are unchanged
**And** a test shows a question with no word overlap but matching an Entity's description finding that Entity

### Story 15.2: Synthesize Cited Local Search Answers

As the presenter,
I want Local Search answers written by the LLM from the retrieved neighbourhood, with citations,
So that the answer is real and the audience can see which passages it used.

**Acceptance Criteria:**

**Given** Local Search seeds (Story 15.1 or keyword fallback)
**When** a Local Search question runs
**Then** `AnswerLocalSearch` assembles context — seed Entities with descriptions, their one-hop Relationships with descriptions, and the Text Units those cite (capped, highest `weight` first) — recording each item as a trace step as it is added, including a new `TEXT_UNIT` step kind carrying the Text Unit id and a short excerpt (AD-29, extends AD-18)
**And** it calls a new `LlmPort.synthesizeAnswer(question, context)`, whose prompt numbers each context item and requires inline `[n]` citations
**And** the adapter drops any citation not in the given context, and an answer of "not in the context" maps to the existing `noAnswer` shape
**And** the query response gains an additive `citations` array of `{textUnitId, documentName, excerpt}` for the citations kept (AD-13 otherwise unchanged)
**And** the chat shows the answer text with its `[n]` markers (rendering of citations is Story 15.4)
**And** the offline stub keeps its deterministic templated answer and returns no citations
**And** a test proves every returned citation id appears as a `TEXT_UNIT` step in that answer's trace

### Story 15.3: Synthesize Cited Global and DRIFT Answers

As the presenter,
I want Global and DRIFT answers written the same way,
So that all three modes give comparable, grounded answers.

**Acceptance Criteria:**

**Given** Story 15.2's `synthesizeAnswer` and citation handling
**When** a Global Search question runs
**Then** the context is the top Community summaries (Story 15.1 or keyword fallback) plus, per Community, its highest-`weight` member Text Units (capped), each recorded as trace steps, and the answer is synthesized and cited as in Story 15.2
**When** a DRIFT question runs
**Then** each sub-question branch gathers Local-style context (Story 15.2) under its `SUB_QUESTION_SPAWNED` step, the final synthesis uses the union of branch contexts, and the drift tree's Synthesis node is reached only after all branches, as today
**And** both modes return `citations` and map "not in the context" to `noAnswer`
**And** the offline stub keeps today's templated answers for both modes

### Story 15.4: Show Citations in Chat and Source Passages in Replay

As a viewer,
I want to open an answer's citations and see the cited passages light up during Replay,
So that I can verify the answer against the source myself.

**Acceptance Criteria:**

**Given** an answer with `citations` (Stories 15.2–15.3)
**When** it is shown in the chat
**Then** each `[n]` marker is a small button, and a "Sources" list under the answer shows `{documentName} · {excerpt}` per citation
**And** activating a marker or source opens the passage text (reusing Story 13.4's text-unit endpoint) in a popover or the detail panel
**And** during Replay, a `TEXT_UNIT` step's caption reads "Read passage {ordinal} of {documentName}" with the excerpt, and the Entities that cite that passage are highlighted on the canvas
**And** the `reading-an-answer` and `trace-replay` help articles explain citations and passage steps
**And** answers without citations (offline mode) render exactly as today
**And** a UI test asks a question, opens a citation, and steps the Replay onto a passage step
