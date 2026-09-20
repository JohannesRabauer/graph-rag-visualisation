---
title: 'Add DRIFT to the Mode Choice & Query Contract'
type: 'feature' # feature | bugfix | refactor | chore
created: '2026-09-20'
status: 'done' # draft | ready-for-dev | in-progress | in-review | done | blocked
baseline_revision: '93122e6b206c8a41e2db232a3439d19088800760'
baseline_commit: '93122e6b206c8a41e2db232a3439d19088800760'
review_loop_iteration: 0 # incremented by step-04 before each review loopback
followup_review_recommended: false # set by step-04 on status: done — true if the LLM decided another review pass is worthwhile
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The mode choice above the composer only offers Local and Global Search, and the `/api/corpora/{corpusId}/query` contract only accepts `mode: LOCAL|GLOBAL`. Story 7.1 (Epic 7, DRIFT Search, v1.1) requires DRIFT to become a third selectable mode with its own color/hint, and the query contract to accept `DRIFT` as a valid mode value (AD-18, FR18).

**Approach:** Add a third `mode-choice-option` radio (value `DRIFT`) to `index.html`, wire its selected-state color (`--drift`/`--drift-soft` tokens, new in `instrument.css`) and its hint text in `upload.js`, and extend `CorpusController#query`'s validation to accept `DRIFT` alongside `LOCAL`/`GLOBAL`. Actual DRIFT retrieval (`AnswerDriftSearch`) is Story 7.2 — not built yet — so a query submitted with `mode: DRIFT` must respond honestly that DRIFT answering isn't available yet, never silently fall through to a Local Search answer under a DRIFT label.

## Boundaries & Constraints

**Always:**
- DRIFT's dot-fill and label color use `#C0225F` (`--drift`) exactly like Local (`--accent`) and Global (`--global`) already do — filled dot + recolored uppercase label, no background fill (DESIGN.md `components.mode-choice`).
- The mode hint below the choice updates to "DRIFT runs a community pass, spawns targeted sub-questions, then re-ranks and synthesizes." when DRIFT is selected.
- `CorpusController#query` accepts `"DRIFT"` (case-insensitive, matching the existing `LOCAL`/`GLOBAL` check) as a syntactically valid `mode` — it must not return the existing 400 "Search mode must be LOCAL or GLOBAL." for it.
- A query request with `mode: DRIFT` still returns a normal (2xx) response shape consistent with existing "recognized but not actionable" responses in this codebase (e.g. AD-13's `noAnswer` shape) — it must NOT silently execute `AnswerLocalSearch` and mislabel the result as DRIFT.

**Never:**
- Do not implement `AnswerDriftSearch`, DRIFT retrieval/orchestration logic, or DRIFT trace-step kinds — that is Story 7.2/7.3, out of scope here.
- Do not add the DRIFT branching tree Replay view (`components.drift-tree`) — that is Story 7.4.
- Do not touch the Local/Global mode-choice markup, colors, or hint text beyond adding the third option alongside them.
- Do not add a fourth mode-choice option or any Vector-related work (Epic 8) — unrelated.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Select DRIFT in the UI | User clicks the DRIFT radio option | Its dot fills and label recolors to `--drift`; `#mode-hint` text updates to the DRIFT hint | No error expected |
| Submit a question in DRIFT mode | `POST /api/corpora/{id}/query` with `{"question": "...", "mode": "DRIFT"}` on a corpus with a ready graph | 200 OK; `noAnswer: true` with a `reason` naming that DRIFT Search isn't implemented yet (not a generic `{"error": ...}` 4xx, and not a fabricated Local Search answer) | N/A — this is the expected outcome until Story 7.2 |
| Submit with an unrecognized mode | `mode: "FOO"` | 400 Bad Request, `{"error": "Search mode must be LOCAL, GLOBAL, or DRIFT."}` | Existing 400 path, message text extended to list DRIFT |
| Submit DRIFT while corpus graph is still building | `mode: "DRIFT"`, corpus workflow status `BUILDING` | 409 Conflict, existing `GRAPH_BUILDING_MESSAGE` (checked before mode-specific handling, same as LOCAL/GLOBAL today) | Existing conflict path, unchanged |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/templates/index.html` (lines 25–37) -- the `.mode-choice` radiogroup (`LOCAL`/`GLOBAL` `<label class="mode-choice-option">` + `#mode-hint` `<p>`); add a third `DRIFT` option label in the same structure.
- `graphrag-web/src/main/resources/static/css/instrument.css` (lines 18–30 tokens; lines 605–675 `.mode-choice*` rules) -- add `--drift: #C0225F;` and `--drift-soft: #FBDCE7;` tokens near `--global`/`--global-soft`; add `.mode-choice-option:has(input[value="DRIFT"]:checked)` + its dot rules mirroring the existing `GLOBAL` block (lines 661–671); also mirrors `.message.answer[data-mode="GLOBAL"] .answer-tag` (line 724) if a DRIFT answer tag color is later needed — not required for this story since DRIFT never reaches `appendAnswer` with a real answer yet.
- `graphrag-web/src/main/resources/static/js/upload.js` (lines 14, 28, 48–55, 57–61) -- `modeInputs` NodeList already generically iterates all `input[name="search-mode"]`, so the new radio is picked up automatically; only `setModeHint(mode)` (lines 48–55) needs a DRIFT branch for the hint text. `currentSearchMode` (line 28) needs no change — it already stores whatever value is selected.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` (lines 121–158) -- `query(...)`: the mode validation at line 128 (`!"LOCAL".equalsIgnoreCase(mode) && !"GLOBAL".equalsIgnoreCase(mode)`) must add a `DRIFT` allowance; the routing after validation (`if ("GLOBAL".equalsIgnoreCase(mode)) { ... } else { AnswerLocalSearch ... }`, lines 142–153) needs a new `else if ("DRIFT".equalsIgnoreCase(mode))` branch returning the not-yet-available response, inserted before the existing `AnswerLocalSearch` fallback so DRIFT never falls through to it. Follow the existing `captureTrace(List.of())` pattern (see `globalSearchResponse`, lines 155–176) so a `traceId`/`traceStepCount` are still present on the DRIFT response, consistent with "traceId is always present on a successful query response" (`upload.js` comment, line ~505).
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` (imports/setup lines 1–58; existing LOCAL/GLOBAL query assertions around lines 258–320, 448–505) -- add a test posting `mode: DRIFT` and asserting the 200 `noAnswer` shape, and a test asserting an unrecognized mode's 400 message now mentions DRIFT.
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ReplayCommunityHullVisibilityUiTest.java` (line 42) -- shows the Playwright pattern for selecting a mode: `page.locator("label.mode-choice-option:has(input[value='GLOBAL'])").click();`. Use the same pattern (`value='DRIFT'`) for a new UI test asserting the DRIFT option renders and recolors on selection.
- `_bmad-output/planning-artifacts/ux-designs/ux-graph-rag-visualisation-2026-09-19/DESIGN.md` -- source of truth for the `--drift`/`--drift-soft` color values and the exact DRIFT hint copy (read-only reference, not edited by this story).

## Tasks & Acceptance

**Execution:**
- [x] `graphrag-web/src/main/resources/templates/index.html` -- add a third `<label class="mode-choice-option"><input type="radio" name="search-mode" value="DRIFT">...<span class="mode-choice-label">Drift Search</span></label>` inside `.mode-choice`, after the GLOBAL option -- gives users a selectable DRIFT radio matching the existing two.
- [x] `graphrag-web/src/main/resources/static/css/instrument.css` -- add `--drift`/`--drift-soft` tokens near `--global`; add `.mode-choice-option:has(input[value="DRIFT"]:checked)` (color) and its `.mode-choice-dot`/`::after` border/background rules, mirroring the GLOBAL block exactly -- renders DRIFT's filled dot + recolored label per DESIGN.md.
- [x] `graphrag-web/src/main/resources/static/js/upload.js` -- extend `setModeHint(mode)` with a `mode === 'DRIFT'` branch setting the exact hint copy from DESIGN.md -- keeps the tutorial-clarity hint accurate for the new mode.
- [x] `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- extend the mode validation to accept `DRIFT`, update the 400 error message to mention all three modes, and add a `DRIFT` branch (before the LOCAL fallback) that captures an empty trace and returns `200 {"answerId", "traceId", "traceStepCount": 0, "noAnswer": true, "reason": "DRIFT Search isn't implemented yet."}` -- satisfies AD-18's contract extension without fabricating an answer.
- [x] `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` -- add assertions for `mode: DRIFT` (200, `noAnswer` shape, no fabricated `answer`) and for an unrecognized mode's updated 400 message -- covers the I/O matrix's DRIFT and unrecognized-mode rows.
- [x] `graphrag-web/src/test/java/com/graphraglens/web/ui/ReplayCommunityHullVisibilityUiTest.java`-adjacent new UI test file (e.g. `DriftModeChoiceUiTest.java`) -- assert clicking the DRIFT `label.mode-choice-option` recolors its label/dot and updates `#mode-hint` text -- covers the UI selection I/O row.

**Acceptance Criteria:**
- Given the chat panel is visible, when the user clicks the DRIFT mode-choice option, then its dot fills and its label recolors to `#C0225F`, and `#mode-hint` reads "DRIFT runs a community pass, spawns targeted sub-questions, then re-ranks and synthesizes."
- Given a corpus with a ready knowledge graph, when a query is submitted with `mode: "DRIFT"`, then the response is `200 OK` with `noAnswer: true` and a `reason` stating DRIFT Search is not yet implemented — never a fabricated Local Search `answer`.
- Given a query submitted with an unrecognized mode value, when validated, then the `400` response's error message lists `LOCAL`, `GLOBAL`, and `DRIFT` as the valid modes.
- Given a corpus whose graph is still `BUILDING`, when a query is submitted with `mode: "DRIFT"`, then the existing `409` graph-building response is returned, unchanged from the LOCAL/GLOBAL behavior.

## Spec Change Log

## Review Triage Log

- 2026-09-20 — `false` — `CorpusController.java:145-155`: the DRIFT branch intentionally returns the Story 7.1 placeholder no-answer shape because the spec explicitly forbids implementing real DRIFT retrieval before Story 7.2.
- 2026-09-20 — `false` — `CorpusController.java:149-155` / `upload.js:226-235`: the backend omits `mode` on no-answer responses, but the UI already falls back to the request's `requestedSearchMode`, so DRIFT answers still render under the correct mode without extra contract surface.
- 2026-09-20 — `false` — `instrument.css:739-741` / `upload.js:514-534`: the DRIFT answer-tag color does apply to the shipped placeholder response because `appendAnswer(...)` always sets `message.dataset.mode = activeMode`, including the DRIFT no-answer case.
- 2026-09-20 — `false` — `upload.js:57-65` / `upload.js:226-235`: the new DRIFT answer-tag helper cannot mislabel the placeholder response in current code, because the request-mode fallback keeps `activeMode === "DRIFT"` for that path.
- 2026-09-20 — `false` — `DriftModeChoiceUiTest.java:9-43`: UI coverage does include both the DRIFT hint/color state and the chat-tag behavior after a DRIFT query, so the claimed verification gap is not present.
- 2026-09-20 — `false` — `CorpusController.java:149-155`: the reason text matches this story's explicit requirement ("DRIFT Search isn't implemented yet."); the broader epic's future "no viable sub-questions" wording belongs to Story 7.2's real DRIFT execution path, not this placeholder contract extension.

## Verification

**Commands:**
- `mvn -pl graphrag-web -am test -Dtest=CorpusControllerTest,CorpusControllerGlobalSearchTest` -- expected: all pass, including the new DRIFT and unrecognized-mode assertions.
- `mvn -pl graphrag-web -am test -Dtest=DriftModeChoiceUiTest` (or the project's existing Playwright UI test invocation) -- expected: pass, confirming the DRIFT option renders and recolors correctly.
- `mvn -pl graphrag-web -am test '-Dtest=CorpusControllerTest,CorpusControllerGlobalSearchTest,DriftModeChoiceUiTest' '-Dsurefire.failIfNoSpecifiedTests=false'` -- passed (30 tests).
