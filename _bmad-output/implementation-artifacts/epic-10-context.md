# Epic 10 Context: Live-Demo UX Fixes & Library Reusability Audit

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

This epic fixes two real bugs and adds two feature improvements surfaced through direct, live use of the app (filed as GitHub issues #20-#24), plus a standalone audit of `graphrag-core`'s reusability. Unlike Epics 1-9, it is not a themed, PRD-derived epic — it's a grab-bag of independent, user-reported items grouped only for sprint tracking. It matters because these are actual demo-breaking or demo-degrading defects and gaps discovered while streaming/using the tool, not speculative hardening.

## Stories

- Story 10.1: Fix entity-search / community-toggle visual overlap
- Story 10.2: Fix the progress stream's false "disconnected" banner
- Story 10.3: Color-code Entity Tags by type, toggleable
- Story 10.4: Allow loading a new Corpus after one is already active
- Story 10.5: Audit graphrag-core's reusability as a standalone library

## Requirements & Constraints

- No new/changed FRs; this epic only fixes or hardens existing UX behavior (Local/Global Search, Knowledge Graph explore, Entity inspection) and adds one audit deliverable — it is not tied to new functional requirements.
- Both controls in Story 10.1 (entity search box, community-visualization toggle) must remain independently visible and clickable at all supported viewport widths; fixes must be verified against actual rendered element heights, not assumed/eyeballed constants.
- Story 10.2's fix must preserve the existing behavior for a genuine disconnect during in-progress (BUILDING) ingestion — only the false-positive case (stream closing after the corpus already reached READY) should be suppressed.
- Story 10.3: the type-color-coding toggle defaults ON for a fresh Corpus's first run, matching the existing community-visualization toggle's default-ON convention. Colors must be assigned deterministically per distinct type value, consistently across Entities and across sessions (not randomly assigned per load).
- Story 10.3 must be reconciled visually with two other existing color languages already on the canvas — community-hull tints and Replay/trace active-state colors — so all three remain visually distinguishable from one another, not colliding.
- Story 10.4's teardown-and-reset flow is destructive (discards the current graph/trace/chat state), so it requires an explicit confirmation step before anything is torn down — it is not treated as an immediate-effect toggle.
- Story 10.5 is audit-only: it must produce a findings list and a prioritized punch list, but must not silently decide branding (`com.graphraglens` vs. a generic library identity) or whether "reusable" requires actually publishing the artifact — those are explicit open questions for the user. Any fix work it identifies is scoped to a separate follow-up story/issue.
- Accessibility floor (project-wide, "basic floor, not a compliance program"): primary interactive controls — including toggles like the one added in Story 10.3 — must be operable via keyboard alone, without requiring precise mouse interaction. Exact tab order/keybindings are an implementation detail. Colorblind-safe palettes are a soft, best-effort goal only, not a validated/blocking requirement.

## Technical Decisions

- Story 10.1 root cause: `instrument.css` positions both `#entity-search` (`top: 46px`, `z-index: 5`) and `#community-toggle-wrap` (`top: var(--space-3)`, `z-index: 2`) as `position: absolute` anchored to the same top-right corner. Fix by stacking vertically with real measured spacing, or relocating one control to a different corner.
- Story 10.2 root cause: `CorpusProgressService.register()` creates a hardcoded `new SseEmitter(30_000L)` with nothing calling `emitter.complete()` after the `ingestion-complete` event, so every stream eventually times out and fires the browser's `EventSource.onerror`, which `upload.js` shows unconditionally with no auto-clear. Acceptable fix is server-side (`emitter.complete()` right after a terminal event) and/or client-side (suppress the banner once the corpus is already known READY).
- Story 10.4: the existing `#workflow-restart-button` ("Start over with a new corpus") is confirmed to currently be a no-op beyond refocusing a hidden file input — it must either become the real reset affordance or be replaced by one that is.
- The codebase follows Hexagonal Architecture (Ports & Adapters): `graphrag-core` holds domain model and use cases behind its own port interfaces (`LlmPort`, `GraphStorePort`, `DocumentParserPort`, etc.) and has zero framework dependencies (enforced by `maven-enforcer-plugin` — no Spring, Neo4j driver, LangChain4j, or PDFBox types may appear in `graphrag-core`). `graphrag-web` is a pure adapter with no domain logic. This boundary is exactly what Story 10.5 is auditing.
- Frontend is plain, unbundled `.js` under `graphrag-web/src/main/resources/static/js/` plus Thymeleaf-rendered templates — no TypeScript, no bundler, no `package.json`-driven build step for the shipped app (a Playwright-Java test-only dependency is the sole, explicit exception, invoked via `mvn test`).
- Story 10.5's current known state to audit against: `graphrag-core` is framework-free and enforced as such, but has no module README, no `package-info.java`, inconsistent Javadoc depth, and no publishing setup.

## UX & Interaction Patterns

- The palette reserves a small, fixed categorical set for structural meaning: two mode accents (Local/Global), a third mode hue for Drift (`#C0225F`, v1.1), and a 5-hue categorical set for Community hulls (orange/blue/green/purple/gold, kept as pale/translucent tints so node/edge lines stay legible on top). Story 10.3's per-type entity coloring must be visually reconciled with both of these existing color languages plus the Replay/current-step active-state color, so none of the three become ambiguous together — avoid introducing hues that collide with existing mode or community tints.
- Existing toggle conventions to match: Local/Global Search and the community-visualization toggle are binary, single-click, immediate-effect controls with an inline plain-language explanation of what each one does — no confirmation step. Story 10.3's new type-color toggle should follow this same immediate-effect pattern (unlike Story 10.4's destructive corpus-switch action, which does require confirmation).
- The community-visualization toggle is a real checkbox (not a switch styled to look like one), specifically to avoid implying it gates backend computation it doesn't actually gate — worth keeping in mind for Story 10.3's toggle affordance and copy, given its underlying color assignment is always deterministic regardless of toggle state.
- Replay and the Entity detail panel already coexist independently on the same canvas (opening one never closes the other) — Story 10.3's canvas node coloring must not disrupt this independence.

## Cross-Story Dependencies

- Story 10.3's canvas node coloring interacts visually with the existing community-hull rendering and Replay-state highlighting on the same Knowledge Graph canvas — changes should be checked against both rather than in isolation.
- Story 10.4's teardown must correctly close any open EventSource/Replay stream, which touches the same progress-stream/SSE lifecycle that Story 10.2 is fixing — coordinate so the two changes don't reintroduce each other's bug (e.g. a corpus switch must not itself trigger a false "disconnected" banner).
- Story 10.5 is independent of the other four (an audit, not a code change) and does not block or depend on them.
