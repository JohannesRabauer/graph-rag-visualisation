---
title: GraphRAG Lens — Experience
status: final
created: 2026-09-19
updated: 2026-09-20
sources:
  - _bmad-output/planning-artifacts/briefs/brief-graph-rag-visualisation-2026-09-19/brief.md
  - _bmad-output/planning-artifacts/briefs/brief-graph-rag-visualisation-2026-09-19/addendum.md
  - _bmad-output/planning-artifacts/prds/prd-graph-rag-visualisation-2026-09-19/prd.md
---

# GraphRAG Lens — Experience Spine

> Solo hobby project: one developer, one machine, live-streamed. Single-page web app. Paired with `DESIGN.md` (Instrument direction). Decisions here are distilled from `.memlog.md` (12-entry UX discovery log).

## Foundation

Web, single-user, local-only (no auth, no hosting, no multi-tenancy — per PRD §5). One surface total: the **main screen** — a single continuous page combining chat, Knowledge Graph canvas, Retrieval Trace Replay, and free-form pan/zoom/click graph exploration (PRD §4.7, FR-16/FR-17), all live on the one screen with no gated setup step. **Merged 2026-09-20**: a separate Explore page existed earlier in this UX pass, duplicating a slightly more capable version of the main screen's own canvas (pan/zoom and a node-click detail panel) behind a second page and a second navigation step; it was folded into the main screen so there is exactly one canvas, always interactive, with the detail panel and Retrieval Trace Replay both available on it at once (see Component Patterns and Key Flow 3). No component library is named; `DESIGN.md` is the visual identity reference in full — this spine specifies behavior only. No dark mode: `DESIGN.md`'s Instrument direction is light-mode-only by design (highest contrast for a teaching tool on stream).

The empty state — no Corpus ingested yet — lives directly on the main screen (see State Patterns), reinforcing the "no-brainer to use" mandate: opening the app *is* starting it.

## Information Architecture

| Surface | Reached from | Purpose |
|---|---|---|
| Main screen | App load (only entry point) | Choose/upload a Corpus, watch Knowledge Graph construction and Community detection, ask a question via Local Search, Global Search, or DRIFT (v1.1), view the answer, replay its Retrieval Trace, and freely pan/zoom/click the same canvas to inspect any Entity's connections and Tags — independent of any query |
| Vector Space tab (v1.1) | A tab switch next to "Knowledge Graph" on the main screen's canvas, appearing once a Vector Baseline has been triggered via the Compare CTA on an answer | Replay the Vector Baseline's own trace for that answer — corpus chunking, embedding, query embedding, similarity-ranked retrieval — as a 2D embedding-space scatter, directly comparable to the Knowledge Graph tab's GraphRAG trace for the same question |

No modal stacking, no settings screen (API key is environment-variable-only per PRD FR-15, with no in-app configuration UI for v1). The main screen is a single continuous view — chat and graph are both live and visible while ingestion and Community detection run in the background; nothing gates the user behind a wizard step. The canvas itself is always pannable, zoomable, and clickable — there is no separate mode or page to reach that capability.

→ Composition reference: `mockups/direction-instrument.html` (main-screen "Instrument" mockup, both the active-Replay state and the idle/resting-canvas state variant). Spine wins on conflict.

## Voice and Tone

Microcopy only. Brand posture and visual voice live in `DESIGN.md`. The governing rule, straight from the memlog: ease-of-use is not the differentiator to sell for its own sake — it exists so attention stays on teaching GraphRAG mechanics. Copy is tutorial-clear: plain sentences, no unexplained jargon, and every mode/toggle gets an inline one-line explanation of what it actually does mechanically.

The idle/empty state's subtitle carries the brief's differentiation in one line, since it's the first thing anyone reads: **"Watch a Knowledge Graph get built, clustered, and searched — the mechanics most GraphRAG tools keep hidden."** It's the only place the "why this is notable" narrative gets a direct echo; everywhere else, the app teaches by showing, not by claiming.

| Do | Don't |
|---|---|
| "Local Search traverses specific Entities and Relationships around your question." | "Local Search: entity-level RAG." |
| "Corpus ingested, Communities detected. Ask a question to see the graph light up." | "Ready!" |
| "Building the Knowledge Graph — extracting Entities and Relationships…" | "Processing…" |
| "The LLM call failed (rate limit or API error). Nothing was retried — try again when ready." | "Error 429" or a silent hang |
| "No answer found — Local Search couldn't reach a relevant Entity for this question." | An empty chat bubble or a generic "no results" |
| Name the Glossary term every time (Entity, Relationship, Community, Corpus) | Paraphrase Glossary terms inconsistently across screens |
| "DRIFT runs a community pass, spawns targeted sub-questions, then re-ranks and synthesizes." (v1.1) | "DRIFT: hybrid search." |
| "Compare with Vector Search — see the same question answered by plain similarity retrieval." (v1.1) | "Try vector mode" |

## Component Patterns

Behavioral rules. Visual specs live in `DESIGN.md.Components`.

| Component | Use | Behavioral rules |
|---|---|---|
| Chat panel | Main screen, left rail | Persistent thread of questions and answers. Available immediately, even while ingestion/Community detection are still running in the background (memlog: single continuous screen, not gated) — the panel itself is never hidden or disabled. **Submission is a separate matter:** see State Patterns' "Ingestion in progress" row — a question can't actually be submitted (409, plain-language guidance) until the Corpus reaches Ready. |
| Local/Global/Drift Search choice | Directly above the composer (**moved 2026-09-20**, was the chat panel header), three-way as of v1.1 | Explicit radio choice (**restyled 2026-09-20** from tab-styled segmented buttons, which read as "different pages" rather than a mode for the next question — every mode shares one chat thread), no automatic routing (PRD FR-9/FR-10/FR-18 — a deliberate choice so all retrieval paths can be demonstrated side by side). Exactly one mode active per query; switching updates the inline explanatory hint immediately. Selected mode is recorded on the Retrieval Trace. |
| DRIFT Replay (v1.1) | Below the graph canvas, same slot as the linear Retrieval Trace scrubber, when the answer's mode was DRIFT | Canvas shows the branching tree (`DESIGN.md.components.drift-tree`): community pass → fanned sub-question branches → convergence into re-rank/synthesize. Transport controls (step/back/play/pause) are unchanged, but step through the tree in one **fixed traversal order**: community pass, then each branch in the order it was spawned, then the final convergence step — never a user-chosen branch order. A branch not yet reached renders as the existing "upcoming" edge treatment; a resolved branch keeps a small "✓ resolved" caption once passed. |
| Vector Space tab (v1.1) | Main screen canvas, alongside Knowledge Graph, once a Compare CTA has been triggered | Chunk dots render immediately at their settled (ingestion-time) 2D positions — the projection is computed once per Corpus and never recomputed per query, so switching between questions never reshuffles the layout. On Replay, the query dot appears at its step, then its top-k neighbors highlight with similarity-score labels, matching the same step-by-step reveal the Knowledge Graph tab already uses. Coexists with the node-detail panel and Retrieval Trace scrubber the same way they coexist with each other (see the merged-canvas rows below) — switching to this tab never closes either. |
| Compare CTA (v1.1) | Chat panel, on any answer that already shows a Replay CTA | On-demand only — triggers the Vector Baseline for that exact question, never run automatically alongside the GraphRAG answer (avoids doubling LLM/embedding cost by default). Clicking it runs the Vector Baseline, then reveals the Vector Space tab with its own Replay. |
| Community-visualization toggle | Main screen, near/above the graph canvas | A real checkbox (**restyled 2026-09-20** from a switch styled to look like a toggle, which read as "this changes backend behavior" and invited the question of whether it gates Community detection — it never has). **Defaults ON for a fresh Corpus's first run** — so the signature "Communities folding into clusters" moment plays automatically rather than depending on the creator remembering to enable it, honoring the brief's "distinct, watchable step" framing. Community detection itself always runs in the background regardless of toggle state (FR-6) — the toggle controls only whether its *formation* is shown/animated (FR-7), and its label now says so directly. After that first run, the creator can freely toggle it OFF/ON to demonstrate "with vs. without" live. Toggling does not re-run detection. |
| Retrieval Trace replay scrubber | Below the graph canvas, appears once an answer's Retrieval Trace (FR-12) is captured | Play/pause toggles autoplay through trace steps; step-forward/step-back move exactly one step per press. Dragging the scrubber head jumps directly to the nearest step (steps are discrete, not continuous time — there's no "between steps" state). Current step always shows a plain-language caption of what happened at that step (see `DESIGN.md`'s `components.scrubber`). Replay is available only after generation completes — no live/streaming visualization (PRD FR-13, explicitly out of scope). **Added 2026-09-20:** a step touching a Community is guaranteed visible on the canvas for the duration it is current or previous, even if the community-visualization toggle is currently OFF — otherwise the step would advance with nothing to show for it. Only the specific hull(s) a step touches are forced visible; every other hidden hull stays hidden, so the toggle's "with vs. without" comparison still works once Replay moves past that step. **Merged 2026-09-20:** Replay and the Entity detail panel (below) are independent and coexist on the same canvas — opening one never closes the other (explicit UX decision, not a default from either component's own logic). |
| Main-screen canvas — node-click detail panel (**merged in 2026-09-20** from the former separate Explore page) | Main screen, triggered by clicking any Entity node on the graph canvas, at any time — during ingestion, mid-Replay, or idle | Clicking a node opens a detail panel showing that Entity's connections (its Relationships), its type, and its Tags (FR-17). Clicking the same node again closes it; clicking a different node swaps the panel's contents directly, no separate close step needed. Clicking a Community hull is a distinct action (focuses/fits that Community) and never opens or closes the detail panel. Independent of any query — this is browsing the Knowledge Graph itself, not asking it a question — and independent of Replay, which can be open on the same canvas at the same time (see the Replay row above). |

## State Patterns

| State | Surface | Treatment |
|---|---|---|
| Idle / empty (no Corpus yet) | Main screen | Lives on the main screen itself, no separate setup screen. Resting canvas shows the idle-state copy pattern ("Knowledge Graph — Resting") with an eyebrow label and plain-language prompt, plus the choice to upload a Corpus (FR-1/FR-2) or pick the Demo Dataset (FR-3) — see Interaction Primitives. |
| Ingestion in progress | Main screen (chat + canvas both live) | Knowledge Graph construction (FR-4) visibly builds — nodes/edges appearing as Entities/Relationships are extracted via the live LLM call. **Superseded 2026-09-20** (per `_bmad-output/implementation-artifacts/spec-demo-ready-showcase-workflow.md`): a question submitted mid-ingestion is now blocked with a plain-language "graph still building" message, and no backend query call is made — the demo-workflow-hardening pass (`spec-demo-ready-showcase-workflow.md`) traded the original "mid-ingestion queries proceed against a partial graph" allowance for presenter reliability. Confirmed as the intended, kept behavior in this UX pass. `ARCHITECTURE-SPINE.md` now documents this precisely: AD-14 is narrowed to the guarantee that still holds (the read use cases themselves never lock once invoked) and AD-16 records the new web-layer readiness gate that decides whether they're invoked at all. |
| Community-detection in progress | Main screen canvas | Runs in the background regardless of toggle state (FR-6). If the community-visualization toggle is ON, the clustering animates visibly (nodes folding into hulls, FR-7) as it happens; if OFF, no animation is shown even though detection is still running underneath. |
| Upload rejected | Main screen, at the idle/upload state | An unsupported file extension, or a PDF yielding no extractable text, produces a plain-language error naming the specific problem (e.g. "That file type isn't supported — plain text or PDF only" / "No text could be read from that PDF — is it a scanned image?") using the error-banner pattern from `DESIGN.md`, never a silent no-op (FR-1/FR-2 consequences). |
| LLM-call failure | Main screen (chat or canvas, wherever the failing call was scoped) | Explicit, visible error state — never a silent hang, crash, or automatic retry (PRD FR-5, NFR reliability, scoped there to Knowledge Graph construction). This spine generalizes the same principle to a failure during answer generation, since it's the same class of live LLM call — plain-language message naming what failed (extraction vs. answer generation) using the error-banner pattern from `DESIGN.md`. |
| No-answer-found | Chat panel + canvas, as the answer for that query | Local Search: neighborhood traversal from the matched Entities yields nothing relevant → visible "no answer found" message, never an empty or misleading response (FR-9 consequence). Global Search: no Communities exist yet (detection hasn't completed) → same visible "no answer found" treatment (FR-10 consequence). Both states name *why* in plain language rather than failing silently. The Retrieval Trace is still captured and its Replay CTA still appears — seeing *where* the search looked and came up empty is itself part of the teaching value, not something to hide. |
| Populated / answered | Chat panel + canvas | Answer renders in chat (FR-11) tagged with its search mode; canvas highlights the touched Entities/Relationships/Communities; Replay CTA appears offering the step-by-step Retrieval Trace. |
| Before any node selected | Main-screen canvas | Once ingestion has produced any graph, it is already rendered and pannable/zoomable/clickable — no query and no separate page needed to browse it; the same canvas that shows ingestion progress and Replay is the one the person clicks around on. |
| DRIFT — no viable sub-questions (v1.1) | Chat panel + canvas | The community pass yields nothing to spawn sub-questions from → same visible "no answer found" treatment as Local/Global (FR-18 consequence), naming DRIFT specifically. The (partial) trace up to that point is still captured and replayable — same "showing where it looked" teaching value as the existing no-answer states. |
| Vector Baseline running (v1.1) | Vector Space tab | Triggered by the Compare CTA; the tab shows a brief in-progress state (chunk dots already visible and static, query dot not yet placed) while embedding + similarity search run, then transitions straight into the populated Replay-ready state — no separate "loading" screen beyond that. |

## Interaction Primitives

- **Click a node** — on the main screen's own canvas, at any time: opens that Entity's detail panel (connections, type, Tags). Independent of whether a Replay is currently open on the same canvas — the two never interfere with each other.
- **Toggle switches** — Local/Global Search and the community-visualization toggle are both binary, single-click, immediate-effect controls with no confirmation step; each carries an inline plain-language explanation per the Voice and Tone rule.
- **Scrubber drag / step** — drag the scrubber head to jump to the nearest discrete trace step; use step-forward/step-back for exactly one step at a time; play/pause autoplays through remaining steps. This fixes the control set the PRD deferred to this UX pass (play/pause + single-step forward/back + drag-to-nearest-step, matching the mockup's transport row); autoplay speed and keyboard shortcuts are left as an implementation-level detail below this spine's altitude.
- **Upload vs. Demo Dataset choice** — presented together at the idle state as two equally-weighted paths into the same pipeline (upload one or more `.txt`/PDF files, FR-1/FR-2, or one-click the built-in Sherlock Holmes Demo Dataset, FR-3); neither is the "default" nor the visually primary option, since the Demo Dataset exists specifically to make first use a "no-brainer."
- **Tab switch — Knowledge Graph / Vector Space (v1.1)** — single click, immediate, no confirmation; switching never re-triggers computation on either side (the graph trace and vector trace are both already captured by the time the tab exists).
- **Compare CTA click (v1.1)** — one click triggers the Vector Baseline for that specific answer's question; never automatic, never retroactive to older answers unless clicked on each.
- **DRIFT branch traversal (v1.1)** — step-forward/step-back move through the fixed order (community pass → branch 1 → branch 2 → … → convergence); there is no direct "jump to branch N" control beyond the existing scrubber-drag-to-nearest-tick behavior, kept consistent with the linear Retrieval Trace scrubber's existing primitive.

## Accessibility Floor

Behavioral floor only; visual contrast and palette live in `DESIGN.md`. Per the memlog, this is intentionally a **basic floor, not a compliance program** — the project is a solo hobby build for one developer's own machine and stream, not a shipped product with an accessibility mandate.

- Colorblind-safe Community palette is a **soft goal, best-effort** (memlog, explicit) — `DESIGN.md`'s five-hue categorical set (orange/blue/green/purple/gold) was chosen for better default separability, but it is not validated against a simulator and is not a hard blocker on ship.
- Basic contrast floor: body text and control labels should read clearly against their surfaces at normal viewing/streaming distance — no glass-morphism, no low-contrast placeholder-on-placeholder text.
- Basic keyboard floor: primary actions (submit a question, toggle Local/Global/Drift Search, toggle community visualization, scrubber play/pause and step, the Compare CTA, and the Knowledge Graph / Vector Space tab switch — v1.1 additions held to the same floor as everything else) should be reachable without requiring precise mouse interaction, since the creator may narrate hands-off at points during a stream. Exact tab order and keybindings are left as an implementation detail, consistent with the project's scale.
- No screen-reader-specific requirements are called out in the memlog, PRD, or brief; none are asserted here beyond standard semantic HTML as a baseline.

## Key Flows

### Flow 1 — UJ-1: Explaining GraphRAG live (the creator, mid coding-stream, audience watching)

1. The app is already running (see Flow 2); the creator is on the main screen, live on stream.
2. They upload a document set or pick the Sherlock Holmes Demo Dataset.
3. The canvas shows ingestion building the Knowledge Graph — Entities and Relationships appearing as the live LLM call extracts them.
4. Community detection runs; the creator has the community-visualization toggle ON to narrate the clustering as it happens, then flips it OFF and back ON to contrast "with vs. without" for the audience.
5. The creator types a question into the chat interface and explicitly picks Local Search or Global Search via the radio choice above the composer.
6. The system answers, and a captured, step-by-step Retrieval Trace becomes available.
7. The creator scrubs back and forth through the Replay, narrating which nodes and Communities were touched at each step.
8. **Climax:** the audience — and the creator — can see exactly which part of the graph produced the answer, not just that an answer arrived. The creator points at the highlighted Entity/Relationship path on screen and explains, with the Retrieval Trace as evidence, *why* GraphRAG produced that specific answer.
9. The final answer sits in the chat thread as the resolution, with the trace still scrubbable for follow-up questions from chat.

**Edge case / failure:** a live LLM call fails mid-run (rate limit, API error). No retry or cached fallback is attempted, by design (FR-5). The failure surfaces as a clear, visible error banner naming what failed — never a crash or silent hang — so the creator can narrate the failure itself as part of the "everything here is real" premise rather than scrambling to explain a frozen screen.

### Flow 2 — UJ-2: Setting up before a stream (the creator, alone, pre-stream)

1. The creator clones the repo.
2. Runs a single Docker Compose command, which provisions Neo4j and any other required infrastructure automatically (FR-14).
3. Sets the `OPENAI_API_KEY` environment variable — the one manual step (FR-15); there is no in-app configuration UI to fill in instead.
4. **Climax:** the creator opens the main screen in a browser and it's already the idle/empty state described above — ready to upload a Corpus or pick the Demo Dataset — with no further setup screen, wizard, or account step standing between "app is running" and "app is usable on stream."
5. No dry run is required by this flow; the app being reachable at all is the resolution.

### Flow 3 — UJ-3: Freely exploring the Knowledge Graph (the creator or a curious viewer, off-query, post-ingestion)

1. On the main screen, once a Corpus has been ingested, the Knowledge Graph is already rendered on the same canvas the person has been watching — no navigation step, no second page.
2. They pan and zoom freely across the graph, independent of any question or query. If the community-visualization toggle is ON, Communities show as grouped/colored clusters; either way, nothing about exploring the graph is gated behind a query.
3. They click a node.
4. **Climax:** a detail panel opens on top of the same canvas, showing that Entity's connections, type, and Tags — the graph becomes something you can wander through and inspect on its own terms, not just something that lights up in response to a query. This works identically whether or not a Retrieval Trace Replay happens to be open on the canvas at the same time (**merged 2026-09-20** — see Component Patterns).
5. They click another node to keep exploring, or type a targeted question into the chat composer informed by what they just saw, without leaving the screen.

**Edge case:** no Corpus has been ingested yet — the canvas shows the idle/empty state (see State Patterns) pointing at the upload/Demo-Dataset entry point, consistent with the "no-brainer to use" mandate, rather than a blank or broken canvas.
