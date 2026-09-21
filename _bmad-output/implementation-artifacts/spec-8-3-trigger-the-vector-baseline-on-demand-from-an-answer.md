---
title: 'Trigger the Vector Baseline On Demand from an Answer'
type: 'feature'
created: '2026-09-20'
status: 'ready-for-dev'
route: 'oneshot'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The `VECTOR` query mode exists in the backend (Story 8-2) but there is no way for a user to trigger it from the UI. The UI also has no canvas tab to house the Vector Space view.

**Approach:** Add a "Compare with Vector Search" CTA button below each GraphRAG answer (LOCAL/GLOBAL/DRIFT) — clicking it fires `POST /api/corpora/{id}/query` with `mode: "VECTOR"` for the same question, then displays the vector answer in a new message. Simultaneously, add a tab bar above the graph-stage with two tabs: "Knowledge Graph" (always present, default active) and "Vector Space" (revealed only once the user first triggers a vector comparison). The Vector Space tab shows a placeholder panel with the vector baseline answer text and a note that the full embedding-space scatter plot arrives in Story 8-5. Both the Compare CTA and the Vector Space tab must be keyboard-reachable.

## Boundaries & Constraints

**Always:**
- The Compare CTA renders on every answer message that has a `data-question` and `data-corpus-id` attribute (both set by `appendAnswer`). It appears below the Replay CTA (if present) or below the answer text (if no Replay CTA). It is a `<button type="button" class="compare-cta">` reading "↻ Compare with Vector Search".
- `appendAnswer` must set `message.dataset.question = question` and `message.dataset.corpusId = activeCorpusId` so the Compare CTA handler can retrieve them via `event.target.closest('.message.answer')`.
- The Compare CTA is shown on LOCAL, GLOBAL, and DRIFT answers. It is not shown on answers that are themselves VECTOR answers (no nesting — a VECTOR answer does not get its own Compare CTA).
- Clicking the Compare CTA: (1) disables itself with `aria-busy="true"` and sets text to "Comparing…", (2) POSTs to `/api/corpora/{corpusId}/query` with `{question, mode: "VECTOR"}`, (3) on success: appends a VECTOR answer message (same `appendAnswer` path, `mode="VECTOR"`) and reveals the Vector Space tab, (4) on failure: shows the error banner and re-enables the button, (5) always re-enables the button in `finally`.
- The canvas tab bar (`#canvas-tab-bar`) sits at the top of `.graph-stage`, above `#graph-canvas` and all other overlays. It contains two tab buttons: `#tab-knowledge-graph` (text "Knowledge Graph") and `#tab-vector-space` (text "Vector Space"). The tab bar itself is hidden (`hidden` attribute) until a corpus is loaded — it appears at the same time the graph canvas becomes visible. The Vector Space tab is also hidden (`hidden` attribute on the tab button itself) until the first vector comparison is triggered; once revealed it stays visible.
- The active tab is indicated by `aria-selected="true"` on the active tab button and `aria-selected="false"` on the inactive one. Switching tabs: Knowledge Graph tab shows `#graph-canvas` area (existing behavior), Vector Space tab shows `#vector-space-panel`. Tab buttons use `role="tab"` and `tabindex` for keyboard navigation (active tab `tabindex="0"`, inactive `tabindex="-1"`), following the ARIA tabs pattern.
- `#vector-space-panel` is a new `<div>` inside `.graph-stage`, initially hidden. It shows the most recent vector baseline answer text in a readable layout. No scatter plot yet — Story 8-5 owns that. A brief label "Vector Space — answer ready" serves as the eyebrow; the answer text is displayed below.
- CSS for `.compare-cta`, `.canvas-tab-bar`, `.canvas-tab`, `#vector-space-panel` follows the existing design token vocabulary (no new color variables).

**Never:**
- Do not add a VECTOR option to the mode radio group — `VECTOR` is on-demand only, not a standing search mode.
- Do not show the Compare CTA on VECTOR answer messages themselves.
- Do not auto-trigger the vector comparison — it is strictly on-demand.
- Do not break existing replay, drift-tree, or graph-canvas behavior.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| User clicks Compare CTA on a LOCAL answer | Valid corpus, vector index ready | VECTOR answer appended to chat; Vector Space tab revealed | N/A |
| User clicks Compare CTA when vector index not ready | `noAnswer: true` from API | VECTOR "no-answer" message appended (using `reason`); Vector Space tab still revealed | N/A — handled via normal appendAnswer path |
| User clicks Compare CTA twice | Second click while first is in flight | Button disabled during fetch; two separate VECTOR answers on completion | N/A |
| API returns an error | Network failure or 4xx/5xx | Error banner shown; Compare CTA re-enabled | Re-enable in `finally` |
| Tab switch: Knowledge Graph → Vector Space | Vector Space tab previously revealed | `#graph-canvas` area hidden, `#vector-space-panel` shown | N/A |
| Tab switch: Vector Space → Knowledge Graph | Active tab is Vector Space | `#vector-space-panel` hidden, `#graph-canvas` area shown | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-web/src/main/resources/templates/index.html` — add `#canvas-tab-bar` div (with two tab buttons) at top of `.graph-stage`; add `#vector-space-panel` div inside `.graph-stage` (initially hidden); set `data-question` and `data-corpus-id` are set in JS, HTML structure unchanged
- `graphrag-web/src/main/resources/static/css/instrument.css` — add `.canvas-tab-bar`, `.canvas-tab`, `#vector-space-panel`, `.compare-cta` rules
- `graphrag-web/src/main/resources/static/js/upload.js` — update `appendAnswer` to set `dataset.question`/`dataset.corpusId`, add Compare CTA button for non-VECTOR answers; add delegated `click` handler for `.compare-cta`; add `revealVectorSpaceTab()` helper; wire tab switching for `#tab-knowledge-graph` / `#tab-vector-space`; show/hide `#canvas-tab-bar` alongside existing canvas-show logic
- `graphrag-web/src/test/java/com/graphraglens/web/ui/VectorBaselineTriggerUiTest.java` (new) — Playwright test: load demo dataset, ask a LOCAL question, wait for `.compare-cta`, click it, assert VECTOR answer message appears and `#tab-vector-space` is visible; assert tab switching works

## Tasks & Acceptance

**Execution:**
- [ ] `index.html` — add tab bar and vector-space panel elements
- [ ] `instrument.css` — add CSS for tab bar, compare CTA, vector space panel
- [ ] `upload.js` — update `appendAnswer`, add Compare CTA handler, tab switching
- [ ] `VectorBaselineTriggerUiTest.java` (new) — Playwright coverage

**Acceptance Criteria:**
- Given a GraphRAG answer (LOCAL/GLOBAL/DRIFT) is displayed, when the user sees the answer message, then a "↻ Compare with Vector Search" button is present and keyboard-focusable.
- Given the Compare CTA is clicked, when the API returns a VECTOR answer, then a new VECTOR answer message is appended to chat and the "Vector Space" tab is revealed in the canvas tab bar.
- Given the Vector Space tab is revealed, when the user clicks it, then `#vector-space-panel` is shown and the Knowledge Graph canvas is hidden; clicking "Knowledge Graph" reverses this.
- Given a VECTOR answer message, when the user looks for a Compare CTA, then no Compare CTA is present on that message (no nesting).
- Given the canvas tab bar is present, when the user tabs to the "Vector Space" tab button, then it is keyboard-reachable and activatable.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `mvn "-pl" "graphrag-web" "-am" "test" "-Dtest=VectorBaselineTriggerUiTest" "-Dsurefire.failIfNoSpecifiedTests=false"` -- expected: all pass
