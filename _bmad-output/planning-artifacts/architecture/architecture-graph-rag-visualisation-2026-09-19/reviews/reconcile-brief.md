---
title: Input Reconciliation — Brief/Addendum vs. Architecture Spine
subject: architecture-graph-rag-visualisation-2026-09-19/ARCHITECTURE-SPINE.md
status: complete
created: 2026-09-19
---

# Input Reconciliation: Brief + Addendum → Architecture Spine

Purpose: verify the ARCHITECTURE-SPINE.md honors every architecture-relevant note in the product brief and its addendum. This is a read-only check — nothing in the spine was edited.

## Sources reviewed

- `brief.md` (full)
- `addendum.md` (full, with focus on "Notes for Architecture" and "Parked for Later")
- `ARCHITECTURE-SPINE.md` (full)

## Check 1 — File ingestion parsing/extraction boundary

**Addendum note:** "File ingestion (plain text and PDF for v1) needs a real parsing/extraction boundary, not a hardcoded loader — PDF text extraction in particular should be isolated behind an interface so it doesn't leak into the graph-construction logic."

**Finding: Honored, explicitly and traceably.**

- `DocumentParserPort` is a first-class port in `graphrag-core` (Design Paradigm diagram, Structural Seed).
- `graphrag-adapter-parsing` is the sole owner of Apache PDFBox and plain-text handling; per AD-1, no PDFBox import may appear in `graphrag-core`.
- AD-9 goes further than the addendum literally required: each file type is its own adapter class with a `supports(filename): boolean` method, and a **core-owned dispatcher** — not `graphrag-web` — selects the adapter. This directly prevents PDF-specific concerns leaking into `IngestCorpus` (graph-construction logic), which is exactly the addendum's stated worry, and also prevents the dispatch knowledge from leaking into the web layer via framework wiring (a leak vector the addendum didn't name but the same principle covers).
- No gap.

## Check 2 — Provider abstraction (LangChain4j/OpenAI) as an explicit boundary

**Addendum note:** "Provider abstraction (LangChain4j, OpenAI initially) exists specifically to make swapping LLM providers cheap later — this should be reflected as an explicit architectural boundary, not an incidental library choice."

**Finding: Honored, explicitly and traceably.**

- `LlmPort` is a core port; `graphrag-adapter-langchain4j` is the only module permitted to import LangChain4j or OpenAI SDK types (AD-3, stated as a hard rule, not a convention).
- The Capability → Architecture Map ties every LLM-touching capability (Knowledge Graph Construction, Query Interface) back to AD-3.
- Config for the provider (API key) is environment-variable only (Consistency Conventions), keeping provider config out of code.
- No gap.

## Check 3 — "Library-in-mind" shaping module boundaries without over-building for it

**Addendum note:** "The 'library-in-mind' ambition affects module boundaries (e.g., separating core GraphRAG/graph logic from the demo web app) more than it affects any v1 feature — no v1 scope item should exist purely to serve the future-library goal."

**Finding: Honored overall, with one nuance worth flagging (not a hard defect).**

- The core/web split (`graphrag-core` vs. `graphrag-web`) is exactly the boundary the addendum names, and the spine says so directly: "This paradigm is chosen directly for the brief's 'library-in-mind' goal."
- The Deferred section explicitly keeps actual library packaging/publishing/versioning **out of v1 scope**, citing this as future-only work the module boundary merely makes *possible*, not something built now. This is the clearest possible compliance with the "no v1 feature exists purely to serve the future-library goal" constraint.
- **Nuance:** `GraphStorePort` wraps Neo4j in the same hexagonal treatment as the LLM and parsing ports, even though the brief/addendum only called out LLM-provider and file-type swapping as the reasons a port boundary was needed — Neo4j is declared "the only graph store" for v1 with no swap in view ("Explicitly out for v1: Graph databases other than Neo4j"). The spine is transparent about this tension (Deferred: "`GraphStorePort` makes this theoretically swappable later, but no second adapter is planned or designed against now") rather than silently over-building, and a uniform port for testability (mocking the store in unit tests) is a legitimate v1 reason independent of the library goal. This is a low-severity item: worth a conscious call-out that this one port's existence is justified by testability/consistency, not by an addendum-stated swap need, so it doesn't quietly get read later as "the addendum asked for this too."

## Check 4 — Brief Vision/differentiation implying constraints the spine might have missed

Checked against every claim in "What Makes This Different," "Success Criteria," "Scope," and "Vision":

- **"Nothing is scripted or cached: every run is a genuine LLM call against a genuine graph"** — consistent with AD-6 (community summaries are generated once, immediately, not lazily/on-demand — this is core GraphRAG algorithm design, not a forbidden "cached safety net") and the Consistency Conventions line "never retried automatically." No conflict with the brief's explicit ban on failure-fallback caching (Scope: "Any fallback/cached 'safety net' for live-demo LLM failures" is out for v1) — these are different kinds of "caching" and the spine doesn't confuse them.
- **"captured from a genuinely live, non-scripted run and replayed step by step, scrubbable rather than one-shot"** (differentiation bullet 1) — AD-5 commits to trace transience, per-UUID addressing, and non-persistence, but does **not** explicitly commit the architecture to the trace being an *ordered, steppable sequence* of events (as opposed to a final-state snapshot). This is arguably appropriate for spine altitude (a full `RetrievalTrace` property list is deferred to detailed design/PRD/UX, and the ER diagram note says the spine intentionally omits full property lists) — but since "scrubbable, step-by-step" is called out as the brief's single biggest differentiation claim, it's worth an explicit downstream check that the PRD/UX artifacts (already referenced as spine sources) actually pin down a step-ordered trace structure, since the spine itself doesn't bind that shape. Flagged as a **gap to verify downstream**, not a spine defect per se.
- **"It's Java-native in a Python-dominated space"** — Stack table is all-Java on the backend (Spring Boot, LangChain4j, Neo4j Java Driver, PDFBox); the TypeScript/Cytoscape.js frontend doesn't contradict this claim (a browser UI isn't part of the "Java-native backend" claim). No conflict.
- **"Modern, minimalist... browser-based UI with minimal setup friction"** — AD-8 (exactly two Docker Compose services, `docker-compose up`) and the static-SPA-on-classpath approach satisfy this directly. No conflict.
- **Scope's "Single local user... no auth, no hosting, no multi-tenancy"** — correctly listed in Deferred. No conflict.
- **Vision's "reference for how to explain GraphRAG"** — served by keeping trace capture in `graphrag-core` use cases (portable) while trace storage/replay API lives in `graphrag-web` (demo-specific), per the Capability → Architecture Map. No conflict.

## Summary of gaps

1. **(Low severity, verify downstream)** AD-5 guarantees trace *addressability and transience* but does not architecturally commit to the trace being an ordered/steppable event sequence — which is what the brief's core differentiator ("replayed step by step, scrubbable rather than one-shot") structurally depends on; confirm the PRD/UX documents (already listed as spine sources) pin this down, since the spine itself leaves it open.
2. **(Low severity, self-flagged tension)** `GraphStorePort` receives the same hexagonal-port treatment as the LLM and parsing ports even though the brief/addendum motivated ports only for LLM-provider and file-type swapping (Neo4j is declared the only graph store for v1, no swap planned) — the spine already discloses this in Deferred, but it's a case where uniform architectural consistency extends slightly past what the addendum explicitly asked for, worth keeping conscious of rather than retroactively attributing to the addendum.

No other gaps found. Checks 1 and 2 (parsing boundary, provider abstraction) are fully and explicitly honored with direct, named architectural rules (AD-9, AD-3). Check 3 (library-in-mind without over-building) is honored, with one disclosed nuance. Check 4 found no missed constraint from the brief's Vision/differentiation section beyond the trace-structure question above.
