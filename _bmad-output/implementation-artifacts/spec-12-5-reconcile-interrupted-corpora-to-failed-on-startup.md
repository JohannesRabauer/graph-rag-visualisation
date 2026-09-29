---
title: 'Reconcile Interrupted Corpora to FAILED on Startup'
type: 'feature'
created: '2026-09-29'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '6d038281bcfd56e8a20f2ea4626b425c9f1871c0'
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** If the app crashes while a corpus is `BUILDING`, `Neo4jCorpusRegistry`'s `CorpusMeta` node stays `BUILDING` forever after restart — `CorpusController`'s existing 409 gate then blocks that corpus indefinitely, with no visible failure and no path to retry.

**Approach:** Add a startup sweep, gated to run only after `Neo4jConnectivityCheck` succeeds, that transitions every `CorpusMeta` still `BUILDING` to `FAILED` via one bulk conditional Cypher write (never per-corpus, never read-then-write).

## Boundaries & Constraints

**Always:** The sweep is one Cypher statement covering every corpus at once (`MATCH (c:CorpusMeta) WHERE c.status = 'BUILDING' SET c.status = 'FAILED'`), inside Neo4j's own transaction — never a read into the JVM followed by a separate per-corpus write, so it stays correct even if two `app` instances briefly overlap during a redeploy. Runs via a new `ApplicationListener<ApplicationReadyEvent>` in `graphrag-web`, explicitly ordered (via `@Order`) to run after `Neo4jConnectivityCheck` — Spring gives no default ordering guarantee across `ApplicationListener` beans, so both listeners need explicit `@Order` values, not just declaration order. If `Neo4jConnectivityCheck` throws (Neo4j unreachable), the reconciliation listener must not run at all for that startup attempt — this falls out naturally from Spring's listener-exception propagation as long as the order is correct, not from new guard code.

**Never:** No change to `CorpusController`'s existing 409-on-`BUILDING`/`FAILED` gate — it already reacts identically regardless of how a corpus reached `FAILED`, confirmed by investigation. No change to `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter`. No per-corpus loop calling the existing single-id `updateStatus`/`markFailed` — this is a genuinely bulk operation, a new method on `Neo4jCorpusRegistry`, not a reuse of the per-id one.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Interrupted corpus reconciled | A `CorpusMeta` node left `BUILDING` from a prior crash | After startup, its status is `FAILED` | N/A |
| Already-settled corpora untouched | `READY`/`FAILED` corpora alongside a `BUILDING` one | Only the `BUILDING` one flips; `READY`/`FAILED` ones are unchanged | N/A |
| No corpora at all | Empty `CorpusMeta` set | Sweep runs, no-op, no exception | N/A |
| Neo4j unreachable at startup | `NEO4J_URI` points nowhere | `Neo4jConnectivityCheck` aborts startup; the reconciliation listener never runs | Startup aborts as it already does (Story 12.3), unchanged |
| Listener ordering | Both listeners registered | `Neo4jConnectivityCheck` observably runs before the reconciliation listener | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-web/src/main/java/com/graphraglens/web/config/Neo4jConnectivityCheck.java` — the existing `ApplicationListener<ApplicationReadyEvent>` to order against; add an explicit `@Order` (e.g. a low/early value) so the new listener's higher value reliably runs after it. Currently no `@Order`/`Ordered` usage anywhere in the module.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jCorpusRegistry.java:166-177` — `updateStatus`'s current Cypher (`MATCH (c:CorpusMeta {corpusId: $corpusId}) SET c.status = $status`) is per-id and unconditional; add a new, separate bulk method — do not repurpose this one.
- `graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java` — existing `neo4jCorpusRegistry(Driver)` `@Bean`; the new listener constructor-injects that same bean, same pattern as `Neo4jConnectivityCheck` injecting `Driver`.
- `graphrag-web/src/test/java/com/graphraglens/web/config/Neo4jConnectivityCheckTest.java` — the narrow, non-web (`WebApplicationType.NONE`) Spring-context test pattern to follow for both the new reconciliation test and the ordering test.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java:209-214` — confirmed unaffected; no change needed.

## Tasks & Acceptance

**Execution:**
- [x] `Neo4jCorpusRegistry.java` -- add `reconcileInterruptedCorpora()`: one bulk `MATCH (c:CorpusMeta) WHERE c.status = 'BUILDING' SET c.status = 'FAILED'` write, no `$id` parameter -- the actual sweep
- [x] `Neo4jConnectivityCheck.java` -- add `@Order(Ordered.HIGHEST_PRECEDENCE)` -- establishes it must run first
- [x] New `InterruptedCorpusReconciler` `@Component` implementing `ApplicationListener<ApplicationReadyEvent>` in `graphrag-web/src/main/java/com/graphraglens/web/config/` -- constructor-injects `Neo4jCorpusRegistry`, calls `reconcileInterruptedCorpora()`, `@Order(Ordered.HIGHEST_PRECEDENCE + 1)` -- wires the sweep into startup
- [x] New test (adapter module, Testcontainers) proving `reconcileInterruptedCorpora()` flips only `BUILDING` corpora, leaves `READY`/`FAILED` alone, no-ops on empty
- [x] New test (narrow non-web Spring context, mirroring `Neo4jConnectivityCheckTest`) proving both listeners registered together run in order (`Neo4jConnectivityCheck` first) and that an unreachable Neo4j prevents the reconciliation listener from running at all

**Acceptance Criteria:**
- Given a `CorpusMeta` node left `BUILDING` from a simulated crash, when the app restarts against the same Neo4j, then that corpus is `FAILED` and `CorpusController`'s existing gate returns 409 for it, same as any other failed corpus
- Given `NEO4J_URI` is unreachable, when the app attempts startup, then it aborts (as Story 12.3 already guarantees) and no reconciliation write is attempted

## Implementation Notes

- All 5 tasks complete. `reconcileInterruptedCorpora()` is one bulk `executeWrite`, never per-corpus. Both listeners now `@Order`ed (`HIGHEST_PRECEDENCE` / `HIGHEST_PRECEDENCE + 1`); no new guard code couples them — Spring's own listener-exception propagation is what prevents the reconciler from running when connectivity fails, exactly as the Design Notes intended. `CorpusController` confirmed untouched.
- **Real bug found and fixed during implementation itself (not by review):** the first draft of the ordering test registered the shared `SharedNeo4jTestContainer.driver()` as a plain `@Bean` in a narrow test context; Spring's default destroy-method inference closed that shared singleton `Driver` when the narrow context shut down, cascading into "driver already closed" failures across other test classes sharing the same JVM. Fixed with `@Bean(destroyMethod = "")`.
- Verified: full `graphrag-web` suite (135 tests) passes cleanly on an independent rerun (not just the implementing subagent's own run) — the 5 UI-test failures the subagent saw on its own run did not reproduce, confirming they were transient sandbox flakiness, not caused by this story.
- Same residual risk as Stories 12.1/12.2/12.4: the adapter module's Testcontainers-based reconciliation tests are unconfirmed to execute in this dev environment (same tracked Docker-sandboxing gap in `deferred-work.md`); logic reviewed against the same Cypher pattern already used and verified by `updateStatus`/`markReady`/`markFailed`.

Final verification: full `graphrag-web` suite (135 tests) passes cleanly on an independent rerun after the review patches.

## Spec Change Log

## Review Triage Log

- **patch** (low) — `InterruptedCorpusReconciler` logs the identical message on every startup regardless of how many corpora were actually flipped, so operators can't tell from logs whether an incident happened. Capture and log the affected-node count from Neo4j's write-result counters.
- **patch** (low) — The two `ApplicationListener` classes each independently hardcode `@Order(Ordered.HIGHEST_PRECEDENCE)`/`@Order(Ordered.HIGHEST_PRECEDENCE + 1)` with no shared constant. Extract one to reduce duplication and the chance of a future listener silently claiming the same precedence.
- **patch** (low) — `InterruptedCorpusReconciler`'s Javadoc claims this gives a corpus "a path to retry," but verified against `CorpusController`: `FAILED` gets the identical `409` gate as `BUILDING`, no retry-by-id endpoint exists before or after this change. Soften the wording — the real benefit is a correct terminal status, not restored retry capability.
- **defer** — The bulk, time-unaware sweep can't distinguish "abandoned by a crash" from "another instance is legitimately still building this corpus right now" during a redeploy overlap — AD-23's "stays correct even if two instances briefly overlap" was about the *write* being atomic/race-free (no lost updates), which it is, not about diagnosing genuine-vs-abandoned `BUILDING` state, which it can't. A real limitation, but a fix (lease/heartbeat/age-based staleness) is a materially larger feature, and this project's actual deployment topology (AD-8: exactly one `app` container) doesn't run concurrent instances in normal operation. Logged to `deferred-work.md`.
- **defer** — Reconciled corpora carry no `reconciledAt`/reason field distinguishing "failed from an ingestion error" vs. "failed only because it was reconciled after a restart." Not a regression — the existing `markFailed` path never captured a reason either — but a real gap for crash-loop diagnosis. Logged to `deferred-work.md`.
- **defer** — No test seeds a `BUILDING` corpus and boots the real `GraphRagLensApplication`-wired context (real component scan + `ParserConfig`) to confirm reconciliation happens end-to-end through production wiring — only a direct registry-level test and a parallel hand-wired ordering test exist. Verification Gap Reviewer's own disposition: mirrors `Neo4jConnectivityCheckTest`'s already-established convention of avoiding full-context boot; a true end-to-end test is a heavier lift than this story's scope. Logged to `deferred-work.md`.
- **low, rejected** — Neither `InterruptedCorpusReconciler.onApplicationEvent` nor `Neo4jCorpusRegistry.reconcileInterruptedCorpora()` catches `Neo4jException` around the write, so a failure aborts startup. Verified this is deliberate and consistent: every other *data* write in this class and its siblings (`put`, `updateStatus`, `Neo4jGraphStoreAdapter`'s persist methods) also fails loud with no catch — only schema/constraint setup (`ensureConstraint`) gracefully degrades, for a documented, different reason (edition-dependent constraint support). Matches this epic's established "never silently degrade" ethos.
- **false** — Edge Case Hunter called `assertThatThrownBy(...).isNotNull()` in the unreachable-Neo4j test a "weak assertion." Verified false as a new weakness: this exact assertion style is `Neo4jConnectivityCheckTest`'s own pre-existing pattern from Story 12.3, not something this story introduced or weakened.
- **low, rejected** — The unreachable-Neo4j test substitutes a `RecordingOnlyReconciler` stand-in rather than a real `InterruptedCorpusReconciler`. Verified reasonable: constructing a real one there would itself need to connect to the (deliberately unreachable) Neo4j via its `Neo4jCorpusRegistry` dependency's constructor, which is circular given the test's own premise. Matches `Neo4jConnectivityCheckTest`'s established narrow-testing philosophy.
- **low, rejected** — No test covers the reconciliation write itself throwing mid-sweep. Real gap, but the fix needs driver/exception-mocking infrastructure this module has none of — same disposition as the identical `ensureConstraint`-fallback-path finding rejected in Stories 12.1/12.2/12.4.

## Design Notes

Ordering relies on Spring's own listener-exception propagation, not new guard code: if `Neo4jConnectivityCheck` throws during the same `ApplicationReadyEvent` dispatch, Spring's event multicaster does not invoke subsequent listeners for that dispatch — so placing the reconciliation listener after it in `@Order` is sufficient by itself to satisfy "never runs if Neo4j is unreachable," without an explicit try/catch coupling the two components.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-adapter-neo4j -am test` -- expected: new registry-level reconciliation test passes (or manually verified per the already-tracked Docker-sandboxing caveat in `deferred-work.md`)
- `mvn -q -B -pl graphrag-web -am test` -- expected: new listener-ordering test passes; full existing suite unaffected
- `mvn -q -B clean install` -- expected: full reactor green
