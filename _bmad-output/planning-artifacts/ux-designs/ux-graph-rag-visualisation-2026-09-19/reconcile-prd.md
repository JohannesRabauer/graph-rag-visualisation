---
title: GraphRAG Lens — UX/PRD Input Reconciliation
created: 2026-09-19
purpose: >
  Finalize-step check: does DESIGN.md + EXPERIENCE.md faithfully and completely realize
  every PRD Functional Requirement (FR-1..FR-15) and Glossary term, with consistent
  terminology? Read-only pass — no spine files edited.
inputs:
  - _bmad-output/planning-artifacts/prds/prd-graph-rag-visualisation-2026-09-19/prd.md
  - _bmad-output/planning-artifacts/ux-designs/ux-graph-rag-visualisation-2026-09-19/DESIGN.md
  - _bmad-output/planning-artifacts/ux-designs/ux-graph-rag-visualisation-2026-09-19/EXPERIENCE.md
  - _bmad-output/planning-artifacts/ux-designs/ux-graph-rag-visualisation-2026-09-19/.memlog.md (context only)
---

# Reconciliation Findings

## Method

Read all four source files in full. Cross-checked each PRD FR (and its "Consequences (testable)"
sub-bullets) against EXPERIENCE.md's Component Patterns, State Patterns, Interaction Primitives,
and Key Flows, and against DESIGN.md's component/token definitions. Checked Glossary terms
(Corpus/Document Set, Demo Dataset, Knowledge Graph, Entity, Relationship, Community, Local
Search, Global Search, Retrieval Trace, Replay) for consistent naming across both spine files.
Memlog decisions (community-detection toggle, the new Explore page) were treated as known context,
not flagged as gaps per the task instructions — they are already slated for separate reconciliation
by the parent conversation.

## FR-by-FR spot checks (the four called out explicitly)

- **FR-5 (visible LLM-failure error state during Knowledge Graph construction).** Present and
  correctly wired: DESIGN.md defines an `error-banner` component explicitly tied to FR-5 (Active/
  Active-soft colors, "no icon glyphs," plain-language message), and EXPERIENCE.md's State Patterns
  table has an "LLM-call failure" row citing FR-5 and the NFR reliability bullet, plus Flow 1's edge
  case narrates it faithfully (no retry, no crash, visible banner). Voice and Tone gives the exact
  copy pattern. **However**, see Gap 2 below — the state row's scope is broader than FR-5's literal
  wording.
- **FR-9/FR-10 consequences (mode recorded on trace; "no answer found" states).** Present and
  correctly wired: Component Patterns' "Local/Global Search toggle" row states verbatim "Selected
  mode is recorded on the Retrieval Trace (FR-9/FR-10 consequences)." State Patterns' "No-answer-
  found" row correctly distinguishes the Local Search cause (neighborhood traversal yields nothing
  relevant) from the Global Search cause (no Communities exist yet), matching the PRD's per-mode
  consequence text almost word for word, and correctly frames both as visible states rather than
  empty/misleading responses. **However**, see Gap 3 below — whether a Retrieval Trace/Replay is
  still produced in this state is left unspecified.
- **FR-12/FR-13 (captured trace + replay, NOT live streaming).** Present and correctly wired:
  EXPERIENCE.md's scrubber row states explicitly "Replay is available only after generation
  completes — no live/streaming visualization (PRD FR-13, explicitly out of scope)," and DESIGN.md's
  step-badge/scrubber components are all built around discrete, already-captured steps (ticks,
  step counter, plain-sequence caption), never a live/streaming visual idiom. Interaction Primitives
  reiterates discrete-step semantics ("steps are discrete, not continuous time — there's no
  'between steps' state").
- **FR-14/FR-15 (Docker Compose setup, env-var API key).** Present and correctly wired: EXPERIENCE.md
  Flow 2 reproduces the PRD's setup sequence exactly (single Docker Compose command → Neo4j
  provisioned → `OPENAI_API_KEY` env var as the only manual step → no in-app config UI), and the
  Information Architecture section explicitly notes "no settings screen (API key is
  environment-variable-only per PRD FR-15, with no in-app configuration UI for v1)."

## Glossary consistency

All ten Glossary terms (Corpus, Demo Dataset, Knowledge Graph, Entity, Relationship, Community,
Local Search, Global Search, Retrieval Trace, Replay) are used consistently and by name across both
spine files — no paraphrasing or drift found (e.g., Voice and Tone explicitly instructs "Name the
Glossary term every time," and both files follow that rule). No spine term collides with or
silently redefines a Glossary term.

## Explore page — internal consistency (not a PRD gap; noted per instructions)

EXPERIENCE.md's description of the Explore page is internally consistent with itself: Information
Architecture, Component Patterns (two rows), State Patterns (two rows), Interaction Primitives, and
Flow 3 all agree on the same behavior — always-on Community visualization (no toggle, unlike the
main screen), node-click opens a detail panel (connections/details/tags), reached via a persistent
nav link, with a defined empty-state fallback when no Corpus has been ingested yet. This matches the
memlog's decisions exactly. One asymmetry worth surfacing for whoever finalizes the visual side (not
a PRD-fidelity gap, since Explore itself isn't a PRD FR yet): DESIGN.md gives the Explore page almost
no visual spec of its own — its only mention is one clause about reusing the Community hull colors
("main-screen toggle and the always-on Explore page view"). There is no DESIGN.md entry for the
node-click detail panel, the nav link/tab, or an Explore-page layout, even though EXPERIENCE.md
fully specifies Explore's behavior. This is a DESIGN/EXPERIENCE completeness asymmetry for that one
surface, not a contradiction.

## Gaps found

1. **FR-1/FR-2 upload-validation error states are unaddressed by name.** The PRD requires a "clear,
   visible message" for two specific non-LLM failure cases — an unsupported file extension (FR-1)
   and a PDF that yields no extractable text (FR-2) — but EXPERIENCE.md's only error-state row
   ("LLM-call failure") is scoped to live LLM-call failures during extraction or answer generation.
   Neither State Patterns, Component Patterns, nor the Voice and Tone table gives copy or a
   component for a rejected-upload or empty-PDF case, so the UX spines are silent on how these two
   PRD-mandated error states actually render.
2. **FR-5's scope in EXPERIENCE.md is wider than the PRD FR it cites.** PRD FR-5 covers only LLM
   failures "during Knowledge Graph construction" (extraction). EXPERIENCE.md's "LLM-call failure"
   state row says the plain-language message names "what failed (extraction **vs. answer
   generation**)" — i.e., it also covers a live LLM call failing while generating an answer during
   Local/Global Search. That's a reasonable real-world case, but no PRD FR or FR-9/FR-10 consequence
   actually requires a visible failure state for answer-generation LLM calls, so this piece of the
   UX spine isn't grounded in a corresponding FR.
3. **Unspecified interaction between "no answer found" (FR-9/FR-10) and Retrieval Trace/Replay
   (FR-12/FR-13).** The PRD says a Retrieval Trace is captured "during query execution (FR-9,
   FR-10)" with no stated exception, implying one exists even for a query that resolves to "no
   answer found." EXPERIENCE.md's "No-answer-found" state row describes only the chat message and
   doesn't say whether a Replay CTA/trace is still offered (e.g., showing which Entities were
   checked and came up empty) or suppressed entirely — leaving that FR-9/10-to-FR-12/13 handoff
   unspecified.
4. **DESIGN.md under-specifies the Explore page relative to EXPERIENCE.md.** As noted above, this
   isn't a PRD-fidelity gap (Explore isn't yet a PRD feature), but it is a real spine-to-spine
   completeness gap worth flagging before Explore is formally reconciled into the PRD: EXPERIENCE.md
   fully specifies Explore's behavior, while DESIGN.md gives it no layout, no node-detail-panel
   component, and no nav-link styling — only a passing mention of reusing Community hull colors.

No gaps were found in FR-6/FR-7 (community detection + visualization, aside from the already-known,
already-flagged memlog toggle decision), FR-3 (Demo Dataset), FR-4 (extraction into Neo4j), FR-8/
FR-11 (chat submit/render), or the FR-14/FR-15 setup flow — all are faithfully and consistently
represented in both spines.
