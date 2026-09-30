# Forged idea: contextual "?" help system for GraphRAG Lens

**Purpose:** make the UI an educational demo. Reader and presenter are the same person, demoing many datasets, not just Sherlock Holmes. Be transparent.

## Locked decisions
- **Container:** docked, non-modal side pane. It stays open while the app is used. Another "?" swaps content in place. Esc or the close button dismisses it. It collapses to an overlay on narrow widths. Type is projector-legible.
- **Two layers per topic:**
  - Generic: header, abstract, extensive overview and hand-authored inline SVG. It is dataset-independent.
  - Live "in your data": the path from the last answer, drawn from the retrieval trace. It has an honest empty state and auto-follows new answers.
- **Content basis:** "The idea" (canonical GraphRAG) plus "In this demo" (what Lens actually does) plus an explicit simplifications callout. Reason: Local Search here is a toy (one keyword-matched seed, one hop, templated answer, no chunks, no LLM).
- **Live layer scope (v1):** Local, Global and Drift only. Every other topic is generic only.
- **Storage:** one HTML file per topic in `static/help/`. A `data-help` registry drives one small vanilla-JS module. No diagram generator, no CMS.
- **Drift guards:** (1) a UI test that every `data-help` maps to a topic file and vice versa; (2) each "In this demo" section names the classes it describes, with a test that they still exist. Future definition of done: changing retrieval behaviour means updating the topic.
- **Topics (17):** upload; demo and offline demo; ingestion progress; corpus chip and history switcher; KG overview; entity types and colours; communities; entity detail; search-mode chooser (comparison); Local; Global; Drift and its tree; reading an answer; Trace replay; Vector Space tab; vector baseline vs GraphRAG. No "?" on zoom, fit, close, send or entity search.
- **Authoring:** Claude drafts from the code, and the user reviews for accuracy.
- **Phasing:** P1 = pane, registry, guards, plus chooser, Local, Global and Drift articles with live layers. P2 = graph and looking-inside topics. P3 = data-in topics.

## Rejected
- Generic-only diagrams: they don't show real results.
- Live-only diagrams: they are empty at the start, and static examples would show Holmes on other datasets.
- Canonical-only content: it misrepresents what Lens does.
- Modal or closing pane: the audience loses the graph, and the presenter has to keep reopening it.
- Generated diagrams: too costly for the value.

## Known unknowns
- Do the offline demo's pre-recorded answers carry a retrieval trace? If not, the live layer is empty there. Verify in P1.
- Guard 2 proves only that the cited classes exist, not that the prose is true.
- Global's trace holds only community steps, so its live layer is thinner than Drift's.

## Done alongside
- Fixed the search-mode radio bug (`upload.js` `syncModeFromDom`; `RestoredSearchModeUiTest`).
