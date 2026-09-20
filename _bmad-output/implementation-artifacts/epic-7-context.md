# Epic 7 Context: DRIFT Search

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Add DRIFT as a third query mode beside Local and Global Search so the app can demonstrate a hybrid GraphRAG path: start from Community summaries, spawn targeted Local Search sub-questions, then re-rank and synthesize a final answer. This matters because the product’s value is teaching retrieval mechanics visibly, and DRIFT extends that teaching story with a clearly different, multi-stage path that should be understandable both in the answer contract and in replay.

## Stories

- Story 7.1: Add DRIFT to the Mode Choice & Query Contract
- Story 7.2: Implement AnswerDriftSearch
- Story 7.3: Capture the DRIFT Trace
- Story 7.4: Replay the DRIFT Trace as a Branching Tree

## Requirements & Constraints

DRIFT must be an explicit third mode that a user selects directly; there is no automatic routing between modes. A DRIFT query must use the same request/response contract as existing query modes except for extending `mode` to include `DRIFT`. Successful answers still return `answerId`, `traceId`, and `answer`; “no answer found” remains a distinct successful response shape, and DRIFT must name itself specifically when no viable sub-questions can be produced. Actual failures must still surface as clear visible errors rather than retries, silent hangs, or ambiguous empty results.

The retrieval behavior is fixed in purpose: DRIFT begins with existing Community summaries, derives targeted sub-questions from that pass, answers those sub-questions through existing Local Search behavior, then re-ranks and synthesizes one final answer. The feature is educational, not evaluative: it is meant to show a third retrieval mechanism, not to introduce scoring, benchmarking, or hidden optimization logic. Scope should stay confined to v1.1 DRIFT behavior; the broader vector-comparison work was added in the same sprint change, but DRIFT itself should not absorb vector-specific responsibilities.

Replay remains a post-answer experience, not live streaming retrieval visualization. The captured trace must preserve enough structure to show DRIFT’s branching shape, and the UI must keep that shape visible rather than collapsing it into a longer linear sequence. A branching-aware replay is in scope only for DRIFT. The app remains single-user, local-only, browser-based, and environment-configured; no auth, hosting, or in-app configuration should be introduced through this epic.

## Technical Decisions

DRIFT is an additive v1.1 extension, not a rework of Epics 3–5. The query contract extends the existing mode enum from `LOCAL | GLOBAL` to `LOCAL | GLOBAL | DRIFT`, while preserving the established answer, no-answer, and error response shapes. This keeps frontend/backend integration stable while adding a new execution path.

`AnswerDriftSearch` should be introduced as its own use case in the existing architecture seams, orchestrating Community-summary selection plus existing Local Search logic instead of inventing a parallel retrieval stack. LLM-driven sub-question generation still stays behind the existing LLM port abstraction, and the change must respect the project’s hexagonal boundaries and provider-swappability constraints.

Trace handling follows the existing retrieval-trace model: one ordered sequence addressed by a single `traceId`, held outside Neo4j and fetched for replay by id. DRIFT extends that model with a new `sub-question-spawned` step kind carrying the spawned question text and its parent community context. Each spawned branch’s Local Search steps remain ordered beneath that branch, followed by a final re-rank/synthesize step. Even though the replay is visually branching, the underlying trace still needs one deterministic traversal order so the existing transport controls remain meaningful.

This epic should reuse the current replay API, transport model, and graph-canvas integration patterns rather than introducing a separate replay subsystem. It is also explicitly additive to the already-accepted trace-step design, so implementation should extend the trace model carefully without breaking Local or Global replay behavior.

## UX & Interaction Patterns

The mode selector is now a three-way radio-style choice placed directly above the composer, using colored dots plus labels rather than segmented tabs. DRIFT uses the dedicated rose token `#C0225F`, and that color meaning must stay consistent anywhere DRIFT is represented. The inline hint below the mode choice must immediately explain the active mode in plain language; for DRIFT, the wording is that it runs a community pass, spawns targeted sub-questions, then re-ranks and synthesizes.

DRIFT replay uses a branching tree view in the same replay area beneath the graph canvas. The tree must show a community-pass root, a fan of sub-question branches, and a single convergence node for re-rank/synthesize. Transport controls do not change: step forward/back, play/pause, and scrubber drag still work, but they advance through a fixed order of community pass, each branch in spawn order, then convergence. Unreached branches keep the existing upcoming-edge treatment, and completed branches retain a small resolved marker once passed.

The overall UX should keep the app tutorial-clear and mechanically explicit. DRIFT should feel like another demonstrable retrieval mode in the same shared chat thread, not like a separate page, tab, or workflow. Accessibility expectations remain the project’s basic floor: the mode choice and replay controls must be keyboard-reachable, and DRIFT’s color usage must not be the only signal of state.

## Cross-Story Dependencies

Epic 7 depends on earlier query and replay foundations already established elsewhere: existing Community summaries from the community pipeline, existing Local Search behavior for sub-question execution, and the existing retrieval-trace/replay transport model. Within the epic, Story 7.1 establishes the selectable mode and contract surface; Story 7.2 supplies the new execution path; Story 7.3 depends on that execution path to capture DRIFT-specific steps; and Story 7.4 depends on the captured DRIFT trace plus the existing replay controls and canvas patterns.

At the broader backlog level, this epic was added post-MVP as a v1.1 extension and is explicitly additive. It should not require reopening or redesigning the earlier Local Search, Global Search, or baseline replay stories—only extending their shared contracts and patterns in a backward-compatible way.
