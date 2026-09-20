---
title: Sprint Change Proposal — DRIFT Search & Vector-RAG Comparison Baseline
status: approved
created: 2026-09-20
approved: 2026-09-20
---

# Sprint Change Proposal — 2026-09-20

## 1. Issue Summary

Mid-sprint (Epics 1–6 done/in `review`, no MVP ship yet), the creator requested two new capabilities be added to GraphRAG Lens, following research into GraphRAG's broader mechanics (DRIFT search, and vector-RAG as a comparison point):

1. **DRIFT Search** — a third query mode alongside Local and Global Search, running a Community-summary pass that spawns targeted Local Search sub-questions, re-ranks them, and synthesizes a final answer.
2. **Vector-RAG Comparison Baseline** — the creator's stated "dream" feature: a plain vector-similarity pipeline, triggered on demand, visualized step-by-step (chunking, embedding, query embedding, similarity ranking) next to GraphRAG's own trace, so a viewer sees not just that the two differ but *how*, concretely — including a 2D visualization of the embedding space itself.

Both are new requirements, not bugs or technical dead-ends, and both are direct extensions of the product's existing Vision ("nothing today visualizes GraphRAG's retrieval mechanics this way").

## 2. Impact Analysis

**Epic impact:** Epics 1–6 require no rework — both additions are purely additive. Epic 5 (Retrieval Trace) is the most conceptually affected, since AD-5's trace-step model needed a documented extension (two new step kinds), but its already-`review` stories (5.1/5.2) are not reopened — the extension is additive (AD-18), following the same amendment pattern already established by AD-16 in this project.

**Artifact conflicts resolved:**
- **PRD** — added Glossary terms, FR-18–FR-22, and a new §7.3 "v1.1 Scope" section kept separate from the locked v1 MVP scope (§7.1, FR-1–FR-17 untouched), protecting the existing success metrics (SM-1/SM-2) from silent scope expansion.
- **Architecture** — added AD-17 (vector subsystem reuses the *existing* `graphrag-adapter-langchain4j` and `graphrag-adapter-neo4j` adapters via new `EmbeddingPort`/`VectorStorePort`, using Neo4j's native vector index rather than a third container, preserving AD-8) and AD-18 (extends AD-13's mode enum to include DRIFT, and AD-5's trace-step model with two new step kinds — sub-question-spawned, chunk-retrieved-via-similarity).
- **UX** — DESIGN.md and EXPERIENCE.md both updated: a third mode color (Drift, Rose `#C0225F` — a deliberate, considered break of the prior "exactly two mode hues" rule), a new branching Replay view for DRIFT (`components.drift-tree`), a new Vector Space tab with an embedding scatter (`components.embedding-scatter`), and an on-demand Compare CTA. Decided via a dedicated `bmad-ux` pass with the creator (mockup: `ux-designs/ux-graph-rag-visualisation-2026-09-19/mockups/direction-instrument-extensions.html`), not improvised in epic text.
- **Other artifacts** — no new Docker container, no new Maven module (both new ports are implemented by existing adapters); new unit/adapter tests and Playwright UI tests will be added per story.

## 3. Recommended Approach

**Option 1 — Direct Adjustment**, framed as v1.1 (not folded into the locked MVP scope). Two new epics (7, 8) with 4 and 5 stories respectively, added to the backlog. Rollback (Option 2) was not applicable — nothing already built conflicts. MVP scope review (Option 3) was not needed — v1.1 framing achieves the same protection without reopening the MVP boundary.

**Effort:** Medium–High (9 new stories across two epics, one new port pair, one new adapter capability, no new infrastructure). **Risk:** Medium — DRIFT's multi-stage orchestration and the vector subsystem are genuinely new logic, not just wiring, but reuse every existing architectural seam (ports/adapters, SSE, trace model) rather than introducing new ones.

## 4. Detailed Changes

| Artifact | Change |
|---|---|
| `prds/prd-graph-rag-visualisation-2026-09-19/prd.md` | Glossary: DRIFT Search, Vector Baseline. New §4.8 (FR-18), §4.9 (FR-19–FR-22). New §7.3 "v1.1 Scope." |
| `epics.md` | FR Coverage Map extended. New Epic 7 (4 stories), Epic 8 (5 stories), fully detailed with acceptance criteria. |
| `architecture/.../ARCHITECTURE-SPINE.md` | New AD-17 (vector subsystem, adapter reuse), AD-18 (mode enum + trace step-kind extension). Capability→Architecture Map and Structural Seed updated. `binds` list extended. |
| `ux-designs/.../DESIGN.md` | New tokens (drift, drift-soft, vector-hit, vector-query) and components (drift-tree, vector-space-tab, embedding-scatter, compare-cta). Colors/Components prose and Do's-and-Don'ts updated. |
| `ux-designs/.../EXPERIENCE.md` | IA, Voice and Tone, Component Patterns, State Patterns, Interaction Primitives, Accessibility Floor all extended for DRIFT and Vector Space. |
| `sprint-status.yaml` | Epic 7 and Epic 8 added, all stories `backlog`. |

**Key decisions locked with the creator during the UX pass:**
- DRIFT color: Rose `#C0225F` (zero collision with existing palette).
- DRIFT Replay: branching/nested tree, not flattened to linear.
- Vector Baseline trigger: on-demand "Compare" CTA per answer, never automatic.
- Embedding Space layout: view-switch tab next to Knowledge Graph, not a permanent split screen.
- 2D projection: settles once at ingestion time, stable across questions — never recomputed per query.

## 5. Implementation Handoff

**Scope classification: Moderate** — backlog reorganization complete (this proposal); implementation is standard `bmad-build` story work from here, same as Epics 1–6. No further PM/Architect replan needed; the creator is both product owner and sole implementer.

**Next steps:** `bmad-sprint-planning` (optional, to re-validate readiness) or directly into `bmad-build` starting with Story 7.1 / Story 8.1, in either order — Epic 7 and Epic 8 have no dependency on each other, only on Epics 3–5 (already done/review).

## 6. Approval

Approved by the creator in-session, 2026-09-20, with explicit direction to lock in quickly and merge to `main`.
