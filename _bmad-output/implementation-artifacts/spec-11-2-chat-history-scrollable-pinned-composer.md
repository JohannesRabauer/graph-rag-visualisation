---
title: 'Make the Chat History Scrollable with a Pinned Composer'
type: 'bugfix'
created: '09-27-2026'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '4d0391b248d1ba0b293c575d91069e9e5ab54017'
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** GitHub #31 reports the chat panel has no scroll view and the composer/mode-choice don't stay pinned to the bottom. Static investigation of the current tree found `.chat-panel` (`instrument.css:929-938`) is already a flex column, `.thread` (`instrument.css:1041-1049`) already has `flex:1; min-height:0; overflow-y:auto`, and `.composer-head`/`.composer` already sit below it as ordinary (non-scrolling) flex children — i.e. the exact structure the acceptance criteria ask for appears to already exist in code, with `sprint-status.yaml` still (stale) marking this story `backlog`. There is no existing test proving this actually renders/behaves correctly at runtime with many turns, so it's unverified whether this is a live bug (a runtime/browser quirk not visible from static CSS) or the fix already shipped silently under another story and just needs verification plus regression coverage.

**Approach:** Add a Playwright UI test that seeds the chat thread with enough turns to overflow the panel, scrolls within it, and asserts (a) the thread itself scrolled and (b) the mode-choice and composer's bounding boxes stayed fixed at the bottom of `.chat-panel` throughout. If the test passes against the current, unmodified code, this closes #31 as already-fixed (verified, not patched) and only the test + tracking update are needed. If the test fails, fix the minimal root cause it exposes to make the acceptance criteria hold, guided by the failure — not by guessing at CSS changes with no failing test to justify them.

## Boundaries & Constraints

**Always:**
- Verify the actual runtime behavior with a real headless-browser test before concluding whether code needs to change — do not patch CSS speculatively when the acceptance criteria might already hold.
- If a fix does turn out to be needed, keep `.mode-choice` and `.composer` non-scrolling flex-column children below `.thread`, and keep `.thread` as the sole `overflow-y: auto` region — do not introduce a second nested scroll container.
- Preserve the existing auto-scroll-to-latest-message behavior already wired in `upload.js`'s three append sites (`appendPendingMessage`, `appendMessage`, `appendAnswer`).

**Never:**
- Do not touch `.app-bar`, `#corpus-chip`, `#error-banner`, or any element outside `.chat-panel` — none of them live inside it.
- Do not restructure `.composer-head`'s internals (mode-choice radios, mode-hint) beyond what's needed to keep it pinned below the thread.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Many chat turns | Chat thread seeded with enough question/answer turns to exceed the panel's visible height | Only `.thread` scrolls; `.mode-choice` and `.composer` bounding boxes stay fixed at the bottom of `.chat-panel` throughout scrolling | No error expected |
| New message arrives while scrolled up | User has scrolled `.thread` up to read an earlier turn, then a new answer is appended | Existing auto-scroll-to-bottom behavior in `upload.js` is preserved (verified unchanged, not a new requirement) | No error expected |
| Narrow viewport (≤640px) | `.chat-panel` switches to the 45%-height mobile layout (`instrument.css:1772-1778`) | The same pinned-composer/scrolling-thread behavior holds at this breakpoint too | No error expected |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/templates/index.html:23-52` -- `.chat-panel` markup: `.thread` (message history) → `.composer-head` (mode-choice + hint) → `.composer-offline-note` → `.composer` (form), in that DOM order
- `graphrag-web/src/main/resources/static/css/instrument.css:929-938` -- `.chat-panel`: already `display:flex; flex-direction:column; overflow:hidden`
- `graphrag-web/src/main/resources/static/css/instrument.css:1041-1049` -- `.thread`: already `flex:1; min-height:0; overflow-y:auto`
- `graphrag-web/src/main/resources/static/css/instrument.css:944-957` -- `.composer-head`/`.mode-choice`: ordinary flex-column children, non-scrolling
- `graphrag-web/src/main/resources/static/css/instrument.css:1155-1161` -- `.composer`: ordinary flex-column child, last in source order
- `graphrag-web/src/main/resources/static/css/instrument.css:1772-1778` -- ≤640px breakpoint: `.chat-panel` becomes `width:100%; height:45%`, still `display:flex; flex-direction:column` (unchanged)
- `graphrag-web/src/main/resources/static/js/upload.js:12,925-935,943-963,1027` -- `chatThread` element and its three append sites, each already ending in `chatThread.scrollTop = chatThread.scrollHeight`
- `graphrag-web/src/test/java/com/graphraglens/web/ui/EntitySearchUiTest.java:120-131` -- pattern to follow for a new viewport/bounding-box test (`page.setViewportSize`, `boundingBox()`)
- `_bmad-output/implementation-artifacts/sprint-status.yaml:111` -- `11-2-...: backlog`, stale relative to the code already found in the tree

## Tasks & Acceptance

**Execution:**
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ChatPanelScrollUiTest.java` -- new Playwright test file: load the demo corpus, submit enough chat questions (or directly inject synthetic turns via `page.evaluate` appending to `#chat-thread`, matching this codebase's existing append pattern) to exceed the panel's visible height, capture `.mode-choice` and `.composer` bounding boxes, scroll `.thread` (`element.evaluate("el => el.scrollTop = el.scrollHeight")` or a wheel/mouse scroll), then assert `.thread`'s `scrollTop` actually changed while `.mode-choice`/`.composer` bounding boxes are unchanged -- gives this story the regression coverage it currently has none of, and is the mechanism that determines whether #31 is already fixed or still broken
- If the new test fails: fix the minimal root cause it identifies in `instrument.css`/`index.html` (do not speculate beyond what the failure shows)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- update `11-2-make-the-chat-history-scrollable-with-a-pinned-composer` from `backlog` to `done` once the test passes

**Acceptance Criteria:**
- Given the chat panel contains enough question/answer turns to exceed the visible panel height, when the thread is scrolled, then only the message thread scrolls and the mode choice + composer remain fixed at the bottom of the panel throughout
- Given the existing auto-scroll-to-latest-message behavior in `upload.js`, when a new message is appended, then that behavior is unchanged and still functions

## Spec Change Log

- The new `ChatPanelScrollUiTest` initially failed against the unmodified tree: with 40 synthetic chat turns injected, `#chat-thread`'s `scrollHeight` equaled its `clientHeight` (no internal scrolling at all) and `.chat-panel`'s real bounding-box height grew to ~6600px (vs. a ~720px viewport), pushing `.mode-choice`/`.composer` off-screen — reproducing GitHub #31 at runtime despite the CSS looking correct in static review. Root cause: `.canvas` (`instrument.css`, the flex-row parent of `.chat-panel`/`.graph-stage`) had no `min-height`, so its classic flexbox default automatic minimum size (content-based, since its own `overflow` is `visible`) let it grow to fit `.chat-panel`'s full, unclipped content height instead of shrinking to `.app-frame`'s fixed viewport-relative height. `.chat-panel`'s `height: 100%` then faithfully stretched to that oversized `.canvas`, defeating its own `overflow: hidden` and `.thread`'s `overflow-y: auto`. Fix: added `min-height: 0;` to `.canvas`, letting it shrink to the space `.app-frame` actually allocates it; verified this makes both the default-viewport and ≤640px-mobile-breakpoint scenarios pass (only `.thread` scrolls, `.mode-choice`/`.composer` bounding boxes stay fixed).

## Review Triage Log

### 09-27-2026 — Review pass
- verdicts: 13 findings — high 0, medium 2, low 7, false 4, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind-hunter) diff omits `sprint-status.yaml` and the spec file itself — by design: those companion tracking/spec files are committed alongside the code diff at finalization, not part of the code change under review.
  - `[false]` `[reject]` (intent-alignment) same observation (tracking/bookkeeping not in the diff) — grouped with the row above.
  - `[low]` `[patch]` (blind-hunter) `ChatPanelScrollUiTest`'s class Javadoc undersells the real bug found (reads as if the fix were verification-only, when a real CSS defect required a code change). Fix: reworded the comment to state a live bug (missing `min-height:0` on `.canvas`) was found and fixed.
  - `[false]` `[reject]` (blind-hunter) no test asserts the `.canvas` `min-height:0` fix doesn't regress `.graph-stage`'s sibling sizing — refuted: this build's own full `mvn clean install` (which includes `EntitySearchUiTest`, `MainScreenLayoutUiTest`, and every other UI test exercising `.graph-stage` rendering) passed green after this fix, already covering this concern.
  - `[medium]` `[patch]` (blind-hunter) the spec's own I/O matrix row "existing auto-scroll-to-latest-message behavior is preserved" has no executable test — only asserted as unchanged in prose. Fix: added a test that scrolls the thread up, appends one more message the way `upload.js` does, and asserts the thread scrolls back to bottom.
  - `[medium]` `[patch]` (intent-alignment) same gap (auto-scroll AC has no executable test, bypassed by direct DOM injection) — grouped with the row above.
  - `[low]` `[patch]` (blind-hunter) `assertPinnedDuringScroll()` mixes a static-imported Playwright `assertThat` with a fully-qualified AssertJ `assertThat` used 10+ times. Fix: added the missing static import for consistency.
  - `[low]` `[reject]` (blind-hunter) the 40-turn synthetic seeding hardcodes DOM class names that duplicate `upload.js`'s markup shape rather than sharing a fixture — verified as a real but low-probability drift risk, and the fix (a shared JS/Java fixture) is more than a direct correction — rejected per the low-finding-with-nontrivial-fix rule.
  - `[low]` `[patch]` (blind-hunter) the one-line CSS fix (`min-height: 0` on `.canvas`) has no inline comment explaining the non-obvious flexbox "automatic minimum size" gotcha that caused the bug, unlike neighboring well-annotated rules in the same file. Fix: added a one-line comment at the fix site.
  - `[low]` `[patch]` (blind-hunter) neither test directly asserts `.chat-panel`'s own bounding-box height stays within the viewport — only inferred indirectly via the composer/mode-choice staying pinned. Fix: added a direct assertion that `.chat-panel`'s height does not exceed the viewport height, re-creating the original bug's most visible symptom (the ~6600px panel height) directly.
  - `[low]` `[patch]` (edge-case-hunter) `boundingBox()` on `.mode-choice`/`.composer` after scrolling could return `null` if the element were ever detached/not rendered, causing an NPE that masks the real assertion failure. Fix: added `isNotNull()` guards before reading `.x`/`.y`/`.width`/`.height`.
  - `[false]` `[reject]` (intent-alignment) "named component vs. actual fix site" divergence (the fix lives in `.canvas`, an ancestor never named in the issue's own scope) — not a defect: this is exactly how root-causing works when a failing test traces a symptom to its real upstream cause; the issue's "scope for investigation" was a starting hypothesis, not a boundary, and the spec's own Boundaries section explicitly allowed following the failing test's evidence.
  - `[false]` `[reject]` (intent-alignment) "verification surface vs. user-facing surface" (diff is mostly test code) — not a defect: matches this story's explicitly verification-first approach stated in its own Design Notes; a small, well-justified code fix plus thorough regression coverage is the intended shape of this change, not a gap.

## Design Notes

Static analysis strongly suggests this bug may already be fixed in the current tree (the exact flex-column/overflow structure the acceptance criteria describe is already present), possibly landed silently as part of an earlier, unrelated story. This story's real job is therefore verification-first: write the test the acceptance criteria demand, run it against the unmodified code, and only change code if that test actually fails and names a real gap.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am -Dtest=ChatPanelScrollUiTest -Dsurefire.failIfNoSpecifiedTests=false test` -- expected: new test passes
- `mvn -q -B clean install` -- expected: full reactor green, no regressions

## Auto Run Result

**Summary:** GitHub #31 was a real, live bug despite static CSS review suggesting otherwise: `.canvas` (the flex-row ancestor of `.chat-panel`/`.graph-stage`) lacked `min-height: 0`, so it grew to fit the chat panel's full unclipped content instead of shrinking to `.app-frame`'s allotted height, defeating `.chat-panel`'s `overflow:hidden` and `.thread`'s `overflow-y:auto`. A one-line CSS fix, found and justified by a failing test written first (per this story's verification-first approach), resolves it.

**Files changed:**
- `graphrag-web/src/main/resources/static/css/instrument.css` -- added `min-height: 0;` to `.canvas`, with an explanatory comment (patched in) about the flexbox automatic-minimum-size gotcha it fixes
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ChatPanelScrollUiTest.java` -- new file: asserts many chat turns overflow `.thread` while `.mode-choice`/`.composer` stay pinned and `.chat-panel`'s height stays within the viewport (both at default and ≤640px mobile viewports), plus a third test (patched in) covering the existing auto-scroll-to-latest-message behavior
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- `11-2-...` updated from `backlog` to `done`

**Review findings breakdown:** 13 findings from 4 reviewer layers, grouped into 10 root causes.
- Patched (6 groups, 7 member findings): misleading test Javadoc undersell (low); the auto-scroll-preserved acceptance-criteria row having no executable test (medium); an assertion-style/import inconsistency (low); a missing explanatory comment on the CSS fix (low); no direct assertion of `.chat-panel`'s own bounded height (low); a missing null-guard on post-scroll bounding boxes that could mask a real failure behind an NPE (low).
- Rejected (4 groups, 6 member findings): tracking/spec files not in the reviewed diff, by design (false, x2); no test for `.graph-stage` sibling regression, refuted by this build's own green full-reactor run (false); hardcoded DOM markup in the test duplicating `upload.js`'s shape, low-probability drift with a nontrivial fix (low, rejected); two intent-alignment observations (fix landing on an ancestor the issue didn't name; the diff being mostly test code) that are correct root-causing methodology, not defects (false, x2).

**Verification performed:** `mvn -q -B -pl graphrag-web -am -Dtest=ChatPanelScrollUiTest,EntitySearchUiTest test` -- 3/3 new + 7/7 existing pass, both before and after the patch pass; `mvn -q -B clean install` -- full reactor green (exit 0), both before and after the patch pass.

**Follow-up review recommended:** `false` -- only one medium-verdict entry was patched this pass (the auto-scroll test-coverage gap); the rule for recommending a follow-up pass (a patched `high`, or two-or-more patched `medium` entries) is not met.

**Residual risks:** none rated high or medium; the rejected low-probability DOM-markup-drift risk (the test's synthetic seeding could silently stop matching `upload.js`'s real markup if that file's structure changes) remains, as does the already-covered-by-suite `.graph-stage` sibling-regression concern.
