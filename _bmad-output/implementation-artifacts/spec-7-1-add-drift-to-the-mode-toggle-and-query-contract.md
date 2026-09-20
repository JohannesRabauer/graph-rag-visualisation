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
deferred:
  - summary: >-
      DriftModeChoiceUiTest only exercises DRIFT mode selection via mouse click, not keyboard
      activation, despite the epic's accessibility-floor expectation.
    evidence: |-
      Pre-existing gap: the Local and Global mode-choice options have no keyboard-activation test
      either, so this is not something this story introduced or regressed — it mirrors the
      existing test pattern (page.locator(...).click()) used across all mode-choice tests.
    location: >-
      graphrag-web/src/test/java/com/graphraglens/web/ui/DriftModeChoiceUiTest.java
    severity: low
  - summary: >-
      No UI test asserts the Replay CTA renders and opens correctly for a zero-step trace, which
      the DRIFT placeholder response always produces.
    evidence: |-
      Real but pre-existing gap: upload.js's own comment documents zero-step Replay CTA rendering
      as deliberate, established behavior ("— 0 steps is itself a meaningful, plain-language
      answer"), and no mode (LOCAL/GLOBAL/DRIFT) currently has a UI test covering the zero-step
      Replay CTA path — not introduced or regressed by this story.
    location: >-
      graphrag-web/src/main/resources/static/js/upload.js:536-541
    severity: low
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

### 2026-09-20 — Review pass

- verdicts: 10 findings — high 0, medium 1, low 3, false 3, maybe-false 0 (3 further findings routed `defer`, counted at their real-outcome verdict: 0 medium/high, 2 low-but-real routed `defer` as pre-existing, 1 `low` routed `defer`)
- findings:
  - `[medium]` `[patch]` (blind-hunter + verification-gap, grouped) `upload.js:675` ready-state banner still reads "Ask a LOCAL or GLOBAL question now." after DRIFT became selectable — undercuts this story's own intent that DRIFT be demonstrable exactly like the other two modes. Fixed: text now reads "Ask a LOCAL, GLOBAL, or DRIFT question now."
  - `[false]` `[reject]` (blind-hunter) DRIFT's `noAnswer` response omits `mode`, claimed as API asymmetry — refuted: `CorpusControllerGlobalSearchTest.java:51` shows the existing GLOBAL `noAnswer` shape already omits `mode` by established convention (`globalSearchResponse`'s `noAnswer` branch never included it either); DRIFT's placeholder simply follows the same precedent.
  - `[false]` `[reject]` (blind-hunter) Replay CTA rendering for the zero-step DRIFT placeholder claimed confusing ("Nothing was touched") — refuted: `upload.js:536-541`'s own comment documents this as deliberate, pre-existing behavior predating this story ("— 0 steps is itself a meaningful, plain-language answer"), unchanged by this diff.
  - `[low]` `[defer]` (blind-hunter) `DriftModeChoiceUiTest` only exercises mouse clicks, not keyboard activation, despite the epic's accessibility-floor note — pre-existing gap shared by the Local/Global mode-choice tests too; not introduced or regressed by this change.
  - `[low]` `[patch]` (blind-hunter) `MainControllerTest.java` asserts `"Local Search"`/`"Global Search"` render in the template but was not extended to assert `"Drift Search"` — a real caller-path gap (a deleted DRIFT `<label>` would not fail this specific test). Fixed: added `assertThat(body).contains("Drift Search");`.
  - `[false]` `[reject]` (blind-hunter) `--drift-soft` token claimed as dead design surface — refuted: `instrument.css`'s own header comment states every color token is "copied verbatim from that frontmatter's colors ... table, not re-derived," i.e. the file's established convention is to mirror DESIGN.md's full token table regardless of current consumption; `--drift-soft` is DESIGN.md-defined and slated for Story 7.4's `components.drift-tree` convergence node.
  - `[low]` `[patch]` (blind-hunter) `captureTrace`'s Javadoc still said "shared by both the LOCAL and GLOBAL query paths," stale now that DRIFT also calls it. Fixed: reworded to "shared by the LOCAL, GLOBAL, and DRIFT query paths."
  - `[medium]` `[patch]` (verification-gap, grouped with the ready-banner finding above) same ready-state guidance gap, filed independently by this layer with its own evidence (`UiTestSupport.java:110` only waits for the substring `Ready`); same fix applies.
  - `[low]` `[patch]` (verification-gap) no test asserted the DRIFT answer message keeps `data-mode="DRIFT"` or the answer-tag's computed drift color — a real, narrowly-scoped gap (removing `message.dataset.mode = activeMode` would not fail any existing test). Fixed: extended `DriftModeChoiceUiTest` to assert `data-mode="DRIFT"` and the tag's computed color `rgb(192, 34, 95)`.
  - `[low]` `[defer]` (verification-gap) no UI test asserts the zero-step Replay CTA renders/opens correctly for the DRIFT placeholder — real gap, but the underlying zero-step-replay behavior is pre-existing and untested for LOCAL/GLOBAL as well (no mode has a zero-step replay UI test); not introduced by this story.

Patches applied (4): ready-state banner copy, `MainControllerTest` "Drift Search" assertion, `captureTrace` Javadoc wording, and `DriftModeChoiceUiTest`'s `data-mode`/color assertion. All re-verified passing (see Verification below).

## Verification

**Commands:**
- `mvn -pl graphrag-web -am test -Dtest=CorpusControllerTest,CorpusControllerGlobalSearchTest` -- expected: all pass, including the new DRIFT and unrecognized-mode assertions.
- `mvn -pl graphrag-web -am test -Dtest=DriftModeChoiceUiTest` (or the project's existing Playwright UI test invocation) -- expected: pass, confirming the DRIFT option renders and recolors correctly.
- `mvn -pl graphrag-web -am test '-Dtest=CorpusControllerTest,CorpusControllerGlobalSearchTest,DriftModeChoiceUiTest' '-Dsurefire.failIfNoSpecifiedTests=false'` -- passed (30 tests).
- Post-review-patch re-run: `mvn -pl graphrag-web -am test '-Dtest=MainControllerTest,DriftModeChoiceUiTest,CorpusControllerTest,CorpusControllerGlobalSearchTest' '-Dsurefire.failIfNoSpecifiedTests=false'` -- passed (36 tests, BUILD SUCCESS).

## Auto Run Result

**Summary:** Added DRIFT as a third selectable mode on the mode-choice radiogroup (dot fill + label recolor to `#C0225F`, DRIFT-specific hint text) and extended `POST /api/corpora/{corpusId}/query`'s `mode` validation to accept `DRIFT`. Since `AnswerDriftSearch` (Story 7.2) does not exist yet, a `DRIFT` query returns an honest `200 {"noAnswer": true, "reason": "DRIFT Search isn't implemented yet."}` placeholder rather than silently answering via Local Search under the wrong label — matching the existing `noAnswer` contract shape already used by Global Search.

**Files changed:**
- `graphrag-web/src/main/resources/templates/index.html` -- added the third `DRIFT` radio option to `.mode-choice`.
- `graphrag-web/src/main/resources/static/css/instrument.css` -- added `--drift`/`--drift-soft` tokens and the DRIFT `.mode-choice-option`/`.answer-tag` color rules.
- `graphrag-web/src/main/resources/static/js/upload.js` -- added the DRIFT hint branch, a `answerTagLabel(mode)` helper (also fixing the pre-existing GLOBAL/LOCAL-only ternary so DRIFT answers label correctly), and updated the ready-state banner copy to mention DRIFT.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- extended mode validation to accept `DRIFT`, added the placeholder `DRIFT` response branch, and updated `captureTrace`'s Javadoc.
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` -- added tests for the DRIFT `noAnswer` response, the DRIFT + `BUILDING` conflict response, and the updated three-mode validation error message.
- `graphrag-web/src/test/java/com/graphraglens/web/MainControllerTest.java` -- added a `"Drift Search"` template-rendering assertion alongside the existing `"Local Search"`/`"Global Search"` ones.
- `graphrag-web/src/test/java/com/graphraglens/web/ui/DriftModeChoiceUiTest.java` -- new Playwright test asserting DRIFT selection recolors the choice and updates the hint, and that a DRIFT query renders a correctly labeled, correctly colored placeholder answer.
- `_bmad-output/implementation-artifacts/epic-7-context.md` -- compiled epic context (new).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- `epic-7` moved to `in-progress`, `7-1-...` moved to `review`.

**Review findings breakdown:**
- Patched (4, all `low`/`medium`, none `high`): stale ready-state banner copy (`medium`, filed independently by both the blind-hunter and verification-gap layers as one grouped entry); `MainControllerTest` missing a `"Drift Search"` assertion (`low`); stale `captureTrace` Javadoc (`low`); missing `data-mode`/computed-color assertion on the DRIFT answer tag (`low`).
- Deferred (2, both `low`, pre-existing and not caused by this story): no keyboard-activation test for mode-choice selection (shared gap across all three modes); no zero-step Replay CTA UI test (shared gap across all modes, and the underlying zero-step-render behavior is itself pre-existing/deliberate).
- Rejected (3, all `false`, refuted with cited evidence): DRIFT's `noAnswer` response omitting `mode` (matches existing GLOBAL `noAnswer` precedent); the zero-step Replay CTA being "confusing" (documented deliberate pre-existing behavior); `--drift-soft` being dead code (file's own header comment establishes the convention of mirroring DESIGN.md's full token table upfront).

**Follow-up review recommendation:** `false`. Only one `medium`-verdict entry was patched (the ready-state banner text, filed by two layers but one root cause/one entry) and no `high` entries were patched, so the "two or more medium" and "any high" triggers do not apply.

**Verification performed:** `mvn -pl graphrag-web -am test` targeted at `CorpusControllerTest`, `CorpusControllerGlobalSearchTest`, and `DriftModeChoiceUiTest` after implementation (30 tests, BUILD SUCCESS), then re-run including `MainControllerTest` after the review patches (36 tests, BUILD SUCCESS). All four I/O & Edge-Case Matrix rows are covered by passing tests: DRIFT selection/hint/color (`DriftModeChoiceUiTest`), DRIFT query `noAnswer` shape (`CorpusControllerTest.driftSearchReturnsTheNoAnswerShapeUntilTheImplementationExists`), unrecognized mode's 400 message (`CorpusControllerTest.unrecognizedModesListDriftInTheValidationError`), and DRIFT + `BUILDING` conflict (`CorpusControllerTest.driftQueryWhileGraphIsStillBuildingReturnsTheExistingConflictResponse`).

**Residual risks:** None blocking. The two deferred items (keyboard-activation coverage, zero-step Replay CTA coverage) are pre-existing gaps shared by all three modes, not introduced by this change — appropriate for a future, separately-scoped test-hardening pass rather than this story.
