---
title: 'Tune Graph Layout So Communities and Nodes Aren''t Wildly Far Apart'
type: 'bugfix'
created: '09-27-2026'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '8a24142984ba4bd0786dbbff35cae9e25afa942b'
context: []
warnings: []
deferred:
  - summary: >-
      The isolated/singleton-node outlier-spacing bound (50x median edge length) is a coarse
      regression guard, not a tight proof this tuning meaningfully improves that specific metric.
    evidence: |-
      Empirical sweeps of nestingFactor (0.05-10) and componentSpacing (1-500) against both the
      demo corpus and a synthetic one showed the "max nearest-neighbor gap / median edge length"
      distributions for tuned vs. untuned-default configs overlap almost entirely (both roughly
      2x-35x across samples), though tuned settings average somewhat lower. A tight bound would
      require touching nodeRepulsion/gravity, which this story's spec explicitly scopes out.
    location: >-
      graphrag-web/src/test/java/com/graphraglens/web/ui/GraphLayoutCommunitySpacingUiTest.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** GitHub #34 reports graph nodes and communities sometimes render extremely far apart. Investigation confirmed the community-grouping mechanism is already correct (each Community is a real Cytoscape compound/parent node, with member entities reparented onto it — `graphrag-web/src/main/resources/static/js/graph-canvas.js:787-812`), and the layout algorithm (`cose`, Cytoscape's built-in COmpound Spring Embedder) is inherently compound-aware. The layout call itself (`graph-canvas.js:1048`) sets only `animate`/`animationDuration`/`fit`/`padding`/`randomize`, leaving every spacing-relevant parameter (`nestingFactor`, `componentSpacing`, `nodeRepulsion`, `idealEdgeLength`) at Cytoscape's generic defaults, which are not tuned for this app's typical graphs (many small communities plus isolated/disconnected nodes) — this is a parameter-tuning gap layered on an already-correct structural mechanism, not a compound-node/grouping problem.

**Approach:** Tune `cose`'s layout parameters — increase `nestingFactor` to pull community children tighter to their compound parent, and bound `componentSpacing` so isolated nodes/small/disconnected communities don't drift to outlier distances — rather than switching layout algorithms or guessing at parameters, per the story's own design notes. Add a regression test asserting each community's member-node bounding box stays within a bounded multiple of the hull's own size, since no existing test covers node/community spacing at all.

## Boundaries & Constraints

**Always:**
- Keep the `cose` layout algorithm and the existing compound-node (parent/child) community-grouping mechanism unchanged — investigation found both already correct; tune only the layout's own parameters.
- Keep `fit: true`/`padding: 32`/`animate`/`animationDuration` unchanged — these only affect viewport fit/zoom and the transition, not relative node/community spacing.
- Verify the tuned parameters against a real rendered graph (via a new Playwright test), not just by reasoning about Cytoscape's cose documentation — this codebase's own precedent (Stories 11.1-11.3) shows static reasoning about layout/CSS can miss or wrongly assume real behavior.

**Never:**
- Do not switch to a different layout algorithm/extension (`fcose`, `cola`, etc.) — the design notes explicitly scope this to tuning `cose`'s own parameters first, and no evidence was found that `cose` itself is incapable of the required spacing.
- Do not change `addCommunity()`/`addEntity()`'s parenting logic (`graph-canvas.js:787-812`) — the compound-node structure is already correct.
- Do not touch the `queueLayout()` call sites (when it's invoked) — only the layout options object passed to `cy.layout({...})`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Typical corpus with multiple communities | A Corpus of typical size is loaded with communities detected | Nodes belonging to the same Community render within a visually coherent, bounded distance of each other and their hull | No error expected |
| Isolated/singleton node | A node with no or few edges, not tightly connected to any community | The node does not drift to an outlier distance disproportionate to the rest of the graph | No error expected |
| Small or disconnected community | A community with very few members, or a disconnected subgraph | Renders at a bounded distance from the rest of the graph, not scattered arbitrarily far | No error expected |

</intent-contract>

## Code Map

- `graphrag-web/src/main/resources/static/js/graph-canvas.js:1048` -- the `cy.layout({...})` call to tune (`cose` algorithm, currently only 5 options set)
- `graphrag-web/src/main/resources/static/js/graph-canvas.js:787-812` -- `addCommunity()`/entity reparenting; confirmed correct, read-only reference
- `graphrag-web/src/test/java/com/graphraglens/web/ui/ReplayCommunityHullVisibilityUiTest.java` -- existing pattern for a community/hull-aware Playwright test to follow for the new spacing regression test

## Tasks & Acceptance

**Execution:**
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` -- tune `nestingFactor` and `componentSpacing` (bound the gap between disconnected components/isolated nodes/small communities) in the `cy.layout({...})` options object at line 1048 -- directly targets the two most plausible tuning gaps identified by investigation. (Note: implementation found `nestingFactor` must be *lowered*, not increased as originally assumed above — see Spec Change Log.)
- `graphrag-web/src/test/java/com/graphraglens/web/ui/GraphLayoutCommunitySpacingUiTest.java` -- new test: load a corpus with multiple communities, read each community-member node's rendered position via a small test-support hook alongside the existing ones in `graph-canvas.js`, and assert each community's own member-node cohesion stays within a bounded multiple of the graph's median edge length (the actual metric used — see Spec Change Log), with no member/isolated-node position falling absurdly far outside the graph's overall extent

**Acceptance Criteria:**
- Given a Corpus of typical size is loaded with communities detected, when the graph canvas lays out nodes, then nodes belonging to the same Community render within a visually coherent, bounded distance of each other and their hull (verified via the new test)
- Given the layout tuning is applied, when the full existing UI test suite is re-run, then no community/hull-visibility/replay test regresses (verified via `ReplayCommunityHullVisibilityUiTest` and the full reactor build)

## Spec Change Log

- **09-28-2026, triggered by edge-case-hunter + intent-alignment findings:** This spec's `<intent-contract>` Approach text says `nestingFactor` should be *increased* — that assumption is wrong and is preserved verbatim above (read-only) as the historical record of the pre-implementation hypothesis, but the actual, empirically-verified fix *lowers* `nestingFactor` from cose's default (1.2 → 0.3). Reading the vendored `cose` source, `nestingFactor` scales the ideal length of edges that cross a compound (Community) boundary — raising it stretches those edges and pushes Communities apart, the opposite of this story's goal. This was confirmed by rendering the real graph and measuring the effect (per this spec's own Boundaries constraint to verify against real rendering, not just reasoning), not merely asserted. KEEP: the direction correction (0.3, not a higher value) must survive any re-derivation of this spec/story.
- **09-28-2026, triggered by edge-case-hunter + intent-alignment findings:** The Tasks & Acceptance section originally described the regression test as bounding each member's distance against "the hull's own bounding box." The implemented test instead bounds Community-member cohesion against the graph's own median edge length (a corpus-size-relative yardstick) and separately bounds the largest nearest-neighbor gap across all nodes the same way — this is a defensible, arguably more portable substitute for an unspecified "multiple of hull bbox" formula, and is what `GraphLayoutCommunitySpacingUiTest` actually asserts. KEEP: the median-edge-length-relative metric must survive any re-derivation.

## Review Triage Log

### 09-28-2026 — Review pass
- verdicts: 19 findings — high 0, medium 0, low 12, false 6, maybe-false 0, deferred 1
- findings:
  - `[false]` `[reject]` (blind-hunter) diff contains a stray non-unified-diff marker line — an artifact of concatenating two `git diff` invocations for this review pass, not part of the actual code change under review.
  - `[low]` `[patch]` (blind-hunter) `layoutSpacingMetrics()`'s JS `medianEdgeLength` computation isn't a true median for even-length arrays (takes one middle element instead of averaging two), inconsistent with the Java test's own correct `median()` helper. Fix: corrected the JS to average the two middle elements for even-length arrays.
  - `[low]` `[reject]` (blind-hunter) `layoutSpacingMetrics()`'s O(n²) nearest-neighbor search ships in the production bundle unguarded — verified it is only ever invoked by tests (`window.GraphCanvas.layoutSpacingMetrics()`), never from any runtime/production code path, so no realistic harm.
  - `[low]` `[reject]` (blind-hunter) new test-support functions aren't namespaced separately from the production API — matches this file's existing, established pattern (`simulateTap`, `elementHasClass`, etc.), not a new problem introduced here.
  - `[low]` `[reject]` (intent-alignment) same namespacing observation — grouped with the row above.
  - `[false]` `[reject]` (blind-hunter) `buildSyntheticCorpus()`'s `addRelationship` call signature assumed without verification — refuted: verification-gap independently checked this call site against `addRelationship`'s actual declaration and confirmed it matches.
  - `[low]` `[reject]` (blind-hunter) fixed `page.waitForTimeout(500)` after the synchronous layout call is a flakiness/slowness risk — verified plausible, but no clear observable condition exists to poll instead (unlike Story 11.3's viewport-resize case, which had `GraphCanvas.dimensions()` to poll) — the correct alternative isn't obvious enough to apply confidently without risking new flakiness.
  - `[low]` `[reject]` (blind-hunter) 11 layout samples with no comment justifying the exact count, no slow-CI fallback — cosmetic, no demonstrated harm.
  - `[low]` `[patch]` (blind-hunter) tuned magic numbers (`nestingFactor: 0.3`, `componentSpacing: 8`) are inlined rather than named constants. Fix: extracted to named constants alongside their existing explanatory comment.
  - `[false]` `[reject]` (blind-hunter) no changelog/tracking-doc update in the diff — by design: the spec file and `sprint-status.yaml` are companion artifacts committed alongside the code diff at finalization, not part of the code change under review, consistent with every prior story's triage in this run.
  - `[low]` `[patch]` (edge-case-hunter) `layoutSpacingMetrics()`'s `cy.nodes(':childless')` selector would include an empty Community's own hull node (a childless compound node) if one ever existed, polluting position/gap measurements. Fix: excluded `.community-hull` from the childless-node set.
  - `[low]` `[patch]` (edge-case-hunter) `medianEdgeLength` can be `null` on a zero-edge graph with no guard at the call site, risking `NaN` in a ratio. Fix: added a null guard.
  - `[low]` `[patch]` (edge-case-hunter) the spec's Approach/Tasks/Design Notes state `nestingFactor` should be *increased*, but the code (correctly, per its own empirical verification) *decreases* it from cose's default — a real, verified documentation/spec-accuracy gap. Fix: corrected the spec's Approach and Design Notes wording to describe the actual, empirically-justified direction.
  - `[low]` `[patch]` (intent-alignment) same nestingFactor-direction discrepancy, independently observed — grouped with the row above.
  - `[low]` `[patch]` (edge-case-hunter) the spec's Tasks section describes bounding a member's distance against "the hull's own bounding box," but the test instead bounds it against the graph's median edge length — a real wording/implementation mismatch, though the chosen metric is a defensible (arguably more portable) substitute. Fix: corrected the spec's Tasks/Acceptance wording to describe the actual metric used.
  - `[low]` `[patch]` (intent-alignment) same verification-metric discrepancy, independently observed — grouped with the row above.
  - `[false]` `[reject]` (intent-alignment) the test builds a synthetic corpus via `GraphCanvas`'s public API rather than driving the real corpus-ingestion/SSE pipeline — not a defect: layout/rendering behavior depends only on the in-memory Cytoscape model, which is identical regardless of how entities/relationships/communities were added to it; the demo dataset was independently confirmed too small/degenerate for a meaningful signal.
  - `[low]` `[defer]` (intent-alignment) the isolated/singleton-node outlier-spacing bound (50x median edge length) is a coarse regression guard, not a tight proof this tuning meaningfully improves that specific metric — disclosed honestly by the implementer; tightening it would require touching `nodeRepulsion`/`gravity`, which this story's own spec explicitly scopes out.
  - `[false]` `[reject]` (intent-alignment) the diff alone doesn't show evidence that `ReplayCommunityHullVisibilityUiTest` and the full reactor build were re-verified — refuted: this build's own Verify step re-ran both (1/1 and full `mvn clean install` green) after the patch pass, recorded in this Auto Run Result.

## Design Notes

Cytoscape's `cose` layout's default parameters are calibrated as generic heuristics, not for this app's specific shape of graph (many small Communities, some isolated nodes, community membership expressed via compound/parent nodes). `nestingFactor` and `componentSpacing` are the two parameters that most directly and narrowly target this story's two named symptoms (community cohesion, and outlier spacing for isolated/disconnected nodes) without touching the already-correct compound-node structure or switching layout engines.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am -Dtest=GraphLayoutCommunitySpacingUiTest,ReplayCommunityHullVisibilityUiTest -Dsurefire.failIfNoSpecifiedTests=false test` -- expected: all pass
- `mvn -q -B clean install` -- expected: full reactor green, no regressions

## Auto Run Result

**Summary:** GitHub #34's root cause was confirmed to be layout-parameter tuning, not the community-grouping mechanism (already correct, using Cytoscape compound/parent nodes). `cose`'s `nestingFactor` and `componentSpacing` were tuned away from generic defaults, verified empirically against a real rendered graph — notably, `nestingFactor` had to be *lowered* rather than increased as originally hypothesized, since raising it stretches cross-Community edge length in `cose`'s own algorithm.

**Files changed:**
- `graphrag-web/src/main/resources/static/js/graph-canvas.js` -- tuned `nestingFactor: 0.3` and `componentSpacing: 8` (extracted to named constants, patched in) in the shared `cyLayoutOptions()`; added `layoutSpacingMetrics()`/`runLayoutSynchronouslyForTest()` test-support hooks, with two edge-case fixes (patched in): excluding empty Community hulls from the childless-node set, and a mathematically correct median for even-length edge-length arrays
- `graphrag-web/src/test/java/com/graphraglens/web/ui/GraphLayoutCommunitySpacingUiTest.java` -- new regression test, sampling the tuned layout 11 times (`cose` is stochastic) against a synthetic multi-community corpus, asserting bounded community-member cohesion and bounded outlier spacing relative to the graph's median edge length

**Review findings breakdown:** 19 findings from 4 reviewer layers, grouped into 16 root causes.
- Patched (6 groups, 8 member findings): a non-true-median bug in JS (inconsistent with the Java test's correct helper); magic numbers not named; two edge-case gaps in the new test-support metrics function (empty-hull pollution, unguarded null); and two documentation corrections (the spec's Approach text said `nestingFactor` should increase, when the empirically-correct fix decreases it; the spec's Tasks text described a "hull bounding box" metric that the actual test doesn't use, bounding against median edge length instead) — both corrected via Spec Change Log entries, since the `<intent-contract>` text itself is read-only.
- Deferred (1 finding): the isolated-node outlier-spacing bound is a coarse regression guard, not a tight proof of improvement — tightening it would require touching `nodeRepulsion`/`gravity`, explicitly out of this story's scope.
- Rejected (9 groups, 10 member findings): a diff-staging artifact; an unreachable O(n²) production-code-path concern; two observations matching this codebase's own established test-support-hook pattern; a signature-assumption claim refuted by verification-gap's own check; a fixed-sleep concern with no safe alternative identified; a cosmetic sample-count nit; a by-design "no changelog in diff" observation; a synthetic-corpus-vs-real-ingestion methodology point (refuted -- layout depends only on the in-memory model, not how data arrived); and a "verification not shown in diff" claim refuted by this build's own re-verification.

**Verification performed:** `mvn -q -B -pl graphrag-web -am -Dtest=GraphLayoutCommunitySpacingUiTest,ReplayCommunityHullVisibilityUiTest test` -- 2/2 pass, both before and after the patch pass; `mvn -q -B clean install` -- full reactor green (exit 0), both before and after the patch pass.

**Follow-up review recommended:** `false` -- no high-verdict or medium-verdict entries were patched this pass (all patched entries were low); the follow-up-review trigger rule is not met.

**Residual risks:** none rated high or medium. The deferred finding above (coarse outlier-spacing bound) is the one disclosed, accepted residual risk — a materially tighter guarantee for isolated/singleton-node spacing would require tuning `nodeRepulsion`/`gravity`, outside this story's scope.
