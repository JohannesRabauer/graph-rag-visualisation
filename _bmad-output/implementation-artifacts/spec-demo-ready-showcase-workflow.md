---
title: 'Demo-ready end-to-end showcase workflow'
type: 'feature'
created: '2026-09-20'
status: 'draft'
route: 'dispatch'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The current main-screen demo flow is hard to present live because it lacks a reliable “ready to query” moment and a graceful recovery path when ingestion fails, which breaks confidence during a showcase.

**Approach:** Harden the existing corpus-ingestion UX into a clear showcase workflow: explicit ready state after graph build completion, guided next action to ask a question, and actionable recovery controls on ingestion failure without page reload.

## Boundaries & Constraints

**Always:**
- Keep the existing upload/demo endpoints and SSE event contract unchanged (`/api/corpora`, `/api/corpora/demo`, `/api/corpora/{id}/progress` with `{type,data}`).
- Reuse current frontend architecture (`index.html` + `upload.js` + `graph-canvas.js`) without introducing npm tooling or framework migration.
- Preserve current local/global query behavior and retrieval trace behavior; this story improves workflow clarity, not retrieval logic.
- Keep changes backward-compatible with existing tests and current deterministic fallback mode when no `OPENAI_API_KEY` is set.

**Never:**
- Do not redesign GraphRAG retrieval algorithms (no conversion of Local Search to graph traversal in this story).
- Do not add persistence/session isolation/per-corpus graph scoping in backend stores.
- Do not add new transports or replace SSE with WebSocket/polling.
- Do not introduce a multi-page onboarding wizard; keep improvements on the current main screen.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Ingestion completes normally | User uploads files or chooses demo dataset; backend emits `ingestion-complete` | UI leaves “Building…” state, shows explicit “ready” state, and prompts user to ask a first question | N/A |
| Ingestion fails | Backend emits SSE `error` with payload | UI shows clear failure message and provides immediate retry/restart path without full page reload | Preserve current error text and keep UI interactive |
| User asks query before graph is ready | Chat submit attempted while ingestion still running | UI blocks/guards submit with clear explanation that graph build is still in progress | No backend error flood; user can retry once ready |
| SSE stream disconnects after start | EventSource closes unexpectedly | UI surfaces non-blocking warning and keeps available controls usable | Allow user to re-trigger ingestion path via existing upload/demo controls |

</frozen-after-approval>

## Open Questions

- SCOPE DEPTH FOR THIS STORY — options: WORKFLOW-ONLY (implement ready-state + recovery UX now, keep retrieval semantics unchanged; fastest path to a stable demo) / FULL SHOWCASE CREDIBILITY (also include deeper GraphRAG behavior fixes like corpus-scoped global search or graph-native local retrieval; higher effort and larger risk).

## Code Map

- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/templates/index.html` -- Main-screen affordances (ready/recovery messaging and controls).
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/static/js/upload.js` -- Core ingestion/query workflow state machine; SSE listeners; busy/ready/error transitions.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- Emits ingestion lifecycle events and error payloads; server-side contract source.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/java/com/graphraglens/web/CorpusProgressService.java` -- SSE buffering/replay behavior and terminal event lifecycle.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` -- Backend endpoint and ingestion-flow expectations to extend/regress-check.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/MainControllerTest.java` -- Rendered main-screen content checks.

## Tasks & Acceptance

**Execution:**
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/static/js/upload.js` -- Add explicit `ingestion-complete` handling and ready-state transition logic; guard pre-ready query submissions with user-facing guidance -- establishes deterministic showcase flow.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/templates/index.html` -- Add minimal UX hooks for ready and retry/restart guidance on the main screen -- makes state transitions visible to presenters.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/static/js/upload.js` -- Add recovery path for SSE `error` state (re-enable relevant controls and provide retry affordance) -- removes page-reload requirement during demos.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/MainControllerTest.java` -- Extend UI rendering assertions for new readiness/recovery elements -- protects showcase UX from regressions.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` -- Ensure lifecycle events needed by frontend flow are still emitted as expected -- keeps backend/frontend contract pinned.

**Acceptance Criteria:**
- Given a corpus ingestion run reaches completion, when `ingestion-complete` is emitted, then the main UI exits building state and clearly indicates that querying can begin.
- Given ingestion emits an error event, when the user remains on the main screen, then they can recover through visible retry/restart controls without reloading the page.
- Given ingestion is still in progress, when the user attempts to submit a question, then the UI prevents submission and explains that the graph is not ready yet.
- Given the new showcase workflow UI is rendered, when backend tests and web module tests run, then they pass without changing existing API endpoint shapes.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Design Notes

The workflow should behave as a small explicit state machine driven by existing lifecycle events:
- `ingestion-started` → Building state
- `ingestion-complete` → Ready state
- `error` → Recoverable error state

Keep this state machine concentrated in `upload.js` and reflected by minimal DOM hooks in `index.html`, so behavior remains easy to reason about and future stories can extend it without duplicating state logic.

## Verification

**Commands:**
- `cd /home/runner/work/graph-rag-visualisation/graph-rag-visualisation && mvn test` -- expected: all module tests pass.
- `cd /home/runner/work/graph-rag-visualisation/graph-rag-visualisation && mvn -pl graphrag-web test` -- expected: web module tests pass with new readiness/recovery assertions.

**Manual checks (if no CLI):**
- Start app, load demo dataset, and verify UI transitions from Building to Ready after ingestion completion.
- Trigger/observe ingestion error path and verify recovery controls are visible and usable without page reload.
