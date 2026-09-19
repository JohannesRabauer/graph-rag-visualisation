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
