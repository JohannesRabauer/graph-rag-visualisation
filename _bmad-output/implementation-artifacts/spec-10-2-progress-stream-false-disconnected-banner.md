---
title: 'Fix the progress stream''s false "disconnected" banner'
type: 'bugfix'
created: '09-27-2026'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      A genuine disconnect while a corpus is still BUILDING shows the
      "progress stream disconnected" banner text, but the Retry/Restart
      recovery buttons (#workflow-recovery-actions) stay hidden, since the
      transport-level onerror handler never calls renderWorkflowStatus('FAILED', ...).
    evidence: |-
      Verified by tracing upload.js: onerror only calls showErrorBanner()
      /close()/=null; it never calls renderWorkflowStatus, so
      workflowRecoveryActions.hidden stays whatever the last BUILDING-state
      render left it (true). The banner's own text tells the user "You can
      reconnect it or start over with a new corpus," but no reconnect/restart
      button is actually visible for them to click. Pre-existing, unchanged
      by this story's diff (which only gates the banner's visibility and
      never touches renderWorkflowStatus/workflowRecoveryActions).
    location: >-
      graphrag-web/src/main/resources/static/js/upload.js:934-939
    severity: medium
baseline_revision: '5039d1f98bf8532f7de9f71e6d2bf45926316f32'
---

<intent-contract>

## Intent

**Problem:** `CorpusProgressService.register()` creates a `SseEmitter(30_000L)` and never calls `emitter.complete()` after a terminal event (`ingestion-complete`/`error`), so every progress stream eventually times out; the browser's `EventSource` then fires `onerror`, and `upload.js`'s handler shows "The progress stream disconnected..." unconditionally — even when the corpus already reached `READY` and there is nothing left to stream (GitHub #21).

**Approach:** `upload.js` already tracks readiness in `activeCorpusReady` (set `true` on `ingestion-complete`, `false` on a structured `error` event or a new corpus). Gate the existing `onerror` banner on that flag: suppress it when the corpus is already known `READY`; keep today's behavior unchanged otherwise.

## Boundaries & Constraints

**Always:** Preserve the existing recovery behavior for a genuine disconnect while the corpus is still `BUILDING` (`activeCorpusReady === false`) — same banner text, same close-and-clear of `activeProgressSource`, no other behavior change. `activeProgressSource.close()` must still run on every `onerror`, ready or not, so the browser never auto-reconnects a stream with nothing left to send.

**Never:** Do not touch the structured `error` SSE event listener (`addEventListener('error', ...)`) — that path already has its own message and is unrelated to this transport-level `onerror` gap. Do not change `CorpusProgressService`/`SseEmitter` server-side lifecycle (no `emitter.complete()` change) — the client-side readiness check alone satisfies the acceptance criteria without risking new EventSource auto-reconnect edge cases from ending the HTTP response early. Do not touch DRIFT/Vector Space/other unrelated UI.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Stream closes after READY | `activeCorpusReady === true`, `EventSource.onerror` fires | Source is closed/cleared; no banner shown; workflow status stays as last rendered (`Ready`) | No error surfaced — nothing is actually wrong |
| Stream closes while BUILDING | `activeCorpusReady === false`, `EventSource.onerror` fires | Same as today: banner text shown, source closed/cleared | Existing "disconnected" banner text |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/static/js/upload.js` — `activeCorpusReady` (declared ~line 34) is already flipped `true` on the `ingestion-complete` listener (~line 915) and `false` on a structured `error` event (~line 924) and on every new corpus via `showCorpusChip` (~line 655). The `onerror` handler to change is at ~lines 934-938:
  ```js
  activeProgressSource.onerror = function () {
    showErrorBanner('The progress stream disconnected. You can reconnect it or start over with a new corpus.');
    activeProgressSource.close();
    activeProgressSource = null;
  };
  ```
  `showErrorBanner`/`hideErrorBanner` (~lines 941-949) and `errorBanner` (`#error-banner`, ~line 9) are unaffected — reused as-is.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusProgressService.java` — read-only reference confirming the root cause (`register()` at line 34-46 creates `new SseEmitter(30_000L)` with no `emitter.complete()` call after a terminal event at `emit()` lines 48-77); no change made here (see Never).
- `graphrag-web/src/test/java/com/graphraglens/web/ui/UiTestSupport.java` — reused as-is (`baseUrl()`, Playwright `page` fixture, cytoscape route stub); the new test class extends it like its siblings.
- `graphrag-web/src/test/java/com/graphraglens/web/ui/VectorBaselineTriggerUiTest.java` (lines 165-167) — reference pattern for mocking a `page.route(...)` endpoint with `Route.FulfillOptions` used to synthesize the new test's mocked `/progress` SSE responses.

## Tasks & Acceptance

**Execution:**
- `upload.js` -- gate the `onerror` handler's `showErrorBanner(...)` call behind `if (!activeCorpusReady) { ... }`, keeping the unconditional `close()`/`= null` cleanup -- suppresses the false positive once ready without touching the genuine-disconnect path
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ProgressStreamDisconnectedBannerUiTest.java` (new) -- add two Playwright UI tests that mock `**/api/corpora/*/progress` to return a synthetic SSE body ending the HTTP response (reproducing the real timeout-driven disconnect deterministically, no 30s wait): one stream that sends `heartbeat` + `ingestion-complete` before ending (asserts `#error-banner` stays hidden and `#workflow-status-text` still reads "Ready"), one that sends only `heartbeat` before ending (asserts `#error-banner` becomes visible with the existing message, unchanged behavior) -- proves both branches of the new conditional without relying on a real 30-second timeout

**Acceptance Criteria:**
- Given a Corpus has reached `READY` (`ingestion-complete` already received), when the underlying SSE connection subsequently closes, then the "progress stream disconnected" banner is not shown
- Given a genuine disconnect while the corpus is still `BUILDING` (no `ingestion-complete` received yet), when the SSE connection closes, then the existing "progress stream disconnected" banner still appears, unchanged from today

## Spec Change Log

## Review Triage Log

### 09-27-2026 — Review pass
- verdicts: 10 findings — high 0, medium 1, low 1, false 3, maybe-false 1, reject 4 (of the above; rejects are a route, not a verdict bucket — see rows)
- findings:
  - `[false]` `[reject]` Blind Hunter: a banner shown for a genuine BUILDING disconnect could stay stale after the corpus later reaches READY without the user clearing it — refuted: `EventSource` is explicitly `.close()`d and nulled on every `onerror`, so no browser auto-reconnect can occur; the only reconnection path is the Retry button (`upload.js:586-588`), which calls `hideErrorBanner()` before reconnecting — no path lets `ingestion-complete` arrive on a banner-showing client without `hideErrorBanner` already having run first.
  - `[low]` `[reject]` Blind Hunter: no test asserts the unconditional `activeProgressSource.close()`/`= null` cleanup still runs on every `onerror` — real but low-impact (a regression here only adds reconnect noise, not user-visible breakage), and verifying it from Playwright would need new internal instrumentation (no DOM-observable signal exists) — not worth the added complexity for this risk level.
  - `[maybe-false]` `[reject]` Blind Hunter: a stray late `onerror` from a superseded stream could close/null the new corpus's live stream and, if `activeCorpusReady` happens to already be `true`, suppress a banner that should have shown — could not confirm reachability: `EventSource` stops dispatching further events once `.close()` is called (readyState becomes `CLOSED` synchronously) and `showCorpusChip` closes the old stream before connecting the new one (`upload.js:605-608`), so this needs an already-queued event to escape that close, which browser event-loop semantics make unlikely; if true, this is a pre-existing race untouched by this diff's gate either way, so its if-true grade is only low — rejected per the maybe-false/low rule.
  - `[reject]` Blind Hunter: spec's Code Map cites approximate (`~`) line numbers with no note on what to do if they drift — fix is to edit this build's spec; excluded from patch/defer per triage rules.
  - `[false]` Blind Hunter: `created: '09-27-2026'` uses a non-ISO MM-DD-YYYY format — matches the established convention already used by the sibling spec `spec-10-1-entity-search-community-toggle-overlap.md` (`created: '09-27-2026'`); not an inconsistency introduced here.
  - `[reject]` Blind Hunter: no test/check confirms the structured `error` SSE event listener still behaves unchanged — the diff never touches that listener (explicit `Never` boundary); untested pre-existing code the change did not affect is out of scope per verification-gap evidence rules.
  - `[reject]` Blind Hunter: "GitHub #21" is not verified/linked to an actual issue — not a code defect; an external issue tracker's accuracy isn't checkable from this repo.
  - `[low]` `[patch]` Verification-gap (Other findings): `streamClosingAfterReadyShowsNoDisconnectedBanner` and `streamClosingWhileBuildingStillShowsTheDisconnectedBanner` assert `#workflow-status-text`/`#error-banner` state with Playwright's default timeout, unlike every sibling UI test in this module which gives the same "Ready" transition an explicit `setTimeout(20000)` (`UiTestSupport.java:110-111` and others) — inconsistent with project convention and a plausible (if minor) source of CI flakiness. Patch: add explicit `.setTimeout(20000)` to the relevant assertions in `ProgressStreamDisconnectedBannerUiTest.java`, matching sibling style.
  - `[false]` Intent-alignment-auditor: the automated tests reproduce the disconnect via immediate HTTP-response termination rather than the real 30-second `SseEmitter` idle-timeout named in the story's root cause — refuted as a gap: the client's `onerror` handler is agnostic to *why* the transport closed; both triggers fire the identical `error` event into the identical handler, so the synthetic test is a faithful, deterministic proxy — no distinct client behavior exists that only a real 30s wait would exercise.
  - `[medium]` `[defer]` Intent-alignment-auditor: the story's AC says a genuine BUILDING disconnect "still shows the existing recovery banner/actions" (plural), but tracing `upload.js` shows the plain-transport `onerror` handler never calls `renderWorkflowStatus('FAILED', ...)`, so `#workflow-recovery-actions` (the Retry/Restart buttons) stays hidden — only the banner *text* appears, which itself tells the user "You can reconnect it..." with no visible way to do so. Verified via code trace, pre-existing and unchanged by this diff (which only gates the banner's visibility, never touches `renderWorkflowStatus` or `workflowRecoveryActions`) — not caused by this story.
- patch outcomes: 1 patch-routed finding fixed and verified — explicit `setTimeout(20000)` added to the "Ready"/banner-visibility assertions in `ProgressStreamDisconnectedBannerUiTest.java`; re-verified: `mvn -q -B -pl graphrag-web -am test -Dtest=ProgressStreamDisconnectedBannerUiTest -Dsurefire.failIfNoSpecifiedTests=false` — 2/2 pass; `mvn -q -B clean install` — full reactor, all tests pass, 0 failures/errors.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am test -Dtest=ProgressStreamDisconnectedBannerUiTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: both new tests pass
- `mvn -q -B clean install` -- expected: full reactor green (every module, every Playwright UI test)

**Manual checks (if no CLI):**
- Load the app, load the Demo Dataset, wait for "Ready", then wait past the 30s SSE timeout: confirm no "disconnected" banner appears.

## Auto Run Result

- Status: **done**
- Summary: `upload.js`'s SSE `onerror` handler now suppresses the "progress stream disconnected" banner when the corpus has already reached `READY` (`activeCorpusReady === true`), while preserving today's banner for a genuine disconnect during `BUILDING`. The unconditional `activeProgressSource.close()`/`= null` cleanup runs on every `onerror`, ready or not. No server-side (`CorpusProgressService`/`SseEmitter`) change was made.
- Files changed:
  - `graphrag-web/src/main/resources/static/js/upload.js` — gated the `onerror` handler's `showErrorBanner(...)` call behind `if (!activeCorpusReady)`.
  - `graphrag-web/src/test/java/com/graphraglens/web/ui/ProgressStreamDisconnectedBannerUiTest.java` (new) — two Playwright UI tests mocking `**/api/corpora/*/progress` to reproduce the disconnect deterministically: one proving the banner stays suppressed once `READY`, one proving it still appears for a genuine `BUILDING` disconnect.
- Review findings breakdown:
  - Patched (1, low): missing explicit `setTimeout(20000)` on the new tests' state-transition assertions, inconsistent with sibling UI tests — fixed and re-verified.
  - Deferred (1, medium): pre-existing gap — a genuine `BUILDING` disconnect's banner text tells the user they can reconnect, but `#workflow-recovery-actions` (the actual Retry/Restart buttons) stays hidden, since the transport-level `onerror` handler never calls `renderWorkflowStatus('FAILED', ...)`. Not caused by this diff; recorded in frontmatter `deferred`.
  - Rejected (6): stale-banner-after-reconnect concern (refuted — Retry button already calls `hideErrorBanner()` before reconnecting); missing test for the unconditional `close()`/`=null` cleanup (low impact, no DOM-observable signal to assert against); a stray-late-`onerror`-from-superseded-stream race (unreachable given `EventSource` stops dispatching after `.close()`, and pre-existing either way); approximate line numbers in the Code Map (fix would edit the spec); non-ISO `created` date format (matches sibling spec's existing convention); no regression check on the untouched structured `error` listener (out of scope — diff never touches it); unverified GitHub issue link (not a code defect); synthetic-vs-real-30s-timeout test mechanism (refuted — the client's `onerror` handler is agnostic to the transport-level cause, so the synthetic test is a faithful proxy).
- Follow-up review recommendation: **false** — the only patched entry was `low` severity (not `high`, not two-or-more `medium`), so the work has converged.
- Verification performed: `mvn -q -B -pl graphrag-web -am test -Dtest=ProgressStreamDisconnectedBannerUiTest -Dsurefire.failIfNoSpecifiedTests=false` (2/2 pass, before and after the patch) and `mvn -q -B clean install` (full reactor, 83/83 tests, 0 failures/errors, both before and after the patch).
- Residual risks: the deferred `workflow-recovery-actions` visibility gap (medium, pre-existing) — a user hitting a genuine `BUILDING`-phase disconnect sees banner text inviting them to reconnect but no visible button to do so; unrelated to and unchanged by this fix.
