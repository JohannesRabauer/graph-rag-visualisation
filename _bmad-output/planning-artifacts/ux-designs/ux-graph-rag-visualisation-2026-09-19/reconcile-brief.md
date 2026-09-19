---
title: Reconciliation — Brief/Addendum vs. UX Spines (DESIGN.md / EXPERIENCE.md)
status: findings
created: 2026-09-19
scope: |
  Compares brief.md and addendum.md (source input) against DESIGN.md and
  EXPERIENCE.md (UX spines), looking for anything meaningful from the brief
  that the spines miss, contradict, or silently drop — with special attention
  to qualitative ideas (tone, differentiation narrative, pedagogical intent)
  that visual/behavioral specs tend to lose, and to the "captured, scrubbable
  replay" framing of the Retrieval Trace vs. literal real-time streaming.
note: This is a read-only reconciliation pass. No source or spine file was edited.
---

# Reconciliation Findings

## 1. Specifically-requested check: "captured, scrubbable replay" framing — CONSISTENT, no contradiction found

Brief's differentiation language (What Makes This Different):

> "Watching a query actually traverse a graph and pull from specific communities — captured from a genuinely live, non-scripted run and replayed step by step, scrubbable rather than one-shot — appears to be genuinely unclaimed territory."

Both spines treat the Retrieval Trace Replay exactly this way, not as literal real-time streaming:

- EXPERIENCE.md, Component Patterns table: "Replay is available only after generation completes — no live/streaming visualization (PRD FR-13, explicitly out of scope)."
- EXPERIENCE.md, Flow 1 (UJ-1), step 6: "The system answers, and a **captured, step-by-step Retrieval Trace** becomes available." — this is a near-verbatim echo of the brief's updated Vision/differentiation phrasing.
- EXPERIENCE.md, Component Patterns: "steps are discrete, not continuous time — there's no 'between steps' state," and the scrubber lets the user "drag ... to jump directly to the nearest step" — behaviorally this is a post-hoc, scrubbable artifact, not a live feed.
- DESIGN.md's scrubber/step-badge/replay-CTA components ("Replay this answer's Retrieval Trace — N steps") are all framed as replaying something already captured, never as a live/streaming view.

Note: EXPERIENCE.md does describe **ingestion** (Knowledge Graph construction) as live — "nodes/edges appearing as Entities/Relationships are extracted via the live LLM call" — but this is a different mechanism than the Retrieval Trace and is itself consistent with the brief's "genuinely live, non-scripted run" framing (the live run happens once; what gets replayed afterward is the captured trace of it). No contradiction between the two.

**Conclusion: no gap here.** This is the one check that comes back clean and worth stating explicitly since it was the most specific ask.

## 2. Gaps / tensions found

### Gap A — Community-detection-as-watchable-step vs. default-OFF community-visualization toggle

The brief's Solution section names visualizing community detection as a first-class deliverable, not an optional extra:

> "Runs community detection (Leiden-style clustering) on that graph and **visualizes the clustering itself as a distinct, watchable step — not just its output.**"

EXPERIENCE.md's Component Patterns table makes the corresponding toggle **default OFF**: "Community detection itself always runs in the background regardless of toggle state (FR-6) — the toggle controls only whether its *formation* is shown/animated (FR-7)." The spine's own rationale (demonstrating "with vs. without" live on stream) is reasonable UX thinking, but it does mean that on a fresh run, with no user action, the specific "watchable step" the brief calls out as differentiating solution behavior will *not* be shown by default — the creator has to remember to flip it on. This is a legitimate design tradeoff (documented, not accidental), but it sits in tension with the brief's framing of clustering-as-watchable being core, not incidental.

### Gap B — The brief's explicit differentiation narrative doesn't surface anywhere in either spine

The brief's "What Makes This Different" section is one of its most load-bearing pieces of qualitative content: nobody else visualizes live GraphRAG retrieval mechanics this way; the project is Java-native in a Python-dominated space; the moat is honestly framed as timing-and-effort, not technical. Neither DESIGN.md nor EXPERIENCE.md echoes any part of this — not in DESIGN.md's Brand & Style narrative (which frames the product only via the "instrument/oscilloscope" register and the "no-brainer to use" mandate), not in EXPERIENCE.md's Voice and Tone microcopy table, and not anywhere in the Key Flows. There's no tagline, about-state copy, or narrative beat anywhere in the product that would let a stream viewer who hasn't read the brief understand *why this project is notable* relative to existing tools. This may be an intentional scope call (the brief itself parks marketing/showcase material for later — see addendum's "Parked for Later"), but as written, the differentiation narrative is present in the source input and completely absent from both UX spines, which is exactly the kind of qualitative idea a visual/behavioral spec tends to lose.

### Gap C — EXPERIENCE.md introduces an entity "tags" concept with no antecedent in the brief/addendum

EXPERIENCE.md's Explore page node-click detail panel is described as showing "that Entity's connections (its Relationships), its details, and its **tags**." Neither brief.md nor addendum.md mentions entity tags anywhere — the brief's vocabulary is Entities, Relationships, Communities, Corpus (and EXPERIENCE.md's own Voice and Tone table names exactly those four as the canonical Glossary terms, with no fifth "tags" term). This is most likely inherited from the PRD (which EXPERIENCE.md lists as a source but which was out of scope for this reconciliation pass), but relative to the brief/addendum alone it's an unsourced addition, not something either source document asked for or anticipated.

### Gap D (minor) — The brief's "working title" caveat is silently dropped

The brief's title is explicitly hedged: "GraphRAG Lens *(working title — needs a real name)*." Both DESIGN.md and EXPERIENCE.md adopt "GraphRAG Lens" throughout as a settled, final product name with no trace of that caveat. This is very likely fine (the name was probably finalized during the intervening PRD work, per EXPERIENCE.md's sources list including the PRD), but neither spine documents that the naming question was resolved — a reader of only the four files in scope here would have to guess whether "GraphRAG Lens" is final or still a placeholder.

## Non-gaps checked and confirmed present/consistent

For completeness, these brief/addendum ideas were checked and found adequately carried through into the spines, so they are **not** listed as gaps:

- Tone: "solo hobby project," "passion project for its creator," "not a consumer product" — directly present in DESIGN.md's Brand & Style ("GraphRAG Lens is a scientific instrument, not a consumer product... a solo hobby project built by one developer to understand GraphRAG deeply enough to teach it live, on stream").
- Pedagogical goal ("watching GraphRAG mechanics unfold," ease-of-use as means not end) — directly quoted/paraphrased in both DESIGN.md ("the core design mandate... ease-of-use is not itself the differentiator to sell — it exists so that a live audience's attention... stays on GraphRAG mechanics") and EXPERIENCE.md's Voice and Tone section.
- "Nothing is scripted or cached... genuine LLM call against a genuine graph" — echoed in EXPERIENCE.md's Ingestion-in-progress state ("consistent with 'everything shown is real, non-scripted computation' from the brief's Vision") and the Flow 1 failure edge case ("the creator can narrate the failure itself as part of the 'everything here is real' premise").
- Accepted risk of live-demo LLM failure, no fallback/retry — matches brief's Scope exactly; reflected in FR-5 error-banner treatment and Flow 1's edge case.
- Local Search / Global Search as two distinct, both-visualized retrieval paths — matches brief's Solution point 3; reflected in the mode toggle, per-mode tagging, and canvas highlighting.
- Demo dataset (Sherlock Holmes) as default with user-upload as an equally-weighted alternative — matches brief Scope and addendum's dataset-options rationale; reflected in the Upload vs. Demo Dataset interaction primitive and corpus chip.
- Single local user, no auth/hosting/multi-tenancy, minimal setup friction — matches brief Scope; reflected in Foundation and Flow 2.
- Audience framing (primary: creator; secondary: stream viewers) — reflected in Flow 1 (creator + audience) and Flow 3 ("the creator or a curious viewer").
- Items the addendum explicitly parks for later (marketing site, polished README, app icon) — correctly absent from both spines; not a drop, since the source itself defers them.

## Summary for handback

4 gaps identified (A–D above); the specifically-requested Retrieval Trace Replay framing check came back **consistent**, not a gap.
