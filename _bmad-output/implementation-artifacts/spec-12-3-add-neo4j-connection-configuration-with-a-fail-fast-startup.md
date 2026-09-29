---
title: 'Add Neo4j Connection Configuration with a Fail-Fast Startup Check'
type: 'feature'
created: '2026-09-29'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '8fd61f977587969c429238ca2cc865a37d419c20'
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `ParserConfig` still bean-wires `InMemoryGraphStoreAdapter`/`InMemoryVectorStoreAdapter` directly, so Stories 12.1/12.2's real Neo4j adapters exist but are never actually used by the running app, and nothing checks Neo4j is even reachable at startup.

**Approach:** Add a single `Driver` bean (plain `org.neo4j.driver`, env-configured), flip `ParserConfig`'s `graphStorePort()`/`vectorStorePort()` to construct the real adapters from it, and fail startup loudly if Neo4j is unreachable. Because this makes every Spring-context test in `graphrag-web` (`CorpusControllerTest` + the ~15-class Playwright UI suite) require a reachable Neo4j to even start, this story also gives those tests a shared Testcontainers-managed Neo4j instance, so they keep exercising the real adapter end-to-end rather than being disconnected from what they actually run against.

## Boundaries & Constraints

**Always:** `Driver` bean built from `@Value("${NEO4J_URI:bolt://neo4j:7687}")`/`${NEO4J_USERNAME:neo4j}`/`${NEO4J_PASSWORD:graphraglens}` (password default matches `docker-compose.yml`'s own `NEO4J_AUTH` default — not a generic guess). `driver.verifyConnectivity()` runs via an `ApplicationListener<ApplicationReadyEvent>` (no existing precedent in this codebase — OPENAI_API_KEY has no equivalent startup check, it fails lazily); a failure logs clearly and aborts startup (non-zero exit), never a silent fallback. `graphStorePort()`/`vectorStorePort()` become `Driver`-parameterized and construct `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter`. `docker-compose.yml`'s `app` service gains `NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD`, same style as the existing `OPENAI_API_KEY` entry. All of `graphrag-web`'s existing `@SpringBootTest`-based tests (`CorpusControllerTest`, every UI test extending `UiTestSupport`) share one JVM-wide singleton Testcontainers Neo4j instance (started once, not per test class, via the standard Testcontainers singleton-container pattern — a static container field started in a static block, never a per-class `@Container`), with `NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD` overridden via `@DynamicPropertySource` to point at it.

**Never:** No Spring Data Neo4j (AD-2). No change to `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter`/`InMemoryGraphStoreAdapter`/`InMemoryVectorStoreAdapter` themselves (Stories 12.1/12.2 already finished them) — this story only wires them in. No GitHub Actions `services:` block and no in-memory-adapter test profile — the human explicitly chose the shared-Testcontainers approach over both alternatives. No new direct `neo4j-java-driver` dependency in `graphrag-web/pom.xml` — it's already transitively available (compile-scope) via `graphrag-adapter-neo4j`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Normal startup, Neo4j reachable | `docker-compose up` (or test context with the shared Testcontainers Neo4j) | App starts normally; `graphStorePort()`/`vectorStorePort()` are real Neo4j-backed adapters | N/A |
| Neo4j unreachable at startup | `NEO4J_URI` points at nothing listening | `ApplicationReadyEvent` listener's `verifyConnectivity()` throws; startup aborts with a clear logged error | Non-zero exit, no silent in-memory fallback |
| Existing web-layer Spring tests | `CorpusControllerTest`, any `UiTestSupport`-based UI test | Context starts against the shared singleton Testcontainers Neo4j; test behavior unchanged from before this story | N/A |

</frozen-after-approval>

## Code Map

- `graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java:68-71,85-88` — current no-arg `graphStorePort()`/`vectorStorePort()` `@Bean` methods constructing the in-memory adapters; change to take a `Driver` parameter and construct `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter`. Mirror the file's existing `@Value("${OPENAI_API_KEY:}")`-on-bean-parameter style (lines 56-66, 73-83) for the new `Driver` bean's three env vars.
- `graphrag-web/src/main/java/com/graphraglens/web/GraphRagLensApplication.java` — bare `@SpringBootApplication`, no listeners today; the new fail-fast check is a new small class (e.g. `Neo4jConnectivityCheck` implementing `ApplicationListener<ApplicationReadyEvent>`) registered as a `@Component` or `@Bean`, not added here directly.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jGraphStoreAdapter.java` / `Neo4jVectorStoreAdapter.java` — both already take `Driver` in their constructor (Stories 12.1/12.2); read their constructors only, do not modify.
- `docker-compose.yml:3,19-27` — `neo4j` service's `NEO4J_AUTH: neo4j/${NEO4J_PASSWORD:-graphraglens}` is the source of truth for the default password; `app` service's `environment:` block (currently just `OPENAI_API_KEY`) gains the three new vars in the same style.
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java:48-67` — `@SpringBootTest` + `@AutoConfigureMockMvc`, `@Autowired GraphStorePort graphStorePort` against the real bean (no mock) — needs the shared Testcontainers Neo4j wired in.
- `graphrag-web/src/test/java/com/graphraglens/web/ui/UiTestSupport.java:51` — shared `@SpringBootTest(webEnvironment = RANDOM_PORT)` base class for ~15 Playwright UI test classes; the natural place to add the singleton-container + `@DynamicPropertySource` setup so every subclass inherits it for free. `CorpusControllerTest` does not extend this class today — give it the same setup directly, or extract a small shared base/interface both use (implementer's call, whichever is less invasive).
- `graphrag-web/pom.xml` — add `org.testcontainers:neo4j` + `org.testcontainers:junit-jupiter` (test scope), mirroring `graphrag-adapter-neo4j/pom.xml`'s existing Testcontainers setup from Story 12.1 (including its `testcontainers-bom` import).
- `.github/workflows/ci.yml` — confirmed no `services:` block and no live Neo4j; no change needed here since the human chose Testcontainers over a CI services block — CI already runs Testcontainers-based tests for `graphrag-adapter-neo4j` (Docker-in-Docker on `ubuntu-latest`), so the same mechanism now also backs `graphrag-web`'s tests.

## Tasks & Acceptance

**Execution:**
- [x] `ParserConfig.java` -- add a `Driver` `@Bean` from `NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD` (defaults `bolt://neo4j:7687`/`neo4j`/`graphraglens`) -- the single connection source of truth
- [x] `ParserConfig.java` -- change `graphStorePort(Driver)`/`vectorStorePort(Driver)` to construct `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter` -- flips the switch from in-memory to real
- [x] New `Neo4jConnectivityCheck` `@Component` implementing `ApplicationListener<ApplicationReadyEvent>` -- calls `driver.verifyConnectivity()`, logs and aborts startup on failure -- the fail-fast guarantee
- [x] `docker-compose.yml` -- add `NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD` to the `app` service's `environment:` block, defaults matching the `neo4j` service's own `NEO4J_AUTH` -- one-command setup keeps working with no manual connection config
- [x] `graphrag-web/pom.xml` -- add `org.testcontainers:neo4j` + `org.testcontainers:junit-jupiter` (test scope) -- needed for the shared test container
- [x] `SharedNeo4jTestContainer.java` (new) + `UiTestSupport.java`/`CorpusControllerTest.java` -- a JVM-wide singleton Testcontainers Neo4j (`neo4j:2026.08.1-community`) + `@DynamicPropertySource` overriding `NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD` -- keeps every existing Spring-context test running against a real, reachable Neo4j
- [x] `Neo4jConnectivityCheckTest.java` (new) -- a narrow, non-web context test with an intentionally unreachable `NEO4J_URI`, asserting the context fails to start -- proves the "Never silently fall back" guarantee, not just that the happy path compiles

**Acceptance Criteria:**
- Given the app starts via `docker-compose up` with Neo4j healthy, when `ParserConfig`'s beans are created, then `graphStorePort()`/`vectorStorePort()` are `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter` instances, not the in-memory ones
- Given `NEO4J_URI` points at an unreachable address, when the app starts, then startup aborts with a clear logged error before accepting any HTTP traffic
- Given the existing `CorpusControllerTest` and UI test suite, when they run after this story, then they still pass, now against the shared Testcontainers Neo4j instead of implicitly against in-memory adapters

## Implementation Notes

- All 7 execution tasks complete. `ParserConfig` gained a `Driver` `@Bean` (`NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD`, defaults matching `docker-compose.yml`'s own `NEO4J_AUTH`), and `graphStorePort`/`vectorStorePort` now construct the real Neo4j adapters. `Neo4jConnectivityCheck` (`ApplicationListener<ApplicationReadyEvent>`) aborts startup loudly on an unreachable Neo4j — proven by `Neo4jConnectivityCheckTest`'s narrow, non-web context test. `SharedNeo4jTestContainer` is a correctly-implemented JVM-wide singleton (not `@Container`-annotated) shared by `CorpusControllerTest` and every `UiTestSupport`-based UI test via `@DynamicPropertySource`.
- **Real bug found and fixed during verification, not by the reviewers:** `ReplayAfterCorpusResetUiTest` failed reliably (not flakily — reproduced 3 times, including once with the wait timeout raised to 90s, which still failed) once real Neo4j's added latency was in the loop. Root cause was a pre-existing test fragility, exposed rather than caused by this story: `resetToIdleState()` (Story 10.4) hides `#workflow-status` but never clears its stale "Ready" text from the first corpus; the test's `containsText("Ready")` wait doesn't check the element's `hidden` state, so it could match that stale, hidden leftover text before the second corpus's own render ever ran — a race that never had a wide-enough window to hit with near-instant in-memory operations. Fixed by asserting `#workflow-status` is no longer hidden before checking its text (test-only change, `ReplayAfterCorpusResetUiTest.java`). Re-verified: this test and the full `graphrag-web` suite (129 tests) now pass cleanly and repeatably.
- Full `graphrag-web` suite (129 tests) verified green with `OPENAI_API_KEY` unset (the deterministic offline stub path `UiTestSupport` requires). Note for whoever runs this next: if a real `OPENAI_API_KEY` is set in the shell environment, `ParserConfig` picks it up even under plain `mvn test` and these tests will silently exercise the real OpenAI API instead of the offline stub — a pre-existing environment hazard, unset it before running.
- `graphrag-adapter-neo4j`'s own Testcontainers tests (`Neo4jGraphStoreAdapterTest`/`Neo4jVectorStoreAdapterTest`, Stories 12.1/12.2) are unaffected by this story and continue to hit the same pre-existing Docker-sandboxing gap tracked in `deferred-work.md` — not a new issue.

## Spec Change Log

## Review Triage Log

- **patch** (medium) — `Neo4jConnectivityCheck`'s Javadoc claims "no HTTP traffic is ever served against a broken Neo4j connection" as an absolute guarantee. Verified false as an absolute claim (confirmed independently by Blind Hunter and Verification Gap Reviewer): the embedded servlet container starts listening during context refresh, before `ApplicationReadyEvent` fires, so a request could theoretically race the abort. Soften the Javadoc to describe this as best-effort fail-fast shortly after startup, not a hard pre-listen guarantee.
- **patch** (low) — Edge Case Hunter caught a real bug in my own review-response fix: the new `assertThat(page.locator("#workflow-status")).not().isHidden())` assertion in `ReplayAfterCorpusResetUiTest` has no explicit timeout, unlike the surrounding assertions widened for real-Neo4j latency (default Playwright timeout is 5s). Add an explicit generous timeout matching the surrounding values.
- **patch** (low) — `SharedNeo4jTestContainer.INSTANCE.start()` in a static initializer, if it throws (e.g. Docker unavailable), produces an opaque `ExceptionInInitializerError`/`NoClassDefFoundError` for every subsequent `@SpringBootTest` class in the JVM instead of a clear diagnostic. Wrap with a clearer error message.
- **patch** (low) — `SharedNeo4jTestContainer` doesn't enable Testcontainers' `withReuse(true)`, despite being built specifically to avoid per-class container-start cost. Add it for local iterative-development convenience (harmless in CI, where reuse is opt-in via `~/.testcontainers.properties`).
- **patch** (low) — No comment documents the Spring Boot behavior `Neo4jConnectivityCheckTest` relies on (an `ApplicationListener` exception during `ApplicationReadyEvent` propagating out of `SpringApplication.run()` to abort startup) — add one so a future Spring Boot upgrade that changes this wouldn't silently invalidate the test's intent.
- **patch** (low) — No README documentation for the three new env vars or the fail-fast startup behavior. Added directly (not deferred to the implementer): `README.md`'s module table and "Running the app" section now describe `NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD` and the abort-on-unreachable behavior.
- **defer** — The deeper fix for the Javadoc-softened claim above (actually preventing the embedded web server from accepting connections until connectivity is verified, e.g. via a custom `WebServerFactoryCustomizer` delaying bind) is more than a direct correction and the race window is vanishingly small in practice; not worth a flaky, timing-dependent test. Logged to `deferred-work.md`.
- **false** — Edge Case Hunter flagged the spec's Boundaries claim that existing web-layer tests are "unchanged" versus `CorpusControllerTest`'s rewrite from unscoped to corpus-scoped assertions. Real wording imprecision, but its only fix is editing this build's spec, which is rejected per this workflow's own triage rule.
- **low, rejected** — Hardcoded default Neo4j credentials (`neo4j`/`graphraglens`) in `docker-compose.yml`/`ParserConfig.java`. Out of scope: this default has existed in `docker-compose.yml` since Neo4j was first added (pre-dates this epic), and a demo-safe default password is consistent with this project's explicit single-user/local-only/no-auth posture (NFR3) — not a regression this story introduced.
- **low, rejected** — Same default password duplicated across two files with no single source of truth. Real but impractical to deduplicate across a YAML file and Java code without new tooling; low value for the complexity.
- **low, rejected** — Neo4j image pinned to `2026.08.1-community`. Pre-existing pin from Stories 12.1/12.2, not introduced by this story.
- **low, rejected** — Edge Case Hunter's `CorpusControllerTest` async-ingestion-race concern (lines 270-271). Speculative — my own full-suite run (129 tests) passed cleanly and repeatably with no sign of this race manifesting.
- **false** — No test verifies `ParserConfig.driver()` reads overridden env vars rather than only defaults. Already indirectly, thoroughly covered: every one of the 129 passing tests exercises the `Driver` bean with `@DynamicPropertySource`-overridden (non-default) `NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD` values via the shared container — if `@Value` binding were broken, all of them would fail.

## Design Notes

The shared Testcontainers Neo4j must be a JVM-wide singleton (started once via a static field/static initializer block, never a per-test-class `@Container`), or ~15+ test classes each starting their own container would make the suite unacceptably slow. This is the standard "singleton container" Testcontainers pattern: a `static final Neo4jContainer<?> NEO4J` field, started once in a static block (not annotated `@Container`/`@Testcontainers`, since that lifecycle-manages per-class), left running for the JVM's lifetime (Testcontainers' own Ryuk resource reaper cleans it up afterward).

The fail-fast `ApplicationListener` and the shared test Neo4j are in tension by design: production always fails fast on an unreachable Neo4j (per this story's own Boundaries), while every Spring-context test must have Neo4j reachable *before* `ApplicationReadyEvent` fires — this is exactly why the test container has to be started (via `@DynamicPropertySource`, which runs before context refresh) rather than relying on any lazy-connect behavior.

## Verification

**Commands:**
- `mvn -q -B -pl graphrag-web -am test` -- expected: `CorpusControllerTest` and the UI suite pass against the shared Testcontainers Neo4j; the new fail-fast test passes
- `mvn -q -B clean install` -- expected: full reactor green
- `docker-compose config` -- expected: the three new env vars appear in the rendered `app` service config with correct defaults
