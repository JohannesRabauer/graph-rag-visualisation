---
title: Input Reconciliation — Brief/Addendum vs. PRD
created: 2026-09-19
scope: read-only reconciliation check; no source files edited
---

# Input Reconciliation: Brief + Addendum → PRD

**Inputs read:**
- `briefs/brief-graph-rag-visualisation-2026-09-19/brief.md` (status: final)
- `briefs/brief-graph-rag-visualisation-2026-09-19/addendum.md`
- `prds/prd-graph-rag-visualisation-2026-09-19/prd.md` (status: draft)

This is a gap-finding pass only. No files were modified.

---

## Gap 1 — "Real-time" retrieval visualization (brief's core differentiator) is quietly narrowed to post-hoc replay, and the tension is never surfaced

The brief's "What Makes This Different" section builds its entire differentiation claim around **real-time** visibility:

> "Watching a query actually traverse a graph and pull from specific communities, **in real time**, appears to be genuinely unclaimed territory."

The Executive Summary reinforces this: "lets someone type a real question and **watch how the answer actually gets assembled**."

The PRD (§4.5, Retrieval Trace & Playback) makes an explicit, considered choice in the opposite direction:

> "Retrieval is captured as a structured trace and replayed as a scrubbable, step-by-step visualization — **a deliberate choice over live-streaming**, since replay supports rewinding and revisiting steps."

and formally excludes real-time visualization:

> **Out of Scope:** Live/real-time streaming of retrieval steps as they happen — replay-after-completion only for v1. (also listed in §6 Non-Goals)

This may well be the right product call (replay is arguably *more* watchable/explainable than a live stream, and still delivers "watching the mechanics"). But it directly trades away the specific "in real time" framing that the brief used to stake out competitive whitespace ("nobody visualizes GraphRAG's live retrieval mechanics... in real time"). The PRD never acknowledges this tension, never revisits the brief's differentiation language in light of it, and a reader of the PRD alone would not know the brief's central "why this is unclaimed territory" argument leaned specifically on real-time-ness. This is exactly the kind of nuance an FR/non-goals structure loses silently — "replay, not live-streaming" reads as a neutral UX choice in the PRD, not as a change to the project's stated differentiation thesis.

**Recommendation:** Either (a) explicitly note in the PRD's Vision or a comment that "live" in the differentiation claim now means "a live, real LLM run you can replay," not "streamed in real time," or (b) flag this back to the brief owner as a considered scope change worth a one-line brief update.

---

## Gap 2 — The brief's visual/tonal identity ("modern, minimalist, browser-based, minimal setup friction") has no home anywhere in the PRD

Brief, Scope (In for v1): "**Modern, minimalist, browser-based UI with minimal setup friction**." The Solution section repeats it: "The UI is modern, minimalist, and runs entirely in the browser with no heavy client install."

Checked the PRD for any trace of this:
- The word **"browser" does not appear anywhere in the PRD** (verified via full-text search).
- The words **"modern"** and **"minimalist"** do not appear anywhere in the PRD.
- §5 Cross-Cutting NFRs has exactly three bullets (reliability, single-user/local-only, provider flexibility) — no aesthetic/UX-tone NFR at all.
- §9 Open Questions punts UI *control* details ("play/pause/step granularity/speed") to the UX pass, but never even flags that the tone/look-and-feel brief is waiting to be picked up by `bmad-ux` — a reader of the PRD alone has no signal that "modern, minimalist" was ever a stated constraint.

"Minimal setup friction" is the one piece that *does* survive, functionally, via FR-14/FR-15 (single Docker Compose command + one env var) — so that part of the sentence isn't lost. It's specifically the visual/tonal half ("modern, minimalist," "browser-based" as an experiential quality rather than a deployment fact) that has been silently dropped. This is the textbook case the task description warned about: a qualitative, non-testable brief statement has nowhere to live in an FR-shaped document, and nobody carried it forward as an NFR or a note for the UX pass.

**Recommendation:** Add a one-line NFR or explicit forward-pointer in §5 or §9, e.g. "UI must read as modern/minimalist and run entirely client-side in-browser (no native client) — detailed in UX pass," so the constraint isn't lost between brief and `bmad-ux`.

---

## Gap 3 — The brief's differentiation/competitive-landscape narrative (Java-ecosystem gap, "honest moat" framing) has no counterpart in the PRD

The brief's "What Makes This Different" section and the addendum's "Landscape Research Digest" carry real argumentative weight:
- Nobody visualizes GraphRAG's live retrieval mechanics (vs. post-hoc parquet viewers like `graphrag-visualizer`, vs. vector-RAG-only explainers like RAG Playground/RAGViz).
- It's Java-native in a Python-dominated space (Neo4j's own SDK, `llm-graph-builder`, and LangChain4j's GraphRAG support are all Python-first or immature-in-Java; ~2 Java-tagged repos found on GitHub).
- "Honest framing of the moat: this is not a technical moat, it's a timing-and-effort one" — a deliberate, explicit anti-hype statement about the project's defensibility.

The PRD's Vision (§1) compresses all of this into a single clause: "...in a space research confirmed is genuinely underserved." The specific comparisons (parquet-viewer prior art, vector-RAG explainer genre, the ~2-Java-repos data point, the explicit "not a technical moat" honesty) appear nowhere in the PRD. §2.1 JTBD does carry forward the *future-maintainer* angle ("extracting a reusable Java GraphRAG library later isn't a rewrite") but not the *why-Java-matters-now* argument.

This is arguably acceptable — a PRD is not obligated to restate market research the brief already covers, and the brief/addendum remain the source of record for that narrative. But per the task's specific instruction to check this framing: a PRD reader who has not also read the brief loses the entire "why does this matter, why is it worth doing" case, down to a single undersupported clause. If anyone downstream (an architecture reviewer, a future contributor, or the creator revisiting this in six months) reads only the PRD, the differentiation rationale is effectively invisible.

**Recommendation:** Low-severity; consider a short "Why This Matters" callout in §1 Vision that at least names the two concrete differentiators (live mechanics vs. post-hoc tools; Java vs. Python-only tooling) rather than the single generic "underserved" clause — even one sentence would prevent total loss of the framing.

---

## Verified consistent (no gap): arbitrary file ingestion scope

The task specifically asked to confirm the brief and PRD *now agree* on arbitrary file ingestion (not just that each looks internally fine), since the brief was updated mid-PRD-discovery to allow arbitrary plain-text/PDF ingestion instead of a fixed-corpus-only v1.

- Brief, Scope (In for v1): "User-provided file ingestion (plain text and PDF), with the public-domain Sherlock Holmes stories as the built-in demo/test dataset" — this is the *updated* language; it treats arbitrary txt/PDF ingestion as the real v1 feature and the Sherlock corpus as merely the bundled demo/test set.
- Brief, Solution §1: "Ingests user-provided documents (plain text or PDF — the Sherlock Holmes corpus by default)" — same framing.
- Addendum explicitly flags the update: "this section predates the decision (made during PRD discovery) to support arbitrary user-provided files as a real v1 feature. It's preserved as the rationale for *which dataset ships as the built-in demo* — a separate question from what the ingestion pipeline itself supports."
- PRD §4.1 (FR-1, FR-2, FR-3): user-provided `.txt` upload, user-provided PDF upload (with text-extraction and clear-failure requirements), and the Sherlock demo as a separate one-click convenience — matches the brief's updated framing exactly, including keeping the demo dataset as a distinct, secondary affordance rather than the only path.

No contradiction found. Brief and PRD are aligned on this point post-update.

---

## Minor aside (not a brief/PRD gap, flagged for awareness only)

PRD §0 states implementation-level tech choices are "intentionally kept out of this document," yet FR-14 specifies **Docker Compose** by name as the setup mechanism — a concrete implementation choice, not sourced from the brief/addendum (neither mentions Docker at all) and arguably inconsistent with the PRD's own stated boundary. This isn't a brief-vs-PRD conflict (the brief simply doesn't address deployment mechanism, so nothing is contradicted), but it's worth a note since it's the kind of detail that usually belongs in architecture, not the PRD, by the PRD's own rule.

---

## Summary

| # | Type | Severity | Item |
|---|------|----------|------|
| 1 | Contradiction (soft) | Medium-High | "Real-time" retrieval visualization (brief's stated differentiator) silently narrowed to post-hoc scrubbable replay in the PRD, with no acknowledgment of the shift. |
| 2 | Dropped qualitative content | Medium | "Modern, minimalist, browser-based" UI tone/identity from the brief has no NFR, requirement, or forward-pointer anywhere in the PRD. |
| 3 | Dropped qualitative content | Low-Medium | Brief's differentiation/competitive-landscape narrative (Java-ecosystem gap specifics, "honest moat" framing) is compressed to one generic clause in the PRD's Vision. |
| — | Verified consistent | — | Arbitrary file ingestion (plain text/PDF) scope: brief (post-update) and PRD agree. |
