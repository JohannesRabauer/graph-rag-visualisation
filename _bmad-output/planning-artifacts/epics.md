---
stepsCompleted: [1, 2]
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
FR16: User can navigate to a dedicated Explore page showing the full Knowledge Graph, with pan and zoom, reached via a persistent link/tab from the main screen.
FR17: User can click any Entity on the Explore page to see its connections (Relationships), details, and Tags.

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
UX-DR3: Build the Local/Global Search mode toggle with an inline, plain-language explanatory hint that updates immediately when the mode changes.
UX-DR4: Build the Composer (text input + send button, plain placeholder copy).
UX-DR5: Build the Graph canvas component (faint grid background, uppercase eyebrow state title, legend row listing visible Community names with color swatches).
UX-DR6: Implement node/edge visual states: default node, active (currently-visited) node, previous-step node, default edge, traversed edge, upcoming/dashed edge.
UX-DR7: Implement Community hulls (soft pale ellipses with monospace labels), shown on the main screen only when its toggle is ON, and always shown on the Explore page.
UX-DR8: Build the Step badge overlay shown during Retrieval Trace Replay (step counter + traversed-relationship name).
UX-DR9: Build the Retrieval Trace scrubber: transport buttons (step-back / play-pause / step-forward), a discrete per-step tick track, a step counter, and a plain-language caption of the current step.
UX-DR10: Build the Corpus chip (app-bar pill naming the active Corpus and document count).
UX-DR11: Build the Community-detection visualization toggle, defaulting ON for a fresh Corpus's first run.
UX-DR12: Build the Error banner component for LLM-call or extraction failures (plain-language message, no icon glyphs beyond the warm-color cue).
UX-DR13: Build the Node detail panel (Explore page, slides in from the right on node click; Entity heading, connections/Relationships, details, and Tags as chips).
UX-DR14: Implement the 5-color categorical Community palette as a best-effort, non-hard-gated colorblind-distinguishability goal.
UX-DR15: Implement the single continuous main-screen Information Architecture — no gated setup wizard; the empty state doubles as the Corpus-upload/Demo-Dataset-selection screen.
UX-DR16: Implement the Explore page as a second surface, reached via a persistent link/tab in the main screen's app bar.
UX-DR17: Implement every State Pattern from EXPERIENCE.md: idle/empty, upload-rejected, ingestion-in-progress, community-detection-in-progress, LLM-call-failure, no-answer-found, populated/answered, and the Explore page's before-any-node-selected state.
UX-DR18: Implement the Voice and Tone microcopy patterns exactly as specified (tutorial-clear language, Glossary terms named consistently every time, the idle-state differentiation subtitle).
UX-DR19: Implement Interaction Primitives: click-a-node (Explore detail vs. main-canvas Replay highlight), immediate-effect toggle switches with no confirmation step, scrubber drag/step, and the equally-weighted upload-vs-Demo-Dataset choice.
UX-DR20: Implement the Accessibility Floor: basic contrast floor for text/labels, basic keyboard reachability for primary actions (submit question, toggle Local/Global, toggle community visualization, scrubber play/pause/step).
UX-DR21: Implement the three Key Flows end-to-end as testable journeys: UJ-1 (Explaining GraphRAG live), UJ-2 (Setting up before a stream), UJ-3 (Freely exploring the Knowledge Graph).

### FR Coverage Map

FR1: Epic 2 - Upload plain text files as a Corpus
FR2: Epic 2 - Upload PDF files as a Corpus
FR3: Epic 2 - Select the built-in Sherlock Holmes Demo Dataset
FR4: Epic 2 - Extract Entities/Relationships into Neo4j via live LLM calls
FR5: Epic 2 - Surface extraction failures visibly
FR6: Epic 3 - Detect Communities (Leiden clustering)
FR7: Epic 3 - Toggle community-formation visualization (defaults ON first run)
FR8: Epic 4 - Submit a query via chat interface
FR9: Epic 4 - Answer via Local Search
FR10: Epic 4 - Answer via Global Search
FR11: Epic 4 - Render the final answer in chat
FR12: Epic 5 - Capture the Retrieval Trace
FR13: Epic 5 - Replay the Retrieval Trace
FR14: Epic 1 - One-command infrastructure setup
FR15: Epic 1 - API key via environment variable
FR16: Epic 6 - Explore the full Knowledge Graph
FR17: Epic 6 - Inspect an Entity's details

NFR1 (UI tone): Established in Epic 1 (design tokens/shell), enforced across all epics.
NFR2 (Reliability, bounded): Enforced in Epic 2 (extraction failures) and Epic 4 (generation failures).
NFR3 (Single-user, local-only): Enforced in Epic 1 (deployment topology, no auth).
NFR4 (Provider flexibility): Established in Epic 2 (first epic to introduce the LlmPort/LangChain4j adapter boundary).

## Epic List

### Epic 1: Foundation & One-Command Setup
Establishes the project skeleton (Hexagonal module layout, Spring Boot + Thymeleaf shell, base design tokens) and delivers the PRD's own Setup & Deployment capability: anyone can clone the repo, run a single Docker Compose command, and reach the app's empty-state main screen — Neo4j (with the GDS plugin) running alongside it, ready to accept a Corpus. Realizes UJ-2.
**FRs covered:** FR14, FR15

### Epic 2: Corpus Ingestion & Knowledge Graph Construction
Users can bring their own documents (plain text or PDF) or pick the built-in Sherlock Holmes Demo Dataset, and watch a real Knowledge Graph get built in Neo4j via live LLM extraction — with visible, honest errors if a file is rejected or an LLM call fails, never a silent hang. This is the first epic where a user has genuine graph data to show for their input.
**FRs covered:** FR1, FR2, FR3, FR4, FR5

### Epic 3: Community Detection & Visualization
Building on an ingested Corpus, the Knowledge Graph is automatically clustered into Communities, and users can watch that clustering happen (on by default for a first run) or toggle it off to compare with/without — the signature "watchable step" the whole project is built to teach.
**FRs covered:** FR6, FR7

### Epic 4: Query Interface
Users can ask a natural-language question through a chat interface, explicitly choosing Local Search or Global Search, and see a real, generated answer — including an honest "no answer found" result when retrieval comes up empty, distinct from an actual failure.
**FRs covered:** FR8, FR9, FR10, FR11

### Epic 5: Retrieval Trace Replay
After an answer arrives, users can scrub back and forth through exactly how it was produced — which Entities, Relationships, and Communities were touched, in order — turning "GraphRAG found an answer" into "here's precisely how."
**FRs covered:** FR12, FR13

### Epic 6: Graph Exploration
Independent of any question, users can navigate to a dedicated page and freely pan, zoom, and click around the entire Knowledge Graph, with Communities always visible and a detail panel showing any Entity's connections, details, and Tags.
**FRs covered:** FR16, FR17

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

### Story 2.4: Build the Knowledge Graph from a Corpus

As the creator,
I want the system to extract Entities and Relationships from my Corpus via live LLM calls and build them into Neo4j, with the graph visibly growing as it happens,
So that I have a real, non-scripted Knowledge Graph to demonstrate and query (FR4).

**Acceptance Criteria:**

**Given** a Corpus has been queued (from Story 2.1, 2.2, or 2.3)
**When** construction runs
**Then** the system calls `LlmPort` (implemented by the LangChain4j/OpenAI adapter, AD-3) to extract Entities and Relationships, never referencing LangChain4j or OpenAI types outside that one adapter
**And** each Entity is written via Cypher `MERGE` keyed on a normalized identity (lowercased name + type), never a blind `CREATE`, so the same entity mentioned twice resolves to one node (AD-10)
**And** progress is pushed to the browser as named Server-Sent Events on `GET /api/corpora/{corpusId}/progress` (AD-7, AD-12), and the graph canvas shows nodes and edges appearing as they're extracted (UX-DR5)
**And** the LLM call is genuinely live for this run — no cached or pre-computed extraction is substituted

### Story 2.5: Surface Extraction Failures Visibly

As the creator,
I want a clear, visible error if an LLM call fails during Knowledge Graph construction,
So that a failure is honest and obvious rather than a silent hang or crash (FR5).

**Acceptance Criteria:**

**Given** Knowledge Graph construction is in progress
**When** an LLM call fails (e.g. rate limit, API error)
**Then** an `error` Server-Sent Event is emitted on the Corpus's progress stream (AD-12)
**And** the frontend displays the Error banner component (UX-DR12) naming what failed, in plain language
**And** the system does not automatically retry or substitute cached/canned output (NFR2) — this is a deliberate, accepted risk, not an oversight

## Epic 3: Community Detection & Visualization

Building on an ingested Corpus, the Knowledge Graph is automatically clustered into Communities, and users can watch that clustering happen (on by default for a first run) or toggle it off to compare with/without — the signature "watchable step" the whole project is built to teach.

### Story 3.1: Detect Communities in the Knowledge Graph

As the creator,
I want the system to automatically cluster the Knowledge Graph into Communities and generate a summary for each,
So that Community structure and summaries are always ready, whether I'm about to watch them form, query with Global Search, or explore the graph later (FR6).

**Acceptance Criteria:**

**Given** Knowledge Graph construction (Story 2.4) has completed for a Corpus
**When** community detection runs
**Then** it starts automatically and asynchronously, unconditionally — never gated by any UI toggle state (AD-6)
**And** relationships fed into the GDS Leiden call are projected as `UNDIRECTED` (AD-4)
**And** each detected Community is written as a first-class `(:Community {id, summary})` node related to its member Entities via `[:BELONGS_TO]` — never a scalar property on Entity (AD-11)
**And** as part of this same run, each Community's summary is generated via `LlmPort` and persisted onto its node — `AnswerGlobalSearch` (Epic 4) will only ever read this summary, never generate one on demand (AD-6)

### Story 3.2: Toggle Community Formation Visualization

As the creator,
I want a toggle on the main screen controlling whether I see the community-formation animation, defaulting on for a Corpus's first run,
So that the signature "communities folding into clusters" moment plays automatically for a first-time viewer, while I can still turn it off afterward to demonstrate "with vs. without" (FR7).

**Acceptance Criteria:**

**Given** a fresh Corpus is being ingested for the first time
**When** community detection (Story 3.1) begins
**Then** the community-visualization toggle defaults to ON, and the frontend animates Communities visibly folding into hulls as the detection progress events arrive (UX-DR7, UX-DR11)
**And** when I switch the toggle OFF, the animation and hull overlay stop being shown, but detection continues unaffected underneath
**And** toggling the switch never re-runs detection, and the backend has no knowledge of the toggle's state at all — the same progress events are emitted regardless (AD-6)

## Epic 4: Query Interface

Users can ask a natural-language question through a chat interface, explicitly choosing Local Search or Global Search, and see a real, generated answer — including an honest "no answer found" result when retrieval comes up empty, distinct from an actual failure.

### Story 4.1: Submit a Question via Chat

As the creator,
I want a chat-style interface where I can type a question,
So that I can ask GraphRAG Lens about my ingested Corpus (FR8).

**Acceptance Criteria:**

**Given** a Corpus has been ingested (Epic 2)
**When** I type a question into the Composer and submit it
**Then** the question appears in the Chat panel's message thread (UX-DR2, UX-DR4)
**And** the request is sent as `POST /api/corpora/{corpusId}/query` with body `{"question": "...", "mode": "LOCAL" | "GLOBAL"}` (AD-13)

### Story 4.2: Answer via Local Search

As the creator,
I want to explicitly select Local Search and get an answer via entity-neighborhood traversal,
So that I can demonstrate targeted, entity-specific retrieval (FR9).

**Acceptance Criteria:**

**Given** I have the Local/Global Search toggle set to Local (UX-DR3) and I submit a question
**When** `AnswerLocalSearch` runs
**Then** it reads whatever Knowledge Graph state is currently committed, without waiting for or locking against any in-flight ingestion (AD-14)
**And** on success, the response is `{"answerId", "traceId", "answer"}` (AD-13)
**And** if neighborhood traversal from the matched Entities yields nothing relevant, the response is instead the distinct `{"answerId", "traceId", "noAnswer": true, "reason"}` shape — never the generic error shape (AD-13, FR9 consequence)

### Story 4.3: Answer via Global Search

As the creator,
I want to explicitly select Global Search and get an answer aggregated from Community summaries,
So that I can demonstrate corpus-wide, thematic retrieval as distinct from Local Search (FR10).

**Acceptance Criteria:**

**Given** I have the Local/Global Search toggle set to Global (UX-DR3) and I submit a question
**When** `AnswerGlobalSearch` runs
**Then** it reads the Community summaries already generated and persisted in Story 3.1 — it never generates a summary on demand (AD-6)
**And** on success, the response is `{"answerId", "traceId", "answer"}` (AD-13)
**And** if no Communities exist yet (detection hasn't completed), the response is the distinct `{"answerId", "traceId", "noAnswer": true, "reason"}` shape — never the generic error shape (AD-13, FR10 consequence)

### Story 4.4: Render the Final Answer in Chat

As the creator,
I want the generated answer displayed in the chat once it's ready, tagged with which search mode produced it,
So that I immediately see the result alongside my question, and know how it was produced (FR11).

**Acceptance Criteria:**

**Given** a query (Story 4.2 or 4.3) has completed successfully
**When** the answer is returned
**Then** it renders in the Chat panel's message thread, tagged with its search mode (`{components.message-answer}`, UX-DR2)
**And** a Replay CTA appears, offering the step-by-step Retrieval Trace (leads into Epic 5)
**And** if the LLM call fails during answer generation itself, the same `{"error": "..."}` shape and Error banner used for extraction failures (Story 2.5) apply here too, with no automatic retry (NFR2, extending FR5's principle to generation failures)

## Epic 5: Retrieval Trace Replay

After an answer arrives, users can scrub back and forth through exactly how it was produced — which Entities, Relationships, and Communities were touched, in order — turning "GraphRAG found an answer" into "here's precisely how."

### Story 5.1: Capture the Retrieval Trace

As the creator,
I want the system to capture an ordered record of which Entities, Relationships, and Communities were touched while answering a query,
So that I can later show exactly how that answer was produced, not just that it was (FR12).

**Acceptance Criteria:**

**Given** a query (Story 4.2 or 4.3) is executing
**When** Local Search or Global Search touches an Entity, Relationship, or Community
**Then** that touch is appended as one step to an ordered, in-memory Retrieval Trace — never an unordered set (AD-5)
**And** the trace is addressed by a UUID `traceId`, generated when the answer is produced and returned alongside it
**And** the trace is never persisted to Neo4j and is fetchable only via `GET /api/traces/{traceId}` (AD-5)

### Story 5.2: Replay the Retrieval Trace

As the creator,
I want to play back a captured Retrieval Trace step by step, with controls to move forward and backward,
So that I can show, live, exactly how GraphRAG arrived at an answer (FR13).

**Acceptance Criteria:**

**Given** an answer with a Replay CTA (Story 4.4) is showing in chat
**When** I click the Replay CTA
**Then** the Retrieval Trace scrubber appears below the graph canvas, fetched via `GET /api/traces/{traceId}` (UX-DR9)
**And** play/pause autoplays through the steps, step-forward/step-back move exactly one step per press, and dragging the scrubber head jumps to the nearest discrete step
**And** each step highlights the relevant node/edge on the canvas (active, previous-step, and traversed/upcoming edge states, UX-DR6) alongside a plain-language step-badge caption (UX-DR8)
**And** Replay is available only after generation completes — there is no live/streaming visualization of retrieval as it happens (explicitly out of scope for v1)
