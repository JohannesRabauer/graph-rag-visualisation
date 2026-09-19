---
title: 'Empty-State Main Screen'
type: 'feature'
created: '2026-09-19'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: 'efbbab960cffaa5256c48c701104bd148c8d3b5c'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The app has no page at all yet — Story 1.2 makes it runnable, but navigating to it renders nothing.

**Approach:** Add a Thymeleaf-rendered root page (`GET /`) applying the "Instrument" design tokens from `DESIGN.md`, showing the resting/idle-state canvas: an uppercase eyebrow ("Knowledge Graph — Resting") and the exact differentiation subtitle from `EXPERIENCE.md`'s Voice and Tone section. No chat panel, upload, or ingestion UI yet — per `epic-1-context.md`, those components "don't exist until later epics."

## Boundaries & Constraints

**Always:** Root URL (`/`) renders a Thymeleaf template using the exact "Instrument" tokens (colors, typography, spacing) from `DESIGN.md`'s frontmatter. The canvas shows the uppercase eyebrow "Knowledge Graph — Resting" and, verbatim, the subtitle: "Watch a Knowledge Graph get built, clustered, and searched — the mechanics most GraphRAG tools keep hidden." Body/subtitle text uses a color with at least 4.5:1 contrast against its background (WCAG AA normal text).

**Never:** Do not build the chat panel, mode toggle, composer, or corpus chip (Epic 2/3 — explicitly out of scope for Epic 1 per `epic-1-context.md`). Do not wire any upload, Demo Dataset, or ingestion endpoint (Epic 2). Do not add Cytoscape.js or any client-side JS (no interactive graph yet — nothing to render).

</frozen-after-approval>

## Code Map

- `graphrag-web/src/main/resources/templates/` (Story 1.1) — currently empty; this story adds the first template here.
- `graphrag-web/src/main/resources/static/js/.gitkeep` (Story 1.1) — placeholder only; no JS needed for this story, left untouched.
- `graphrag-web/src/main/java/com/graphraglens/web/GraphRagLensApplication.java` (Story 1.1) — `@SpringBootApplication`; this story adds a sibling `@Controller` class in the same package, no changes to the application class itself.
- **Design-token source of truth:** `_bmad-output/planning-artifacts/ux-designs/ux-graph-rag-visualisation-2026-09-19/DESIGN.md` frontmatter (`colors`, `typography`, `spacing`, `rounded`) — copied into CSS custom properties, not re-derived.
- **Exact copy source:** `_bmad-output/planning-artifacts/ux-designs/ux-graph-rag-visualisation-2026-09-19/EXPERIENCE.md` line 37 (Voice and Tone) for the subtitle; `DESIGN.md`'s "Idle / empty" component description for the eyebrow pattern.
- **Contrast correction (superseded by review, see Review Triage Log):** `DESIGN.md` assigns `{colors.ink-400}` (`#8B94A0`) to the eyebrow/canvas-title text, which measures 2.94:1 against `{colors.paper}` — below WCAG AA. Planning-time reasoning excused this by citing `DESIGN.md`'s "acceptable v1 gap" language, but that tolerance is explicitly scoped to Community-hull *colorblind-safety*, not general text-contrast ratios — an invalid borrow, caught in review. Corrected: the eyebrow uses `{colors.ink-600}` (`#4B5563`, 7.24:1) instead — an existing, already-approved token from the same palette, not a new color — satisfying UX-DR20's accessibility floor, which is a hard Epic 1 requirement (unlike the colorblind-safety tolerance, which is the only thing `DESIGN.md` actually marks best-effort).

## Tasks & Acceptance

**Execution:**
- [x] `graphrag-web/src/main/resources/static/css/instrument.css` -- CSS custom properties for every color/typography/spacing token in `DESIGN.md`'s frontmatter, plus the app-frame, app-bar, and canvas (grid background, eyebrow, subtitle) styles -- the one stylesheet the page shell and all future epics' components build on
- [x] `graphrag-web/src/main/resources/templates/index.html` -- Thymeleaf template: app bar (brand mark + "GraphRAG Lens" wordmark only, no corpus chip yet), full-width canvas region with the 24px grid background, eyebrow "Knowledge Graph — Resting", and the verbatim differentiation subtitle -- the actual page shell
- [x] `graphrag-web/src/main/java/com/graphraglens/web/MainController.java` -- `@Controller` with `@GetMapping("/")` returning view name `"index"` -- serves the page

**Acceptance Criteria:**
- Given the app running (any means — a plain `mvn spring-boot:run` is enough to check this without Docker), when `GET /` is requested, then the response is HTML containing the eyebrow text "Knowledge Graph — Resting" and the exact subtitle sentence
- Given the rendered page, when its `<head>` is inspected, then `instrument.css` is linked and defines the documented color/typography custom properties
- Given the rendered page, when the subtitle's or the eyebrow's computed color and canvas background are checked, then their contrast ratio is ≥ 4.5:1 for both
- Given the rendered page, when inspected, then no chat panel, composer, upload control, or `<script>` tag referencing Cytoscape.js is present

## Implementation Notes

- Created `graphrag-web/src/main/resources/templates/` (did not previously exist) and `.../static/css/` directories.
- `instrument.css` defines every `colors`/`typography`/`rounded`/`spacing` token from `DESIGN.md`'s frontmatter as a CSS custom property (typography tokens split into per-property vars, e.g. `--font-eyebrow-size`, since a single shorthand var can't hold a multi-field token), plus `.app-frame`, `.app-bar`, `.brand`, and `.canvas`/`.canvas-idle` rules. No component-level styles (chat, toggle, scrubber, nodes) were added — those components don't exist until later epics per `epic-1-context.md`.
- `index.html` renders the app bar (brand mark + "GraphRAG Lens" wordmark, no corpus chip) inside a `<header>` landmark with an `<h1>` for the wordmark, and a full-width canvas with the 24px grid background, the eyebrow, and the verbatim subtitle. The em dash is written as the literal `—` character (not an HTML entity) so a raw-HTML substring check for the exact copy succeeds. `<link rel="icon" href="data:,">` was added to `<head>` to suppress the browser's automatic `GET /favicon.ico` request without shipping an icon asset.
- `MainController` is a plain `@Controller` with `@GetMapping("/")` returning view name `"index"`; `GraphRagLensApplication.java` and `application.yml` were left untouched (Thymeleaf/web starters were already present from Story 1.1).
- Post-review fix: the eyebrow now uses `var(--ink-600)` instead of `var(--ink-400)` (a `.brand-title` rule was added to reset the new `<h1>`'s default margin/font-size to match the previous `.brand span` look). Confirmed computationally: ink-600 (`#4B5563`) on paper (`#FAFAF9`) = 7.24:1 — both the eyebrow and the subtitle now pass the ≥4.5:1 AC.
- Post-review fix: added `graphrag-web/src/test/java/com/graphraglens/web/MainControllerTest.java`, a `@WebMvcTest(MainController.class)` + `MockMvc` test asserting the exact eyebrow/subtitle text, the `instrument.css` link, and the absence of `chat`/`composer`/`upload`/`cytoscape`/`<script` (case-insensitive) in the response body.
- Discovered while wiring the test: on Spring Boot 4.1.1 `@WebMvcTest`/`MockMvc` auto-configuration moved out of `spring-boot-test-autoconfigure` into a new artifact, `spring-boot-webmvc-test` (package `org.springframework.boot.webmvc.test.autoconfigure`), which `spring-boot-starter-test` does not pull in on its own. Added `spring-boot-webmvc-test` as a `test`-scope dependency in `graphrag-web/pom.xml` (version inherited from the existing `spring-boot-dependencies` BOM import) — the only `pom.xml` change in this story.

## Spec Change Log

## Review Triage Log

Review pass 1 — 3 layers (blind-hunter, edge-case-hunter, verification-gap), correct diff this time (all 5 changed files present, 16.25KB).

- **medium** — blind-hunter + verification-gap (same root cause, grouped): zero automated test coverage for `MainController`/`index.html` — all four ACs are only verified by one-off manual `curl` commands recorded as prose, not a check that runs again. `spring-boot-starter-test` is already an unused test dependency in `graphrag-web/pom.xml`. Verified (verification-gap's finding arrives pre-verified per the triage rules; independently, `find`/`grep` confirmed zero test files or CI anywhere in the repo). → patch: add `MainControllerTest` (`@WebMvcTest` + `MockMvc`).
- **medium** — blind-hunter (two findings, same root cause, grouped): the eyebrow's `--ink-400`-on-`--paper` contrast (2.94:1) fails WCAG AA, and the spec's own justification for accepting it — reusing `DESIGN.md`'s "acceptable v1 gap" language — was borrowed from an unrelated tolerance (colorblind-safety of the Community palette, not text contrast). Verified: recomputed both ratios myself, confirmed `DESIGN.md`'s "best-effort" language is scoped only to Community-hull colorblind-safety in the source document, not text contrast generally, and UX-DR20 (accessibility floor) is a hard Epic 1 requirement, not best-effort. → patch: switch the eyebrow to `{colors.ink-600}` (already an approved token in the same palette, 7.24:1), not a new color. Corrected directly above (Code Map) and in the Acceptance Criteria.
- **false** — blind-hunter: `context: []` is empty despite the spec citing `DESIGN.md`/`EXPERIENCE.md`/`epic-1-context.md`. Refuted: per `spec-template.md`'s own rule, `context:` is for material "not already distilled into the spec body" — every concrete value needed (exact hex colors, exact copy, exact contrast numbers) was already distilled directly into this spec's Code Map and Boundaries, so nothing further needs loading from those source files. `context: []` was correct, not an oversight.
- **low** — blind-hunter: no semantic landmarks (`<div class="app-bar">` instead of `<header>`, no `<h1>` anywhere) — real, narrow accessibility gap for screen-reader users navigating by heading/landmark. Verified via direct inspection of `index.html`. → patch.
- **low** — blind-hunter: no favicon served or referenced, causing an avoidable `GET /favicon.ico` 404 on every page load. Verified. → patch: add a minimal inline `data:` favicon link.
- **false** — blind-hunter: viewport meta tag implies responsiveness but the layout has no breakpoints/media queries for small viewports. Refuted: `DESIGN.md`'s Layout & Spacing section explicitly states "no responsive collapse for v1... targets a developer's own machine and a stream capture, not phone or tablet viewports" — mobile/tablet behavior is an explicit non-goal by design decision, not an oversight; the viewport meta tag is standard boilerplate present on virtually every page regardless of responsiveness and creates no obligation here.

## Verification

**Commands run:**
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -q -pl graphrag-web test -Dtest=MainControllerTest -DfailIfNoTests=true` -- `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -q package` (full reactor, includes the new test) -- `BUILD SUCCESS`
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -q -pl graphrag-web spring-boot:run &` then `curl -s http://localhost:8080/` -- 200 OK; body contains the literal `Knowledge Graph — Resting` eyebrow, the exact differentiation subtitle sentence, `<header class="app-bar">`, `<h1 class="brand-title">GraphRAG Lens</h1>`, and `<link rel="icon" href="data:,">`
- `curl -s http://localhost:8080/css/instrument.css` -- 200 OK; contains `--paper: #FAFAF9`, `--ink-900: #14181C`, `--accent: #2563EB`, and `.canvas-idle .eyebrow { ... color: var(--ink-600); }`
- Checked the rendered `index.html` body for `chat`, `composer`, `upload`, `cytoscape`, `<script` (case-insensitive) -- no matches
- Process stopped cleanly after verification; confirmed no lingering `java`/`spring-boot` processes and the port no longer answers

**Result:** All four Acceptance Criteria pass, now backed by an automated test (`MainControllerTest`) in addition to manual `curl` checks.

**Manual checks (if no CLI):**
- Open `http://localhost:8080/` in a browser at normal size and confirm the eyebrow and subtitle are legible against the canvas background, matching the "Instrument" light-mode palette (no dark mode).
