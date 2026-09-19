# Epic 1 Context: Foundation & One-Command Setup

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

This epic lays the ground everything else is built on: a Maven multi-module project scaffolded along Hexagonal Architecture boundaries, a Docker Compose setup that brings up the whole app plus Neo4j with one command, and a Thymeleaf-rendered main screen that loads into a correct resting/empty state. It matters because every later epic (ingestion, querying, community detection, trace replay, exploration) is implemented inside the module boundaries and deployment topology this epic establishes, and because the module split is the specific seam that keeps a future standalone Java GraphRAG library extraction realistic rather than a rewrite. It also delivers the PRD's own Setup & Deployment capability end to end: clone, run one command, reach a usable app with no separate setup screen.

## Stories

- Story 1.1: Project Skeleton & Module Boundaries
- Story 1.2: One-Command Local Environment
- Story 1.3: Empty-State Main Screen

## Requirements & Constraints

- The application and its Neo4j dependency must be startable via a single Docker Compose command; no manual step beyond that (FR14).
- The LLM API key is supplied purely via an environment variable at startup (`OPENAI_API_KEY`); there is no in-app configuration UI, settings screen, or config file for v1 (FR15).
- The system is single-user and local-only for v1: no authentication, hosting, or multi-tenancy — the deployment topology reflects "runs on one developer's own machine" (NFR3).
- The overall UI tone (modern, minimalist, browser-based, low setup friction) is established starting in this epic and carried through every later epic (NFR1) — full visual spec lives in the design tokens below.
- The main screen must be reachable and correctly rendered with no upload/ingestion logic wired yet — that begins in Epic 2; this epic only needs the resting/idle state to be right.

## Technical Decisions

- **Module layout (Hexagonal / Ports & Adapters):** five Maven modules — `graphrag-core` (domain model + use cases + ports, zero framework dependency: no Spring, Neo4j driver, or LangChain4j imports anywhere in it), `graphrag-adapter-neo4j`, `graphrag-adapter-langchain4j`, `graphrag-adapter-parsing`, `graphrag-web`. `graphrag-core` defines the port interfaces (`GraphStorePort`, `LlmPort`, `DocumentParserPort`) as empty contracts in this epic; adapters implement them in later epics. Ports are named `<Noun>Port`; adapters `<Tech><Port-without-suffix>Adapter` (e.g. `Neo4jGraphStoreAdapter`).
- **Deployment topology:** exactly two Docker Compose services — `app` (the Spring Boot jar, serving REST + SSE + Thymeleaf pages + static JS from its own classpath) and `neo4j` (Community Edition with the GDS plugin enabled). No third/frontend-dev-server container is ever introduced.
- **Frontend is Java-native:** the page shell is server-rendered Thymeleaf under `graphrag-web/src/main/resources/templates/`; any client-side JS (Cytoscape.js canvas, later epics) is plain, unbundled `.js` under `graphrag-web/src/main/resources/static/js/`. No `package.json`, Node/npm tooling, or bundler anywhere in the project — `graphrag-web` depends on `spring-boot-starter-thymeleaf`.
- **Stack/versions to scaffold against:** Java 25 (LTS), Spring Boot 4.1.x (Spring Framework 7), Thymeleaf (bundled), Neo4j 2026.x Community Edition + GDS plugin, Neo4j Java Driver (latest at implementation start), LangChain4j (latest at implementation start), Apache PDFBox 3.0.x, Cytoscape.js (current), Maven multi-module build (confirmed choice, not TBD).
- **Config convention:** environment-variable-only configuration (the OpenAI API key); no config file, no in-app settings UI — this convention applies from day one, not added later.
- **Observability:** deliberately minimal — console logging only, no metrics/tracing infrastructure — appropriate to project scale, not an oversight to fix here.

## UX & Interaction Patterns

- **Design tokens ("Instrument" direction):** light-mode-only palette (no dark mode for v1) — neutral `paper`/`panel` surfaces, `accent` blue reserved for Local Search, `global` teal reserved for Global Search, `active` warm color reserved for "current step"/error states. System-sans UI typography throughout; monospace (`data-mono`) is reserved strictly for data/step labels (step counters, trace captions, corpus chip text) and never used for prose or button labels. These tokens must be applied to the page shell in this epic, even though the components that use accent/global/active meaningfully (chat, toggles, replay) don't exist until later epics.
- **Empty/idle state (main screen only, no separate setup screen):** the canvas shows an uppercase eyebrow title and the idle-state copy pattern "Knowledge Graph — Resting." The subtitle must carry the exact differentiation line from Voice and Tone: "Watch a Knowledge Graph get built, clustered, and searched — the mechanics most GraphRAG tools keep hidden." This is the only place that specific narrative line appears.
- **Information architecture:** the main screen is the single, only entry point on app load — a single continuous page (no gated wizard, no intermediate setup screen). Opening the app for the first time already shows the right starting point.
- **Accessibility floor (basic, not a compliance program):** body text and control labels must read clearly against their surfaces at normal viewing/streaming distance — a basic contrast floor, no low-contrast placeholder-on-placeholder text.

## Cross-Story Dependencies

- Story 1.2 (Docker Compose) packages the module structure Story 1.1 defines — the `app` service builds from the Maven multi-module project, so 1.1's module/build setup must exist first.
- Story 1.3 (empty-state main screen) renders via the Thymeleaf setup that lives in `graphrag-web`, established in Story 1.1, and is verified by loading the app as started in Story 1.2.
- This epic as a whole is a hard prerequisite for all later epics: Epic 2 onward implement their use cases and adapters inside the module boundaries fixed here, and rely on the Docker Compose topology and page shell already being correct.
