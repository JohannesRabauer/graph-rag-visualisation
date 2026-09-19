---
title: 'Empty-State Main Screen'
type: 'feature'
created: '2026-09-19'
status: 'in-review'
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
- **Verified contrast gap (pre-existing in the approved design system, not introduced here):** `DESIGN.md` assigns `{colors.ink-400}` (`#8B94A0`) to the eyebrow/canvas-title text; measured against `{colors.paper}` (`#FAFAF9`) this is 2.94:1, below WCAG AA even for large text (3:1). The differentiation subtitle itself uses `{colors.ink-600}` (7.24:1, passes comfortably) per the mockup's own CSS (`.idle-copy p`), so the one AC-critical sentence is unaffected. Implementing the eyebrow with the token exactly as specified, consistent with `DESIGN.md`'s own stated v1 posture on accessibility ("a soft, best-effort goal... an acceptable v1 gap, not a blocker" — stated there for Community-hull colorblind-safety, same risk tolerance applied here). Flagged for a future accessibility pass, not blocking this story.

## Tasks & Acceptance

**Execution:**
- [x] `graphrag-web/src/main/resources/static/css/instrument.css` -- CSS custom properties for every color/typography/spacing token in `DESIGN.md`'s frontmatter, plus the app-frame, app-bar, and canvas (grid background, eyebrow, subtitle) styles -- the one stylesheet the page shell and all future epics' components build on
- [x] `graphrag-web/src/main/resources/templates/index.html` -- Thymeleaf template: app bar (brand mark + "GraphRAG Lens" wordmark only, no corpus chip yet), full-width canvas region with the 24px grid background, eyebrow "Knowledge Graph — Resting", and the verbatim differentiation subtitle -- the actual page shell
- [x] `graphrag-web/src/main/java/com/graphraglens/web/MainController.java` -- `@Controller` with `@GetMapping("/")` returning view name `"index"` -- serves the page

**Acceptance Criteria:**
- Given the app running (any means — a plain `mvn spring-boot:run` is enough to check this without Docker), when `GET /` is requested, then the response is HTML containing the eyebrow text "Knowledge Graph — Resting" and the exact subtitle sentence
- Given the rendered page, when its `<head>` is inspected, then `instrument.css` is linked and defines the documented color/typography custom properties
- Given the rendered page, when the subtitle's computed color and canvas background are checked, then their contrast ratio is ≥ 4.5:1
- Given the rendered page, when inspected, then no chat panel, composer, upload control, or `<script>` tag referencing Cytoscape.js is present

## Implementation Notes

- Created `graphrag-web/src/main/resources/templates/` (did not previously exist) and `.../static/css/` directories.
- `instrument.css` defines every `colors`/`typography`/`rounded`/`spacing` token from `DESIGN.md`'s frontmatter as a CSS custom property (typography tokens split into per-property vars, e.g. `--font-eyebrow-size`, since a single shorthand var can't hold a multi-field token), plus `.app-frame`, `.app-bar`, `.brand`, and `.canvas`/`.canvas-idle` rules. No component-level styles (chat, toggle, scrubber, nodes) were added — those components don't exist until later epics per `epic-1-context.md`.
- `index.html` renders the app bar (brand mark + "GraphRAG Lens" wordmark, no corpus chip) and a full-width canvas with the 24px grid background, the eyebrow, and the verbatim subtitle. The em dash is written as the literal `—` character (not an HTML entity) so a raw-HTML substring check for the exact copy succeeds.
- `MainController` is a plain `@Controller` with `@GetMapping("/")` returning view name `"index"`; `GraphRagLensApplication.java`, `application.yml`, and `pom.xml` were left untouched (Thymeleaf/web starters were already present from Story 1.1).
- Confirmed computationally: ink-600 (`#4B5563`) on paper (`#FAFAF9`) = 7.24:1 (subtitle, passes ≥4.5:1); ink-400 (`#8B94A0`) on paper = 2.94:1 (eyebrow) — matches the Code Map's pre-documented, accepted gap exactly; not touched, per the spec's own instruction not to "fix" it.

## Spec Change Log

## Review Triage Log

## Verification

**Commands run:**
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -q install -DskipTests` then `mvn -q package` -- `BUILD SUCCESS`
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -q -pl graphrag-web spring-boot:run &` then `curl -s http://localhost:8080/` -- 200 OK; body contains the literal `Knowledge Graph — Resting` eyebrow and the exact differentiation subtitle sentence
- `curl -s http://localhost:8080/css/instrument.css` -- 200 OK; contains `--paper: #FAFAF9`, `--ink-900: #14181C`, `--accent: #2563EB` custom properties
- Checked the rendered `index.html` body for `chat`, `composer`, `upload`, `cytoscape`, `<script` (case-insensitive) -- no matches
- Process stopped cleanly after verification; confirmed no lingering `java`/`spring-boot` processes and the port no longer answers

**Result:** All four Acceptance Criteria pass.

**Manual checks (if no CLI):**
- Open `http://localhost:8080/` in a browser at normal size and confirm the eyebrow and subtitle are legible against the canvas background, matching the "Instrument" light-mode palette (no dark mode).
