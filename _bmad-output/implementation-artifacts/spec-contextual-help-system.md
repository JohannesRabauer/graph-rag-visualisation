---
title: 'Contextual help system (docked "?" pane, 17 topics, live in-your-data layer)'
type: 'feature'
created: '2026-09-30'
status: 'done'
baseline_revision: '3f9c8cacd2d338c0fde25735e8db097b734d66f1'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/forge/contextual-help-system/forged-idea.md'
warnings: [multiple-goals, oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** The GraphRAG Lens UI is used as a live demo and teaching tool, but nothing in it explains what its areas and controls are, or how each search mode really retrieves. The presenter has to narrate everything.

**Approach:** Add a docked, non-modal help pane opened from "?" buttons (`data-help="<topic>"`). Each topic is a static HTML file with two layers: a generic layer (header, abstract, overview, hand-authored inline SVG; "The idea" / "In this demo" / simplifications) and a live "in your data" layer built from the last answer's retrieval trace. Phases in order: P1 pane + registry + guards + chooser/Local/Global/Drift articles with live layer (ships alone); P2 graph and looking-inside topics; P3 data-in topics. Full design: the forged-idea.md in `context`.

## Boundaries & Constraints

**Always:** Vanilla JS in one new module (`help.js`, IIFE, `window.Help`); topics are files under `static/help/<topic>.html` fetched from `/help/<topic>.html`; no generator, CMS, or new dependency. Help pane is a flex sibling after `.graph-stage` inside `main.canvas` (graph re-fits via its ResizeObserver) and collapses to an overlay below 960px. Pane stays open across other "?" clicks (content swaps), Esc or close dismisses, focus returns to the invoking "?" button; "?" buttons have `aria-label` and never trigger the control they sit next to. Projector-legible type (>= 16px body). "In this demo" text must match the code: name the real classes (AnswerLocalSearch etc.) inside `<code data-class="...">`. Live layer auto-follows each new answer for the open topic. Match existing CSS tokens (`--space-*`, `--ink-*`, `--accent`, light theme only).

**Never:** "?" on zoom, fit, close, send or entity search. No live layer for Vector Space or the vector baseline in v1 (generic only). No claim that the offline demo has recorded answers, since it has none (composer disabled, queries 409). No push. No changes to search/answer semantics or HTTP contracts, no new backend endpoints.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Open topic | Click a "?" | Pane opens docked, graph canvas width shrinks, article rendered with SVG | Fetch fails: pane shows "help unavailable" text, no console throw |
| Swap topic | Pane open, click another "?" | Content replaced in place, pane stays open | none |
| Dismiss | Esc or close button | Pane hidden, focus back on invoking "?" | none |
| Live layer, no answer yet | Topic open, no answer in session | Empty state "Ask a question to see this in your data" | none |
| Live layer, answer arrives | Local/Global/Drift answer while its topic is open | Layer redraws from `GET /api/traces/{traceId}` (Local: seed -> relationship -> other; Global: all communities, best marked; Drift: community -> sub-question -> local steps -> synthesis) | Trace 404 or zero steps: no-match text ("this mode found nothing to trace") |
| Offline demo | Offline corpus active | Articles open; live layer shows offline note (no answers possible in offline mode) | none |
| Corpus reset/switch | Restart or activate other corpus | Last-answer state cleared, live layer returns to empty state | none |
| Narrow viewport | Width <= 960px | Pane overlays with visible close control | none |
| Registry drift | Test scan | Every `data-help` value has a topic file and every topic file is referenced | Guard 1 fails the build |
| Class drift | Test scan | Every `data-class` name resolves to an existing Java class | Guard 2 fails the build |

</intent-contract>

## Code Map

Paths under `graphrag-web/src/main/resources` (`res`) and `graphrag-web/src/test/java/com/graphraglens/web` (`test`); core under `graphrag-core/src/main/java/io/graphrag/core`.

- `res/templates/index.html` -- add `<aside id="help-pane" class="help-pane" hidden>` after `.graph-stage` (inside `main.canvas`, L32-); "?" buttons next to: `.mode-choice`, `#canvas-tab-bar` tabs, `#canvas-settings-toggle`, `#corpus-chip`, `#corpus-history-toggle`, idle upload/`#demo-dataset-button`/`#demo-offline-button`, `#workflow-status`, `#graph-legend`, `#entity-detail-panel`, `#replay-scrubber`, `#drift-tree`, `#vector-space-panel`, answer "Compare"; scripts at ~L193-199, load `help.js` after upload.js.
- `res/static/css/instrument.css` -- `.canvas` L360 (flex row), `.chat-panel` L1124 (width 340, `[hidden]` display none) is the sizing model; `.graph-stage` L374 `flex:1;min-width:0`; breakpoints L1911 (960) and L1943 (640); z-index map: settings popover 6, history popover 20. Tokens `:root` L16-111 (`--space-10` used L1773 but undefined; avoid).
- `res/static/js/upload.js` -- private closure state `activeCorpusId`, `activeCorpusReady`, `activeCorpusOffline`, `currentSearchMode` (L44-47); `appendAnswer(...)` L1107-1175 builds `.message.answer[data-mode][data-corpus-id]` + `.replay-cta[data-trace-id]`; reset/switch choke points `resetToIdleState` L792, `showCorpusChip` L918, `activateCorpus` L1430; offline flag L941. Add `document.dispatchEvent(new CustomEvent('graphrag:answer'|'graphrag:corpus', ...))` at these points (no CustomEvents exist today; no getters).
- `res/static/js/replay.js` -- `/api/traces/{id}` fetch pattern (L120-125), verb captions L358-363; reuse the fetch shape only.
- `res/static/js/drift-tree.js:30-36` -- Drift trace grouping (SUB_QUESTION_SPAWNED opens a branch, first SYNTHESIS closes); `graph-canvas.js` L451-490 ResizeObserver re-fit, edge id convention `sourceIdentity->TYPE->targetIdentity`.
- Backend facts (read-only, do not change): query JSON `{answerId, traceId, traceStepCount, answer, mode}`, or `noAnswer:true, reason` (no `mode`); trace only via `GET /api/traces/{traceId}` -> `{steps:[{kind,identifier,label}]}`; no TTL. Static `res/static/**` served by default Spring Boot, no security config.
- `core/usecase/AnswerLocalSearch.java` -- keyword-scored (KeywordMatcher: +2 substring, +1 fuzzy Levenshtein; no LLM, no embeddings) single best seed entity over `name + type`, one best relationship hop; steps ENTITY, RELATIONSHIP (`src->TYPE->tgt`), ENTITY; no match -> empty steps; templated answer.
- `core/usecase/AnswerGlobalSearch.java` -- reads persisted communities only; one COMMUNITY step per community (id, summary label); best-scored summary wins (ties: smaller id); none -> `noAnswer` with reason; no LLM.
- `core/usecase/AnswerDriftSearch.java` -- COMMUNITY steps for all; tied-best communities as candidates; `LlmPort.deriveDriftSubQuestions` (default template, also with real OpenAI adapter); per branch SUB_QUESTION_SPAWNED(parentId = community id) + a full Local search's steps; one SYNTHESIS (no LLM); no viable community -> `noAnswer`.
- `core/usecase/AnswerVectorBaseline.java` -- embeds question, cosine over all chunks, TOP_K 5; steps VECTOR_QUERY_EMBEDDED, VECTOR_CHUNK (`score=`), SYNTHESIS; reached only via per-answer Compare button (radios offer LOCAL/GLOBAL/DRIFT).
- `core/domain/RetrievalTrace.java`, `RetrievalStep.java` -- step model (Kind enum names above).
- `web/CorpusController.java` -- offline demo is `POST /api/corpora/demo-offline`: same Sherlock corpus built with deterministic stubs, queries 409, excluded from history list; no recorded answers exist. Real LLM is used only for extraction and community summaries (`OpenAiLlmPort` overrides only `extract`, `summarizeCommunity`); stub (no `OPENAI_API_KEY`): regex extraction, hashed bag-of-words 64-d embeddings.
- `test/ui/UiTestSupport.java` -- base for real-browser tests: `page`, `baseUrl()`, `loadDemoDatasetAndWaitReady()`; no ask-question or offline helper, so inline: fill `#chat-input`, click `#chat-form .send-button`; offline: click `#demo-offline-button`. Mirror `OfflineDemoUiTest`, `MainScreenLayoutUiTest` (`GraphCanvas.dimensions()` resize check), `DriftModeChoiceUiTest`. Plain JUnit (no Spring/Docker) style: `DemoDatasetServiceTest`.

## Tasks & Acceptance

**Execution:**
- `res/static/js/help.js` -- create IIFE `window.Help`: delegated click on `[data-help]`, fetch topic, swap content, focus/Esc handling, live-layer renderer per mode (SVG built in JS from trace steps), listens for `graphrag:answer`/`graphrag:corpus` -- P1
- `res/templates/index.html`, `res/static/css/instrument.css` -- pane markup, "?" button styling (`.help-btn`), docked layout, overlay < 960px -- P1
- `res/static/js/upload.js` -- dispatch the two events (answer with `{mode, traceId, stepCount, question, corpusId}`; corpus with `{corpusId, offline}`) -- P1
- `res/static/help/{mode-chooser,local-search,global-search,drift-search}.html` -- articles + hand-authored SVG, live-layer mount point `<div data-live>` -- P1
- `test/help/HelpRegistryGuardTest.java` (plain JUnit) -- Guard 1 (`data-help` <-> topic files, scan index.html) and Guard 2 (`data-class` names resolve to Java files under `src/main` of any module) -- P1
- `test/ui/HelpPaneUiTest.java` -- Playwright: open/swap/Esc/focus, canvas shrinks, live layer empty + Local/Global/Drift after an answer, offline note, corpus-switch reset, narrow overlay -- P1
- `res/static/help/*.html` + "?" wiring for P2: upload, demo-offline, ingestion-progress, corpus-chip-history, kg-overview, entity-types, communities, entity-detail, reading-an-answer, trace-replay, drift-tree, vector-space (generic only); P3: vector-vs-graphrag. Extend guard and UI tests to cover every topic opening -- P2/P3
- Add "update the help topic when behaviour changes" to the definition of done wherever the project documents it (grep `definition of done`, else `_bmad-output/planning-artifacts` conventions or README) -- P3

**Acceptance Criteria:**
- Given the ready demo corpus, when the user clicks "?" beside the mode chooser, then a docked pane shows the chooser article with SVG and the graph canvas is narrower but still rendered.
- Given the pane is open, when another "?" is clicked, then content is replaced and the pane stays; when Esc is pressed, then it closes and focus returns to that "?".
- Given the Local article is open, when a Local answer arrives, then the live layer shows the seed entity, relationship and other entity from that answer's trace without reopening.
- Given a Global or Drift answer, when its article is open, then the live layer shows the community steps (Drift also sub-questions and synthesis).
- Given the offline demo, when any article is open, then it renders and the live layer explains that offline mode has no answers.
- Given a topic file or `data-help` is added or removed without its counterpart, or a named class is deleted, then the guard test fails.
- Given all phases done, every one of the 17 topics opens from its "?" with header, abstract, overview and at least one SVG.

## Spec Change Log

## Review Triage Log

### 2026-09-30 — Review pass
- verdicts: 4 layers reported; grouped below — high 0, medium 6, low 5, false 6, maybe-false 1
- findings:
  - `[medium]` `patch` Live-layer tests assert only ready/svg/not-loading — strengthen to seed/relationship, best marker, communities/synthesis captions.
  - `[medium]` `patch` Corpus-reset test fakes the event — drive real restart path.
  - `[medium]` `patch` No test that "?" clicks leave the adjacent radio/popover unchanged; every-topic test bypasses real buttons (Help.open).
  - `[medium]` `patch` Trace 5xx/network error shown as "found nothing to trace" — add error state.
  - `[medium]` `patch` Global best community inferred from answer text and may fall outside first 8 — put first, label as inferred (backend carries no score; changing it is out of scope).
  - `[medium]` `patch` Esc closes help when meant for another popover/overlay — ignore when target is inside them.
  - `[low]` `patch` Drift spawning community beyond first 5 not shown; dead code in renderDrift; Local third box/arrow without other entity; focus-return fallback; local-search "three steps" wording; empty SVG `<text>`.
  - `[maybe-false]` `patch` Floating "?" buttons may overlap scrubber/legend/drift tree — add bounding-box overlap test; fix CSS if real.
  - `[medium]` `defer` Offline demo UI still says "pre-recorded" though nothing is recorded (pre-existing wording, asserted by OfflineDemoUiTest) — contradicts the new help article.
  - `[false]` `reject` Corpus "?" CSS sibling selector never matches — button is a sibling of #corpus-chip inside .app-bar-right (index.html L18-29), so `~` matches.
  - `[false]` `reject` Drift SUB_QUESTION_SPAWNED identifier is not the community id — AnswerDriftSearch records it as parentId = community id.
  - `[false]` `reject` noAnswer/VECTOR path leaves stale lastAnswer — noAnswer responses still go through appendAnswer with a traceId (upload.js L602-607); VECTOR is deliberately ignored per spec.
  - `[false]` `reject` help.js loads after upload.js so initial state is lost — initial state is idle/non-offline (help.js defaults) and restore is async, after listeners attach.
  - `[false]` `reject` Guard hard-codes 17 / matches class by filename only — matches spec (17 topics, "classes still exist"); accepted known limit in forged idea.
  - `[false]` `reject` Phase-staged delivery / spec code-map line numbers stale / README placement — spec edit or process preference, not a defect in the change.
  - `[low]` `reject` stopPropagation in capture phase skips outside-click handlers, aria-expanded, 20px targets, pane focus outline, duplicate marker ids, per-open refetch, pasted simplifications block, re-activating same corpus keeps answer — unlikely in everyday demo use and fixes add complexity.

## Design Notes

Live layer contract (per open topic, latest answer only): `Help` keeps `{mode, traceId}` from `graphrag:answer`, fetches `/api/traces/{traceId}` when the open topic's `data-mode` matches, renders a small inline SVG plus a caption list. Topic articles declare `data-live-mode="LOCAL|GLOBAL|DRIFT"`. Empty and no-match states are plain text blocks. Simplifications callout in every search-mode article states: Local is not chunk/LLM based here; Drift sub-questions and synthesis are deterministic templates; extraction and summaries are the only LLM-generated parts when an API key is set.

## Verification

**Commands:**
- `mvn -q -pl graphrag-web -am test -Dtest=HelpRegistryGuardTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: pass (no Docker)
- `mvn -q -pl graphrag-web -am test -Dtest=HelpPaneUiTest,RestoredSearchModeUiTest,MainScreenLayoutUiTest,OfflineDemoUiTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: pass (needs Docker)
- `mvn -q verify` -- expected: full build green

**Manual checks (if no CLI):**
- Run the app, open each "?" at 1440px and 800px wide; SVGs render, nothing overlaps the entity detail panel or top-right controls.

## Auto Run Result

Status: done

**Summary:** Docked, non-modal "?" help pane with 17 topic articles (hand-authored inline SVG), a `data-help` registry and `help.js` module, and a live "in your data" layer for Local/Global/Drift driven by the retrieval trace. Guard 1 and Guard 2 are plain JUnit scans. `README.md` has a new "Definition of done" section.

**Files changed:** `help.js`, `static/help/*.html` (17), `index.html`, `instrument.css`, `upload.js`, `README.md`, `HelpRegistryGuardTest`, `HelpPaneUiTest` (11 Playwright tests).

**Review findings:** Patches applied:
- Trace 5xx errors now show an error state instead of "no match".
- Global best community is placed first and labelled as inferred.
- Drift shows spawning communities first.
- Local draws the arrow and third box only when the other entity exists.
- Esc is ignored inside popovers, the scrubber and the entity panel.
- Focus falls back to the chat input when the invoker is hidden.
- The legend "?" lifts above the replay scrubber.
- The ingestion "?" is hidden in the clipped banner state.

One item is deferred: the offline UI wording "pre-recorded" is inaccurate, but it is pre-existing. Six findings were rejected after verification (CSS sibling selector, Drift identifier contract, noAnswer stale state, script order, guard hard-coding, spec-edit items).

**Known unknown resolved:** The offline demo has no recorded answers and no traces (queries return 409). The articles and live layer say so.

**Verification:** `HelpRegistryGuardTest`, `HelpPaneUiTest`, `RestoredSearchModeUiTest`, `MainScreenLayoutUiTest` and `OfflineDemoUiTest` pass. 9 failures in five other UI test classes reproduce on the baseline commit (pre-existing). `mvn verify` fails in `graphrag-adapter-neo4j` because no Docker environment was available.

**followup_review_recommended: true.** Risks:
- No visual check of the pane at 1440px/800px beyond DOM assertions.
- Global's "best" community is inferred from the answer text because the trace carries no scores.
