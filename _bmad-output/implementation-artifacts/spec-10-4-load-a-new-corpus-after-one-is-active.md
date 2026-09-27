---
title: 'Allow Loading a New Corpus After One Is Already Active'
type: 'feature'
created: '09-27-2026'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: [oversized]
deferred:
  - summary: >-
      Restarting during BUILDING abandons the in-flight ingestion server-side
      with no cancellation/cleanup (e.g. no DELETE /api/corpora/{id} call).
    evidence: |-
      Verified pre-existing: before this story, the only way to "start over"
      was a full page reload, which already abandoned a mid-ingestion
      Corpus server-side with the exact same lack of cleanup. This story
      adds another client-side path to the same pre-existing characteristic,
      not a new leak. The epic's own framing is explicitly UX-only ("not
      tied to new functional requirements"), and adding a cancellation
      endpoint would be new backend capability outside this story's scope.
    location: >-
      graphrag-web/src/main/resources/static/js/upload.js (resetToIdleState)
    severity: low
baseline_revision: 'e1e61945d47b7c7a2cd5051c29defa80f3a539a6'
---

<intent-contract>

## Intent

**Problem:** Once a Corpus is active, `#canvas-idle` (the upload/demo-dataset controls) is hidden for good (`showCorpusChip` sets `canvasIdle.hidden = true` and nothing ever undoes it), and `#workflow-restart-button` — nominally "Start over with a new corpus" — is both unreachable (it lives inside `#workflow-recovery-actions`, shown only in the `FAILED` state) and a no-op (it only refocuses the now-hidden file input). The only way to load a second Corpus today is a full page reload (GitHub #23).

**Approach:** Relocate the restart button next to `#corpus-chip` in the app bar, so it stays reachable in every workflow state (`BUILDING`/`READY`/`FAILED`) exactly as long as a Corpus is active. Wire it to a `window.confirm(...)` gate, then a new `resetToIdleState()` function that tears down every piece of active-corpus state `showCorpusChip` turns on (EventSource, Replay, detail panel, canvas, chat thread, mode choice, Vector Space tab/panel) and re-reveals `#canvas-idle` — after which the *existing*, unmodified upload/demo-dataset handlers just work again, since they already call `showCorpusChip` unconditionally on success.

## Boundaries & Constraints

**Always:** Show the confirmation ("Loading a new Corpus will discard the current one — continue?") before tearing down anything — cancelling must leave the active Corpus's UI/state completely untouched. The restart control must be reachable (visible, keyboard-focusable) in `BUILDING`, `READY`, and `FAILED` alike — mirror its visibility with `#corpus-chip`'s own (shown/hidden together), not with `#workflow-recovery-actions` (`FAILED`-only). After reset, the graph canvas must be genuinely empty (call `window.GraphCanvas.init(...)` again — it already does `cy.destroy()` internally) and `#canvas-idle` must be visible and its controls (`#corpus-file-input`, `#demo-dataset-button`, `#demo-offline-button`) enabled — no full page reload.

**Never:** Do not modify the upload/demo-dataset submit handlers or `showCorpusChip` itself — they already correctly re-run for any corpusId, unconditionally; the only missing piece is getting `#canvas-idle` re-reachable and every prior corpus's state cleared first. Do not build a custom modal/dialog component — no such pattern exists yet in this codebase, and a native `window.confirm(...)` satisfies "a confirmation step" literally without new UI chrome. Do not touch DRIFT/community-hull/entity-type-coloring logic beyond resetting their toggles' state alongside everything else.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Restart triggered during BUILDING | Corpus is mid-ingestion | Confirmation shown; on confirm, EventSource/canvas/chat/mode all reset, `#canvas-idle` reappears | No error expected |
| Restart triggered during READY | Corpus fully ingested, chat active | Same full reset | No error expected |
| Restart triggered during FAILED | Corpus ingestion failed | Same full reset (button already reachable here today, but now via the relocated control, not the old FAILED-only one) | No error expected |
| User cancels the confirmation | `window.confirm` returns `false` | Nothing torn down — active Corpus, chat, canvas all remain exactly as they were | No error expected |
| A new Corpus is chosen after reset | User uploads a file or picks a demo dataset from the re-shown `#canvas-idle` | Normal fresh-corpus flow runs unmodified (`showCorpusChip`) — second Corpus builds and streams exactly like the first one did | Existing upload-failure handling (unchanged) |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/templates/index.html` — move `<button type="button" id="workflow-restart-button">Start over with a new corpus</button>` (currently ~line 111, inside `#workflow-recovery-actions`) out to `.app-bar-right` (~line 17-19), as a sibling of `#corpus-chip`, `hidden` by default (mirrors `#corpus-chip`'s own `hidden` attribute). `#workflow-recovery-actions` keeps only `#workflow-retry-button` (10.2's own scope, untouched).
- `graphrag-web/src/main/resources/static/css/instrument.css` — `.app-bar-right` (~line 187-191, already `display:flex; gap`) needs no layout change; add a small, unobtrusive `.workflow-restart-button` rule (text-button scale, matching the app bar's typography — not `.demo-dataset-button`'s larger idle-screen chrome).
- `graphrag-web/src/main/resources/static/js/upload.js` — this is the whole of the change:
  - `workflowRestartButton` click handler (~lines 641-648): replace the `fileInput.focus()` no-op with `if (!window.confirm('Loading a new Corpus will discard the current one — continue?')) { return; } resetToIdleState();`.
  - New `resetToIdleState()` function: the mirror image of `showCorpusChip` (~lines 650-749) — close `activeProgressSource` (as `showCorpusChip` already does, ~654-657), close `window.Replay` (~662-664), `closeEntityDetailPanel()` (~668, already resets `selectedEntityIdentity`/`selectedEntityType`), `activeRelationships = []` (~669); reset `activeCorpusId = null`, `activeCorpusReady = false`, `activeCorpusOffline = false`; reset `currentSearchMode` to `'LOCAL'` and check the matching `modeInputs` radio + `setModeHint('LOCAL')`; clear `chatThread` (`.textContent = ''`), `chatInput.value = ''`, re-enable `chatInput`/`sendButton`, reset `chatInput.placeholder`; `hideErrorBanner()`; hide `corpusChip`/`workflowRestartButton` (mirror pair), `chatPanel`, `communityToggleWrap`, `entityTypeToggleWrap`, `graphCanvasEl` (+ `aria-hidden`), `canvasTabBar`, `#entity-search`, `graphEyebrow`, `workflowStatus`; call `switchCanvasTab('knowledge-graph')` (~line 175) then re-hide `tabVectorSpace` (re-add its `hidden` attribute — `revealVectorSpaceTab`, ~lines 153-156, removes it) and hide `vectorSpacePanel`, clear `vectorSpaceAnswer.textContent`; call `window.GraphCanvas.init({ interactive: true })` (destroys/recreates `cy`, clears the legend) so the canvas is genuinely empty even before a new Corpus is chosen; re-enable `fileInput`/`demoButton`/`demoOfflineButton` and clear `fileInput.value`; finally `canvasIdle.hidden = false`.
  - `showCorpusChip` (~line 702): add `if (workflowRestartButton) { workflowRestartButton.hidden = false; }` alongside `corpusChip.hidden = false` (~702) — the button and chip now show/hide as a pair in both directions.

## Tasks & Acceptance

**Execution:**
- `index.html` -- relocate `#workflow-restart-button` from `#workflow-recovery-actions` into `.app-bar-right`, `hidden` by default -- makes it reachable in every workflow state, not just FAILED
- `instrument.css` -- add a small `.workflow-restart-button` rule -- fits the app bar's scale instead of the idle screen's larger CTA style
- `upload.js` -- add `resetToIdleState()`, wire the confirm-gated click handler, pair `workflowRestartButton`'s visibility with `corpusChip`'s in `showCorpusChip` -- the actual teardown-and-reveal, and the reachability pairing
- `graphrag-web/src/test/java/com/graphraglens/web/ui/LoadNewCorpusUiTest.java` (new) -- Playwright UI tests covering every I/O Matrix row: restart button reachable/functional during BUILDING (mock a slow `/progress` stream, matching `ProgressStreamDisconnectedBannerUiTest`'s route-mocking pattern) and during READY; cancelling the confirmation leaves everything untouched; confirming resets canvas/chat/mode and re-reveals `#canvas-idle`; loading a second Demo Dataset afterward builds and streams normally (reusing `loadDemoDatasetAndWaitReady`-equivalent assertions a second time in one test) -- proves the full teardown-and-reload-free-reload cycle end-to-end

**Acceptance Criteria:**
- Given a Corpus is already active (BUILDING, READY, or FAILED), when the restart control is triggered and confirmed, then any open EventSource/Replay is closed, the graph canvas resets to empty, chat thread/mode state resets, and the upload/demo-dataset controls become reachable again — all without a full page reload
- Given the restart control is triggered, when the confirmation is shown, then nothing is torn down until the user confirms — cancelling leaves the active Corpus exactly as it was
- Given the reset has completed, when a new Corpus is uploaded or a demo dataset is chosen, then it builds and streams exactly as the first Corpus did, with no leftover state from the previous one

## Spec Change Log

## Review Triage Log

### 09-27-2026 — Review pass
- verdicts: 17 findings — high 0, medium 5, low 1, false 4, maybe-false 0, reject/defer-only 7 (rejects/defers below carry their own verdict; see rows)
- findings:
  - `[medium]` `[patch]` Verification-gap: `resetToIdleState()` explicitly hides `graphCanvasEl`/`communityToggleWrap`/`entityTypeToggleWrap`/`entitySearchEl`/`graphEyebrow`, then calls `switchCanvasTab('knowledge-graph')` — but if Vector Space was ever revealed this session (`revealVectorSpaceTab`/`switchCanvasTab('vector-space')` stamps `dataset.hiddenByTabSwitch = '1'` on those same elements), that call's own cleanup unconditionally sets `el.hidden = false` on every element still carrying the flag, undoing the reset's hides. Verified by reading `switchCanvasTab` (~lines 175-215): the unhide-on-return-to-KG-tab logic has no awareness of the reset having just run. Patch: move the `switchCanvasTab('knowledge-graph')` call to *before* the explicit hide block in `resetToIdleState()` (so the hides applied afterward are the ones that stick), and add a `LoadNewCorpusUiTest` case that reveals Vector Space first, then confirms restart, and asserts those elements stay hidden.
  - `[medium]` `[patch]` Edge Case Hunter: same defect as the Verification-gap row above (grouped, shares its `patch` route) — independently found via path-tracing `switchCanvasTab`'s `dataset.hiddenByTabSwitch` cleanup against `resetToIdleState`'s call ordering.
  - `[medium]` `[patch]` Intent-alignment-auditor: the epic's own cross-cutting constraint ("coordinate so the two changes don't reintroduce each other's bug") has no test asserting `#error-banner` stays hidden after a restart, and flagged a plausible race: a transport-level `onerror` for the *old*, already-`.close()`d `EventSource` could still be in-flight when `resetToIdleState()` runs. Verified and deepened: `connectProgressStream`'s `onerror` handler (`upload.js:1103-1109`) closes over the shared module-level `activeProgressSource`/`activeCorpusReady`, not the specific `EventSource` instance it was attached to. If that stale handler fires *after* `resetToIdleState()` has already set `activeCorpusReady = false` and `activeProgressSource = null`, it (a) re-shows the disconnected banner for a Corpus the user already discarded, since `!activeCorpusReady` now reads `true`, and (b) then throws `TypeError: Cannot read properties of null (reading 'close')` on `activeProgressSource.close()`, since the shared var is already `null`. Patch: capture the specific `EventSource` instance in a local (e.g. `var source = activeProgressSource;`) when registering `onerror`, and guard the entire handler body on `activeProgressSource === source` before doing anything else — fixes both the stale banner and the null-dereference in one guard.
  - `[medium]` `[patch]` Blind Hunter + Edge Case Hunter (grouped, same defect, 2 rows sharing this `patch` route): an in-flight chat-query `fetch` (`upload.js:452-483`) has no staleness guard — if the user clicks restart-and-confirm while a question is still awaiting its answer, the `.then()`/`.catch()` continuation still runs after `resetToIdleState()` has cleared `chatThread`/reset `activeCorpusId`, appending a stale (and potentially mis-attributed, if a second Corpus has since loaded) answer into the wrong chat thread. Verified reachable: nothing in the submit handler or `resetToIdleState()` fences this. Patch: capture `var requestedCorpusId = activeCorpusId;` alongside the existing `requestedSearchMode` capture, and guard the `.then()`/`.catch()` bodies with `if (requestedCorpusId !== activeCorpusId) { return; }` before touching the DOM.
  - `[medium]` `[patch]` Blind Hunter + Intent-alignment-auditor (grouped, same gap, 2 rows sharing this `patch` route): the spec's own I/O Matrix names "Restart triggered during FAILED" as a row and the Tasks section claims "every I/O Matrix row" is covered, but `LoadNewCorpusUiTest` only has BUILDING and READY cases — verified by reading the test file (4 tests, none reach FAILED). Patch: add a test that mocks `/progress` to emit a structured `error` SSE event (`event: error\ndata: {"type":"error","data":{"error":"..."}}\n\n`, matching `upload.js`'s structured-error listener's expected envelope) to reach FAILED, then asserts the restart button is reachable/functional there too.
  - `[low]` `[patch]` Blind Hunter: `#entity-search-input`'s value is never cleared by `resetToIdleState()` (only the wrapper's `hidden` is set) — a search string typed against the first Corpus reappears verbatim once the box is shown again for a second one. Real but minor (no incorrect search results, just a confusing pre-filled box); the fix is a trivial one-line addition alongside the reset's other UI-clearing lines. Patch: also clear the search input's value (and close any open results dropdown) in `resetToIdleState()`.
  - `[reject]` Blind Hunter: `resetToIdleState()` hides `communityToggleWrap`/`entityTypeToggleWrap` but never resets the underlying checkboxes' `.checked` — verified no observable consequence: both wraps are hidden (unreachable/non-interactable) during the idle window, and `showCorpusChip` unconditionally resets both to `checked = true` before making them visible again on the next Corpus load. No path exists where a stale `.checked` value is ever seen or acted upon.
  - `[low]` `[defer]` Blind Hunter: no server-side cancellation of an in-flight ingestion when restarting during BUILDING — real, but verified pre-existing and not caused by this story: abandoning a mid-ingestion Corpus server-side already happened on every page-reload restart before this story existed (the only way to "start over" until now), and the epic's own framing is explicitly UX-only, "not tied to new functional requirements." A `DELETE /api/corpora/{id}`-style endpoint is a new backend capability outside this story's scope.
  - `[reject]` Blind Hunter: no test drives the relocated button via actual keyboard events (only `isVisible`/`isEnabled` checked) — unlike a custom checkbox, a plain, unmodified `<button type="button">` has nothing in this diff (no `tabindex`, no `pointer-events` tricks, no custom ARIA) that could plausibly break native keyboard operability; not worth a dedicated keyboard-simulation test.
  - `[reject]` Blind Hunter: `LoadNewCorpusUiTest` uses a fully-qualified `com.microsoft.playwright.Dialog::dismiss` instead of importing `Dialog` — purely cosmetic, zero behavior impact, compiles and passes as-is.
  - `[reject]` Blind Hunter: the confirmation string is duplicated as a literal in both `upload.js` and the test's `CONFIRM_MESSAGE` constant with no shared source of truth — matches the already-accepted sibling convention (`ProgressStreamDisconnectedBannerUiTest`'s own `DISCONNECT_MESSAGE` constant duplicates its production string the same way); not a new problem this diff introduced.
  - `[reject]` Edge Case Hunter (claims-check): the spec's Design Notes claim "a grep... found no other references besides upload.js's own listener and index.html's markup" is technically false (`MainControllerTest.java:80` also references the id) — but harmless (that assertion is location-agnostic and still passes), and the fix would edit this build's spec; excluded from patch/defer per triage rules.
  - `[reject]` Edge Case Hunter (deletion-check): the old click handler's `fileInput.focus()` call was removed and not replaced, so keyboard focus has nowhere to land after reset — verified there was no working behavior to preserve: the epic itself confirms the old handler was already a no-op (`#canvas-idle`/the file input were permanently `hidden`, and `.focus()` on a `hidden` element is a browser no-op), and neither the AC nor this spec's Boundaries require auto-focus — only that the controls be reachable via normal tab order, which they are once un-hidden.
  - `[false]` Intent-alignment-auditor: raised "real affordance vs. relocated no-op-turned-confirm" as a divergence — not a new finding: the spec's own Design Notes explicitly considered and rejected building a custom modal (scope creep) in favor of `window.confirm(...)`, so this reading was already deliberately addressed by design, not overlooked.
  - `[false]` Intent-alignment-auditor: confirmed the confirm/cancel-leaves-everything-untouched behavior is solidly covered by `cancellingTheConfirmationLeavesTheActiveCorpusUntouched` — no gap, no action needed.
- patch outcomes: all 5 patch-routed findings fixed and verified —
  - Vector-Space reset-ordering: `switchCanvasTab('knowledge-graph')` (plus its grouped Vector Space tab/panel/answer cleanup) now runs before the explicit hide block in `resetToIdleState`, so the hides stick; new test `resettingAfterVectorSpaceWasRevealedLeavesEveryKnowledgeGraphControlHidden` asserts all five elements stay hidden.
  - stale-onerror race: `onerror` now captures the specific `EventSource` instance and guards its whole body on `activeProgressSource === source`, so a handler for an already-closed/nulled source is a complete no-op.
  - in-flight chat-query race: `requestedCorpusId` is now captured and checked in both the `.then()`/`.catch()` continuations before touching the DOM.
  - missing FAILED-state test: new `restartButtonIsReachableAndResetsDuringFailed` reaches FAILED via a structured `error` SSE event and asserts the same reachability/reset behavior.
  - entity-search value: `resetToIdleState` now clears `#entity-search-input`'s value and calls `window.EntitySearch.close()` (an existing, already-exposed API) to close any open results dropdown.
  - Re-verified: `mvn -q -B -pl graphrag-web -am test -Dtest=LoadNewCorpusUiTest` — 6/6 pass; `mvn -q -B clean install` — full reactor, 95/95 tests, 0 failures/errors.

## Design Notes

`#workflow-restart-button` keeps its existing `id` and label text (only its DOM location changes) so no test or styling elsewhere that might reference it by id breaks — a grep of the current codebase found no other references besides `upload.js`'s own listener and `index.html`'s markup, so this relocation is safe. `window.confirm(...)` was chosen over a custom modal purely because no modal pattern exists yet in this app and the AC's own wording ("a confirmation step") doesn't require anything richer — introducing one would be scope creep for a single Yes/No gate.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am test -Dtest=LoadNewCorpusUiTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: all new tests pass
- `mvn -q -B clean install` -- expected: full reactor green (every module, every Playwright UI test)

**Manual checks (if no CLI):**
- Load the app, load the Demo Dataset, ask a question, then click "Start over with a new corpus": confirm the dialog appears, cancelling changes nothing, and confirming clears the chat/canvas and lets you upload or demo-load a second Corpus without reloading the page.

## Auto Run Result

- Status: **done**
- Summary: `#workflow-restart-button` moved from the FAILED-only `#workflow-recovery-actions` to sit beside `#corpus-chip` in the app bar, reachable in every workflow state. Its click handler now gates on `window.confirm(...)` before calling a new `resetToIdleState()`, which mirrors and undoes everything `showCorpusChip` turns on (EventSource, Replay, detail panel, canvas, chat thread, mode choice, Vector Space tab/panel, entity search, toggles) and re-reveals `#canvas-idle` — after which the existing, unmodified upload/demo-dataset handlers just work again for a second Corpus.
- Files changed:
  - `graphrag-web/src/main/resources/templates/index.html` — relocated `#workflow-restart-button` into `.app-bar-right`.
  - `graphrag-web/src/main/resources/static/css/instrument.css` — added a small `.workflow-restart-button` rule matching the app bar's scale.
  - `graphrag-web/src/main/resources/static/js/upload.js` — added `resetToIdleState()`, the confirm gate, the `showCorpusChip` visibility pairing, and (from review) a corrected tab-switch-cleanup ordering, a stale-`onerror`-instance guard, an in-flight-chat-query staleness guard, and entity-search value/dropdown clearing.
  - `graphrag-web/src/test/java/com/graphraglens/web/ui/LoadNewCorpusUiTest.java` (new) — 6 Playwright UI tests covering every I/O Matrix row plus the two runtime-correctness patches (Vector-Space-revealed reset, FAILED-state reachability).
- Review findings breakdown:
  - Patched (5; 4 medium, 1 low): a Vector-Space-tab reset-ordering bug that could re-reveal hidden knowledge-graph chrome; a stale-`onerror`-handler race that could re-show the disconnected banner and throw a null-dereference after a restart; an in-flight chat-query race that could append a stale answer into the wrong corpus's thread; missing FAILED-state test coverage despite the spec's own I/O Matrix naming it; an uncleared entity-search input value. All fixed and reverified with new/adjusted tests.
  - Deferred (1, low): no server-side cancellation of an abandoned in-flight ingestion on restart — verified pre-existing (a full-page-reload restart already had this characteristic) and explicitly outside this UX-only epic's scope.
  - Rejected (7): stale toggle-checkbox state during the hidden idle window (no observable consequence); no dedicated keyboard-simulation test for a plain native `<button>` (nothing in this diff could plausibly break its default operability); a test-style nit (fully-qualified `Dialog::dismiss` vs. an import); a duplicated confirmation-message literal (matches an already-accepted sibling-test convention); a technically-inaccurate but harmless spec Design-Notes claim (fix would edit the spec); a deleted `fileInput.focus()` call that was already a confirmed no-op before this story (nothing working to preserve); the "real affordance" divergence (already deliberately addressed by the spec's own Design Notes, which explicitly rejected a custom modal).
- Follow-up review recommendation: **true** — 4 medium entries were patched on this first pass (two or more medium clears the bar). Named residual risk: the stale-`onerror`-instance guard and the tab-switch-ordering fix both touch timing-sensitive/state-ordering logic (the exact class of bug Story 10.2 already had to fix once) that's inherently harder to fully pin down with deterministic Playwright tests than a pure-value bug — worth a second look for any remaining ordering edge cases.
- Verification performed: `mvn -q -B -pl graphrag-web -am test -Dtest=LoadNewCorpusUiTest` (4/4 pass before patching, 6/6 after) and `mvn -q -B clean install` (full reactor, 95/95 tests, 0 failures/errors, after patching).
- Residual risks: the named timing/ordering-fragility risk above; the one deferred low-severity item (no server-side ingestion cancellation on restart).
