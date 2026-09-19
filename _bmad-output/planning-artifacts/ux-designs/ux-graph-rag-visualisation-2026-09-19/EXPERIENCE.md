---
title: GraphRAG Lens — Experience
status: draft
created: 2026-09-19
updated: 2026-09-19
sources:
  - _bmad-output/planning-artifacts/briefs/brief-graph-rag-visualisation-2026-09-19/brief.md
  - _bmad-output/planning-artifacts/briefs/brief-graph-rag-visualisation-2026-09-19/addendum.md
  - _bmad-output/planning-artifacts/prds/prd-graph-rag-visualisation-2026-09-19/prd.md
---

# GraphRAG Lens — Experience Spine

> Solo hobby project: one developer, one machine, live-streamed. Single-page web app plus one secondary page. Paired with `DESIGN.md` (Instrument direction). Decisions here are distilled from `.memlog.md` (11-entry UX discovery log).

## Foundation

Web, single-user, local-only (no auth, no hosting, no multi-tenancy — per PRD §5). Two surfaces total: the **main screen** (a single continuous page — chat + Knowledge Graph canvas + Retrieval Trace Replay, all on one screen with no gated setup step) and a second **Explore page** for free-form graph exploration, added during this UX pass and pending PRD reconciliation (per memlog). No component library is named; `DESIGN.md` is the visual identity reference in full — this spine specifies behavior only. No dark mode: `DESIGN.md`'s Instrument direction is light-mode-only by design (highest contrast for a teaching tool on stream).

There is deliberately no separate start/setup screen. The empty state — no Corpus ingested yet — lives directly on the main screen (see State Patterns), reinforcing the "no-brainer to use" mandate: opening the app *is* starting it.

## Information Architecture

| Surface | Reached from | Purpose |
|---|---|---|
| Main screen | App load (only entry point) | Choose/upload a Corpus, watch Knowledge Graph construction and Community detection, ask a question via Local Search or Global Search, view the answer, replay its Retrieval Trace |
| Explore page | Nav link/button from the main screen (exact placement is a layout, not a UX, decision) | Free-form pan/zoom/click exploration of the full Knowledge Graph and its Communities, independent of any query |

No modal stacking, no settings screen (API key is environment-variable-only per PRD FR-15, with no in-app configuration UI for v1). The main screen is a single continuous view — chat and graph are both live and visible while ingestion and Community detection run in the background; nothing gates the user behind a wizard step. The Explore page is reached via a simple persistent link/tab in the app bar. (Reconciling the Explore page as a formal PRD feature/FR is still pending — see the memlog and this pass's Finalize reconciliation step.)

→ Composition reference: `mockups/direction-instrument.html` (main-screen "Instrument" mockup, both the active-Replay state and the idle/resting-canvas state variant). Spine wins on conflict. The Explore page has no visual mock by choice — built from the spine tables above alone.

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

## Component Patterns

Behavioral rules. Visual specs live in `DESIGN.md.Components`.

| Component | Use | Behavioral rules |
|---|---|---|
| Chat panel | Main screen, left rail | Persistent thread of questions and answers. Available immediately, even while ingestion/Community detection are still running in the background (memlog: single continuous screen, not gated). |
| Local/Global Search toggle | Chat panel header | Explicit two-way toggle, no automatic routing (PRD FR-9/FR-10 — a deliberate choice so both retrieval paths can be demonstrated side by side). Exactly one mode active per query; switching updates the inline explanatory hint immediately. Selected mode is recorded on the Retrieval Trace (FR-9/FR-10 consequences). |
| Community-visualization toggle | Main screen, near/above the graph canvas | **Defaults ON for a fresh Corpus's first run** — so the signature "Communities folding into clusters" moment plays automatically rather than depending on the creator remembering to enable it, honoring the brief's "distinct, watchable step" framing. Community detection itself always runs in the background regardless of toggle state (FR-6) — the toggle controls only whether its *formation* is shown/animated (FR-7). After that first run, the creator can freely toggle it OFF/ON to demonstrate "with vs. without" live. Toggling does not re-run detection. |
| Retrieval Trace replay scrubber | Below the graph canvas, appears once an answer's Retrieval Trace (FR-12) is captured | Play/pause toggles autoplay through trace steps; step-forward/step-back move exactly one step per press. Dragging the scrubber head jumps directly to the nearest step (steps are discrete, not continuous time — there's no "between steps" state). Current step always shows a plain-language caption of what happened at that step (see `DESIGN.md.scrubber`). Replay is available only after generation completes — no live/streaming visualization (PRD FR-13, explicitly out of scope). |
| Explore page — always-on community view | Explore page canvas | Communities are always visible here (no toggle, unlike the main screen) — this page's entire purpose is free structural exploration, so hiding Community structure would work against it. |
| Explore page — node-click detail panel | Explore page, triggered by clicking any node | Clicking a node opens a detail view showing that Entity's connections (its Relationships), its details, and its tags. Independent of any query — this is browsing the Knowledge Graph itself, not asking it a question. |

## State Patterns

| State | Surface | Treatment |
|---|---|---|
| Idle / empty (no Corpus yet) | Main screen | Lives on the main screen itself, no separate setup screen. Resting canvas shows the idle-state copy pattern ("Knowledge Graph — Resting") with an eyebrow label and plain-language prompt, plus the choice to upload a Corpus (FR-1/FR-2) or pick the Demo Dataset (FR-3) — see Interaction Primitives. |
| Ingestion in progress | Main screen (chat + canvas both live) | Knowledge Graph construction (FR-4) visibly builds — nodes/edges appearing as Entities/Relationships are extracted via the live LLM call. Chat remains usable; a question submitted mid-ingestion proceeds against whatever graph state exists at that moment, rather than queuing or being rejected — consistent with "everything shown is real, non-scripted computation" from the brief's Vision. |
| Community-detection in progress | Main screen canvas | Runs in the background regardless of toggle state (FR-6). If the community-visualization toggle is ON, the clustering animates visibly (nodes folding into hulls, FR-7) as it happens; if OFF, no animation is shown even though detection is still running underneath. |
| Upload rejected | Main screen, at the idle/upload state | An unsupported file extension, or a PDF yielding no extractable text, produces a plain-language error naming the specific problem (e.g. "That file type isn't supported — plain text or PDF only" / "No text could be read from that PDF — is it a scanned image?") using the error-banner pattern from `DESIGN.md`, never a silent no-op (FR-1/FR-2 consequences). |
| LLM-call failure | Main screen (chat or canvas, wherever the failing call was scoped) | Explicit, visible error state — never a silent hang, crash, or automatic retry (PRD FR-5, NFR reliability, scoped there to Knowledge Graph construction). This spine generalizes the same principle to a failure during answer generation, since it's the same class of live LLM call — plain-language message naming what failed (extraction vs. answer generation) using the error-banner pattern from `DESIGN.md`. |
| No-answer-found | Chat panel + canvas, as the answer for that query | Local Search: neighborhood traversal from the matched Entities yields nothing relevant → visible "no answer found" message, never an empty or misleading response (FR-9 consequence). Global Search: no Communities exist yet (detection hasn't completed) → same visible "no answer found" treatment (FR-10 consequence). Both states name *why* in plain language rather than failing silently. The Retrieval Trace is still captured and its Replay CTA still appears — seeing *where* the search looked and came up empty is itself part of the teaching value, not something to hide. |
| Populated / answered | Chat panel + canvas | Answer renders in chat (FR-11) tagged with its search mode; canvas highlights the touched Entities/Relationships/Communities; Replay CTA appears offering the step-by-step Retrieval Trace. |
| Explore page — before any node selected | Explore page | Full Knowledge Graph and its Communities rendered and pannable/zoomable immediately; no query needed to populate it (it reads the already-constructed graph from the main screen's session). |

## Interaction Primitives

- **Click a node** — on the graph canvas during/after a Replay, or on the Explore page: opens that Entity's detail (Explore page) or highlights it in context (main-canvas Replay).
- **Toggle switches** — Local/Global Search and the community-visualization toggle are both binary, single-click, immediate-effect controls with no confirmation step; each carries an inline plain-language explanation per the Voice and Tone rule.
- **Scrubber drag / step** — drag the scrubber head to jump to the nearest discrete trace step; use step-forward/step-back for exactly one step at a time; play/pause autoplays through remaining steps. This fixes the control set the PRD deferred to this UX pass (play/pause + single-step forward/back + drag-to-nearest-step, matching the mockup's transport row); autoplay speed and keyboard shortcuts are left as an implementation-level detail below this spine's altitude.
- **Upload vs. Demo Dataset choice** — presented together at the idle state as two equally-weighted paths into the same pipeline (upload one or more `.txt`/PDF files, FR-1/FR-2, or one-click the built-in Sherlock Holmes Demo Dataset, FR-3); neither is the "default" or visually primary option, since the Demo Dataset exists specifically to make first use a "no-brainer."

## Accessibility Floor

Behavioral floor only; visual contrast and palette live in `DESIGN.md`. Per the memlog, this is intentionally a **basic floor, not a compliance program** — the project is a solo hobby build for one developer's own machine and stream, not a shipped product with an accessibility mandate.

- Colorblind-safe Community palette is a **soft goal, best-effort** (memlog, explicit) — `DESIGN.md`'s five-hue categorical set (orange/blue/green/purple/gold) was chosen for better default separability, but it is not validated against a simulator and is not a hard blocker on ship.
- Basic contrast floor: body text and control labels should read clearly against their surfaces at normal viewing/streaming distance — no glass-morphism, no low-contrast placeholder-on-placeholder text.
- Basic keyboard floor: primary actions (submit a question, toggle Local/Global Search, toggle community visualization, scrubber play/pause and step) should be reachable without requiring precise mouse interaction, since the creator may narrate hands-off at points during a stream. Exact tab order and keybindings are left as an implementation detail, consistent with the project's scale.
- No screen-reader-specific requirements are called out in the memlog, PRD, or brief; none are asserted here beyond standard semantic HTML as a baseline.

## Key Flows

### Flow 1 — UJ-1: Explaining GraphRAG live (the creator, mid coding-stream, audience watching)

1. The app is already running (see Flow 2); the creator is on the main screen, live on stream.
2. They upload a document set or pick the Sherlock Holmes Demo Dataset.
3. The canvas shows ingestion building the Knowledge Graph — Entities and Relationships appearing as the live LLM call extracts them.
4. Community detection runs; the creator has the community-visualization toggle ON to narrate the clustering as it happens, then flips it OFF and back ON to contrast "with vs. without" for the audience.
5. The creator types a question into the chat interface and explicitly picks Local Search or Global Search via the toggle.
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

1. From the main screen, after a Corpus has been ingested and Communities detected, the person navigates to the Explore page.
2. The full Knowledge Graph renders immediately, Communities always visible as grouped/colored clusters (no toggle needed — this page has nothing to hide).
3. They pan and zoom freely across the graph, independent of any question or query.
4. They click a node.
5. **Climax:** a detail panel opens showing that Entity's connections, details, and tags — the graph becomes something you can wander through and inspect on its own terms, not just something that lights up in response to a query. This is the moment the Explore page exists for: structural curiosity, satisfied directly.
6. They click another node to keep exploring, or return to the main screen to ask a targeted question informed by what they just saw.

**Edge case:** no Corpus has been ingested yet — the Explore page shows a plain-language empty state pointing back to the main screen's ingestion entry point, consistent with the "no-brainer to use" mandate, rather than a blank or broken canvas.
