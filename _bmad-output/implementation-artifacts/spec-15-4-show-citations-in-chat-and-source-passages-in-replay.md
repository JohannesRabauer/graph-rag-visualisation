---
title: 'Show Citations in Chat and Source Passages in Replay'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: '095189bb6cfd437ec28e9567a297c236843e58b5'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: ['oversized']
deferred:
  - summary: >-
      No UI test drives a citing Entity that was also the previous step's node during a TEXT_UNIT step (the step-previous override fix).
    evidence: |-
      The CitationsUiTest step-previous assertion passes without the fix because its previous step is a passage step; the guard in graph-canvas.js highlightStep is unpinned.
    location: >-
      graphrag-web/src/main/resources/static/js/graph-canvas.js highlightStep
    severity: low
---

<intent-contract>

## Intent

**Problem:** Since Stories 15.2 and 15.3, Local, Global and DRIFT answers carry inline `[n]` markers and a `citations` array, and their traces contain `TEXT_UNIT` steps. The chat shows the markers only as plain text, and Replay captions passage steps with a neutral "read passage". A viewer cannot open a cited passage or watch the passages light up.

**Approach:**
- **Chat:** each `[n]` becomes a small button, and a "Sources" list goes under the answer. Activating a marker or a source opens the passage text, reusing Story 13.4's text-unit endpoint and its passage cache.
- **Replay:** a `TEXT_UNIT` step is captioned "Read passage {ordinal} of {documentName}" plus the excerpt, and the Entities that cite that passage are highlighted.
- **Help:** two articles explain this.

Answers without citations render exactly as today.

## Boundaries & Constraints

**Always:**
- **Rendering rule.** `[i]` in the answer refers to `citations[i-1]`; 15.2's resolver renumbers markers so this holds. Grouped markers like `[1, 2]` render as one button per number. A marker with no matching citation stays plain text.
- **Markers and sources.**
  - Marker buttons have accessible names like "Source 1: {documentName}".
  - The Sources list shows one row per citation as `{n}. {documentName} · {excerpt}`.
  - Activating a marker or a row toggles an inline passage panel under that answer, with the full passage text. It is fetched from `GET /api/corpora/{corpusId}/text-units/{textUnitId}` and cached per textUnitId. On failure it shows "Passage not available".
- **Safety.** All text is set with `textContent`; no `innerHTML` with answer or passage text.
- **No citations.** An answer with no `citations` or an empty one (offline, Vector Baseline, noAnswer) renders byte-for-byte as today: no Sources list, no buttons.
- **Replay caption.** A `TEXT_UNIT` step's caption is `Read passage {ordinal+1} of {documentName}`, followed by the excerpt (the step `label`).
  - `documentName` and `ordinal` come from the text-unit endpoint, via the same cache, for the replayed trace's corpus.
  - Until the fetch resolves, or if it fails, the caption falls back to "Read passage" plus the excerpt.
  - The `+1` matches Story 13.4's "passage N" convention.
- **Replay highlight.** During a `TEXT_UNIT` step, every Entity whose `sourceTextUnitIds` contains that id is highlighted on the canvas with the same class the replay uses for the current step. The client already holds entity sources from the graph and SSE payloads. Other steps' highlighting is unchanged.
- **Help.** The `reading-an-answer` article explains `[n]` markers, the Sources list and the passage panel. The `trace-replay` article explains passage steps and the highlighted citing Entities. The help registry guard test must stay green.
- Works in light and dark themes and at the existing breakpoints, using the existing design tokens and classes.

**Never:**
- No backend contract change, except an optional additive field if strictly needed. Prefer none: the endpoint already returns `{id, documentName, ordinal, text}`.
- No change to which steps are recorded.
- No new libraries.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Cited answer | answer "A [1] and B [2]." + 2 citations | 2 marker buttons, Sources list with 2 rows | none |
| Grouped marker | "… [1, 2]." | two buttons 1 and 2 | none |
| Dangling marker | "[3]" with 2 citations | "[3]" plain text | none |
| Open passage | click marker 1 | passage panel shows full text of citations[0] (fetched once, cached) | fetch fails → "Passage not available" |
| No citations | offline answer | identical DOM to today | none |
| Replay passage step | trace step TEXT_UNIT id X | caption "Read passage 2 of notes.txt" + excerpt; entities citing X highlighted | fetch fails → "Read passage" + excerpt |
| Non-passage steps | ENTITY/RELATIONSHIP/COMMUNITY | captions/highlights as today | none |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/static/js/upload.js`:
  - `appendAnswer(text, mode, traceId, traceStepCount, question, queryProjection)` (~L1420-1519). Answer text goes into a `<span>` via `textContent` (~L1443). The Replay, Compare and help CTAs follow. It dispatches `graphrag:answer`.
  - The call site in the chat submit handler (~L879-908) passes `body.answer || body.reason`. Pass `body.citations` through (add a parameter or an options object), as well as on the compare path (~L157-173) if it renders graph answers.
  - `renderEntityDetailSources` (~L566-630) holds the reusable passage fetch, `sourcePassageCache` (~L84) and the "Passage not available" fallback. Extract a shared `loadPassage(textUnitId)` helper returning a promise of `{documentName, ordinal, text}` with a per-id cache. Note that the current cache is reset per identity (~L507-519, 577), so the shared cache should be per corpus instead.
  - The entity sources the client already holds are normalized by `normalizeSources` (~L522).
- `graphrag-web/src/main/resources/static/js/replay.js`:
  - `captionFor(step, index, total)` (~L427-436) has the `TEXT_UNIT` → 'read passage' verb from 15.2.
  - `updateCounterAndCaption` (~L356) and `renderStep` (~L324). Make the TEXT_UNIT caption async-safe: render the fallback first, then update it if the step is still current when the fetch resolves.
- `graphrag-web/src/main/resources/static/js/graph-canvas.js`:
  - `stepNodeId` (~L1049) returns null for TEXT_UNIT, and `highlightRetrievalStep` (~L1157) skips it (both 15.2).
  - Add highlighting of the citing Entities, using the entity nodes' data. Check how entity nodes store `sources`/`sourceTextUnitIds` when added (`addEntity`). If they don't store them, store them there.
  - Exports are at ~L1715.
- `graphrag-web/src/main/resources/static/js/drift-tree.js` -- TEXT_UNIT steps are branch steps (15.3). The caption comes from replay.js.
- `graphrag-web/src/main/resources/static/help/reading-an-answer.html`, `trace-replay.html`; the registry guard is `graphrag-web/src/test/java/com/graphraglens/web/help/HelpRegistryGuardTest.java`.
- CSS: find the existing stylesheet for chat and detail-panel classes (e.g. `node-detail-source-*`) under `static/css`, and reuse its tokens.
- UI tests: `graphrag-web/src/test/java/com/graphraglens/web/ui/` (Playwright, `UiTestSupport`). `DriftTreeReplayUiTest` shows the `window.fetch` rewrite technique for injecting synthesized traces and answers, because CI runs offline.

## Tasks & Acceptance

**Execution:**
- `upload.js` -- marker buttons, the Sources list, the passage panel, a shared `loadPassage` cache, and citations passed through from the query response.
- `replay.js` -- the passage caption with async enrichment and fallback.
- `graph-canvas.js` -- highlighting of citing Entities for TEXT_UNIT steps, with entity source ids available on the nodes.
- CSS -- styles for the marker buttons, Sources list and passage panel, using the existing tokens, in light and dark themes.
- `reading-an-answer.html`, `trace-replay.html` -- the new explanations.
- New `CitationsUiTest` (Playwright). It runs the offline demo. It uses `page.route` or a `window.fetch` rewrite to return a cited answer (a real demo text-unit id, read from `GET /api/corpora/{id}/graph` entity sources) and a trace with that TEXT_UNIT step. It then asks a question and asserts:
  - the buttons and the Sources list;
  - opening a citation shows the real passage text from the endpoint;
  - stepping Replay onto the passage step shows "Read passage N of {doc}" and the excerpt;
  - at least one citing Entity node has the current-step highlight class;
  - a no-citation answer renders without a Sources list.

**Acceptance Criteria:**
- Given a cited answer, when it appears in the chat, then each `[n]` is a button and a Sources list shows `{documentName} · {excerpt}` per citation. Activating either opens the passage text.
- Given Replay of that answer's trace, when it steps onto a `TEXT_UNIT` step, then the caption reads "Read passage {N} of {documentName}" with the excerpt, and the citing Entities are highlighted.
- Given an offline answer without citations, when it renders, then the chat looks exactly as today, and all existing UI tests pass.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 27 findings — high 0, medium 5, low 20, false 2, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) Dangling ` · ` separator when an excerpt is missing — separator only added when there is an excerpt
  - `[low]` `[patch]` (blind) Failed passages are refetched on every Replay render — failures remembered per open trace
  - `[low]` `[patch]` (blind) The passage panel is not linked or announced (a11y) — panel id, aria-controls, aria-live added
  - `[low]` `[reject]` (blind) The chat panel shows no passage position; excerpt not highlighted — cosmetic; the title names document and number
  - `[low]` `[reject]` (blind) The help diagram is outdated — the text sections explain the feature; the diagram is cosmetic
  - `[low]` `[reject]` (blind) No regression test for the entity detail panel after the cache refactor — EntityDetailSourcesUiTest is green and covers opening passages
  - `[medium]` `[patch]` (blind) Thin chat citation tests (switching citations, two-source fallback) — switch-citation test added; fails if fewer than 2 sources
  - `[low]` `[patch]` (blind) The fallback test uses a fixed wait — waits for the routed 404 instead
  - `[low]` `[reject]` (blind) The cache is reset by unrelated rebuilds — only costs a refetch; keying per corpus keeps it correct
  - `[low]` `[reject]` (blind) The marker formats the UI accepts are undocumented — CitationResolver emits only the comma form (15.2)
  - `[low]` `[reject]` (blind) Citing-entity scan is O(nodes); test checks at least one — demo-scale graphs; the restore test adds coverage
  - `[medium]` `[patch]` (edge) step-previous overrides step-active for a citing entity — current-step classes take precedence; assertion added
  - `[low]` `[patch]` (edge) Repeated 404s on re-render — same root cause as row 2
  - `[low]` `[patch]` (edge) Replay corpus id is assigned before the trace fetch succeeds — assigned only on success
  - `[low]` `[patch]` (edge) A missing ordinal shows "passage 1" — number omitted when not finite
  - `[low]` `[patch]` (edge) Dangling separator — same root cause as row 1
  - `[low]` `[patch]` (edge) Empty answer text plus citations shows Sources — plain text path for empty answers
  - `[false]` `[reject]` (edge claim) The Sources format has an `n.` prefix — the spec defines `{n}. {documentName} · {excerpt}`; the prefix ties a row to its marker
  - `[medium]` `[patch]` (edge claim) A citing entity can show the previous ring — same root cause as row 12
  - `[medium]` `[patch]` (verification-gap) The async Replay caption success path is untested — uncached Replay caption test added
  - `[medium]` `[patch]` (verification-gap) Highlight on the READY restore path is untested — reload/restore test added
  - `[low]` `[patch]` (verification-gap) Retype keeping sourceTextUnitIds is untested — retype assertion added
  - `[low]` `[patch]` (verification-gap) The current-step-only rule is not asserted — same root cause as row 12
  - `[low]` `[patch]` (verification-gap) Switching citations and the panel title are not asserted — same root cause as row 7
  - `[low]` `[reject]` (intent) The backend-to-UI seam is not exercised end to end — no API key in CI; controller tests (15.2/15.3) pin the backend shapes the UI consumes
  - `[low]` `[reject]` (intent) The passage opens in an inline panel, not a popover or the detail panel — inline expanding panel under the answer serves as the popover; keeps the detail panel for entities
  - `[false]` `[reject]` (intent) Ordinal is shown +1 — the spec follows Story 13.4's "passage N" convention

## Verification

Run every Maven command with `OPENAI_API_KEY` unset (as in CI). `-Dapi.version=1.44` is needed for Testcontainers with the local Docker.

**Commands:**
- `env -u OPENAI_API_KEY mvn -B -pl graphrag-web test -Dapi.version=1.44 -Dtest='CitationsUiTest,HelpRegistryGuardTest,HelpPaneUiTest,DriftTreeReplayUiTest,EntityDetailSourcesUiTest' -Dsurefire.failIfNoSpecifiedTests=false` -- expected: all pass
- `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44` -- expected: BUILD SUCCESS (all modules, UI tests included)

## Auto Run Result

Status: done

**Summary:** Cited answers now show their `[n]` markers as buttons, plus a "Sources" list (`n. {documentName} · {excerpt}`). Activating either opens an inline passage panel with the full text. The text comes from Story 13.4's text-unit endpoint through a shared per-corpus passage cache. During Replay, a `TEXT_UNIT` step reads "Read passage {N} of {documentName}: {excerpt}", which is enriched asynchronously, falls back while loading or on failure, and does not refetch a failed passage. The Entities citing that passage are highlighted as the current step. Answers without citations render exactly as before. The `reading-an-answer` and `trace-replay` help articles explain citations and passage steps.

**Files changed:**
- `upload.js` -- shared `loadPassage`/`window.Passages`, citation markers, Sources list, passage panel with ARIA, and entity sources passed to the canvas.
- `replay.js` -- passage caption, failure memo, corpus-id race fix.
- `graph-canvas.js` -- `sourceTextUnitIds` on nodes, citing-Entity highlight with current-step precedence, `citingEntityIds` export.
- `instrument.css` -- styles.
- Help: `reading-an-answer.html`, `trace-replay.html`.
- Tests: new `CitationsUiTest` (4 tests); `DriftTreeReplayUiTest` caption case; `PassageProgressStatusUiTest` retype sources assertion.

**Review findings:** 27 findings. 18 rows were patched, covering 11 distinct fixes:
- highlight precedence;
- failure memo;
- corpus-id race;
- ordinal null handling;
- excerpt separator;
- empty-answer path;
- ARIA wiring;
- switch-citation test;
- uncached caption test;
- restore-path test;
- retype test (the fixed-wait removal was also patched).

1 deferred: the exact citing-also-previous-node case has no driving test. 9 rejected, with reasons in the Review Triage Log.

**Follow-up review recommendation:** true. 4 medium entries were patched. Named unverified risk: the citation UI has only been exercised with injected answers and traces (`window.fetch` rewrite). It has never been exercised with a real LLM-synthesized answer, and the highlight-precedence fix is not driven by a test.

**Verification:** `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44` gave BUILD SUCCESS: core 173, neo4j 63, langchain4j 31, parsing 10, web 191, 0 failures.

**Residual risks:** Layout was not inspected visually at different widths. The stylesheet is light-mode only by existing design.
