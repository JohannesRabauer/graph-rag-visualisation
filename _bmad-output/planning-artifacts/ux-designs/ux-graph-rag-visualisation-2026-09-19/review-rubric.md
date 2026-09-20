# Spine Pair Review — GraphRAG Lens

## Overall verdict

The spine pair is largely a clean, source-extractable contract: sources resolve, all three UJs get full Key Flows, every DESIGN.md token resolves, the single mockup is linked with spine-wins-on-conflict stated, and both files follow the canonical section shapes in the correct order. The 2026-09-20 follow-up's two targeted edits (mid-ingestion 409 blocking, Replay-forces-hull-visible) are each internally sound, but the ingestion edit left one stale row (Chat panel's "not gated" language) that a downstream consumer could misread as still allowing mid-ingestion queries. The most consequential defect is unrelated to the follow-up: DESIGN.md names the community toggle `community-detection-toggle` even though both spines are explicit that it never touches detection — a naming trap for whoever implements it next.

## 1. Flow coverage — strong
Checked PRD §2.3's three UJs (UJ-1, UJ-2, UJ-3) against EXPERIENCE.md's Key Flows for verbatim naming, named protagonist, numbered steps, a climax beat, and a failure/edge-case path.
### Findings
- **low** Flow 2 (UJ-2, "Setting up before a stream") has no failure/edge-case path, unlike Flows 1 and 3 (EXPERIENCE.md lines 106–112). *Fix:* either add one line for a setup failure (e.g. Docker Compose fails / port conflict) or note explicitly that UJ-2 has no UX-relevant failure surface, matching the PRD (which also omits an edge case for UJ-2).
- **low** Edge-case labeling drifts between flows: Flow 1 uses "**Edge case / failure:**" (line 104), Flow 3 uses "**Edge case:**" (line 123). *Fix:* pick one label and use it consistently.

## 2. Token completeness — strong
Extracted every token in DESIGN.md's YAML frontmatter (colors, typography, rounded, spacing, components) and every `{path.to.token}` reference in both files' prose (grep-verified); all resolve to a defined token. No color is missing a hex value; light/dark pairs are correctly N/A (light-mode-only, stated as deliberate).
### Findings
- **medium** No load-bearing combination gets a stated contrast target (a number, not just "should read clearly"). Community-hull labels sit on 0.55–0.7-opacity pale tints, and `ink-600`/`ink-400` text sits on `paper`/`panel` — exactly the combinations a lab-bright, stream-captured instrument register depends on (DESIGN.md Colors §207–221; EXPERIENCE.md Accessibility Floor §81–88, which states a "basic floor, not compliance program" but gives no number). *Fix:* add one sentence with a target ratio (e.g. "body text ≥ 4.5:1 against its surface"), even as a soft/best-effort goal, matching the rigor already given to colorblind-safety.
- **low** `components.graph-canvas.grid-cell: '24px'` (frontmatter line 118) duplicates `spacing.grid-unit: 24px` (line 79) as a re-typed literal instead of `{spacing.grid-unit}`. *Fix:* reference the token so the two can't drift.

## 3. Component coverage — thin
Extracted every component named in DESIGN.md.Components and every component-like row in EXPERIENCE.md.Component Patterns; cross-checked both directions for a real (not one-word) behavioral rule per visual spec.
### Findings
- **high** DESIGN.md's `community-hull` token/component (frontmatter lines 146–150; prose line 257) specifies only the *visible* tint state (fills, `0.55–0.7` opacity, label colors). It never specifies what a *hidden* hull renders as, nor the *forced-visible-despite-toggle-OFF* override state that EXPERIENCE.md's "Community-visualization toggle" row (line 56) and the 2026-09-20-added Replay-scrubber note (line 57) both require to exist. `.memlog.md`'s bug-fix entry confirms the label is restored too when a hull is forced visible, but neither spine states that — a downstream consumer has to reverse-engineer the hidden/forced-visible visual states from behavior text alone. *Fix:* add `community-hull.hidden` (opacity 0 or equivalent) and note in the forced-visible behavioral rule that the label reappears too, not just the hull shape.
- **high** EXPERIENCE.md's Chat panel row still reads "Available immediately, even while ingestion/Community detection are still running in the background (memlog: single continuous screen, not gated)" (line 54) — written before the 2026-09-20 mid-ingestion-blocking change and never updated. Read on its own, "not gated" reads as license to submit and get an answer mid-ingestion, which directly contradicts the corrected "Ingestion in progress" State Patterns row (line 66) that now blocks submission with a 409. The two rows are technically compatible (panel is visually present; only submission is blocked) but nothing in the Chat panel row signals that distinction. *Fix:* add a clause to the Chat panel row, e.g. "— submission itself is guarded during ingestion, see State Patterns," so a reader of Component Patterns alone doesn't reconstruct the superseded behavior.
- **medium** Composer (`{components.composer}`) and Replay CTA (`{components.replay-cta}`) both have DESIGN.md visual specs but no dedicated EXPERIENCE.md.Component Patterns row (table at lines 52–59 covers Chat panel, mode toggle, community toggle, scrubber, and the two Explore-page components, but not these two). Composer's submit-while-building behavior and the Replay CTA's click-to-open-scrubber behavior are only inferable indirectly via State Patterns. *Fix:* add one row each, even a short one.
- **low** Step badge and Error banner similarly lack dedicated Component Patterns rows; their behavior is reasonably recoverable from the scrubber row and the error-state rows respectively, so this is more a completeness gap than a real ambiguity.

## 4. State coverage — adequate
Walked both IA surfaces (Main screen, Explore page) and listed the states each should carry (empty, in-progress, error, populated, selected/detail) against EXPERIENCE.md's State Patterns table.
### Findings
- **medium** The Explore page's empty state (no Corpus ingested yet — an explicit FR-16 consequence) is documented only inside Flow 3's edge case (line 123), not as its own State Patterns table row, unlike the main screen's exactly-analogous "Idle / empty (no Corpus yet)" row (line 65). *Fix:* promote it to a State Patterns row for parity and so a consumer scanning that table alone doesn't miss it.
- **low** No State Patterns row for "Explore page — node selected / detail panel open" (the panel-open state is described only as a Component Patterns behavioral rule, line 59). Defensible since it's a direct 1:1 click response rather than a state with its own treatment nuances, but worth a one-line row for table completeness.

## 5. Visual reference coverage — strong
Listed `mockups/` (one file: `direction-instrument.html`); `wireframes/` and `imports/` don't exist.
### Findings
None. The single mockup is linked once, inline, at the relevant IA section (EXPERIENCE.md line 31), names exactly what it illustrates (both the active-Replay and idle/resting-canvas variants), and states "Spine wins on conflict." No orphans, no unspecific references. The Explore page's lack of a mock is explicitly a stated choice, not an omission.

## 6. Bloat & overspecification — adequate
Checked for pixel specs where tokens should cover it, source restatement, prose-where-a-table-works, unread-by-anyone sections, and decorative narrative untied to a decision. DESIGN.md is allowed editorial voice; EXPERIENCE.md is not.
### Findings
- **medium** Elevation & Depth (DESIGN.md lines 241–243) hardcodes box-shadow values (`0 1px 2px rgba(20,24,28,.06), 0 12px 32px rgba(20,24,28,.10)` and `0 2px 6px rgba(20,24,28,.08)`) directly in prose with no corresponding frontmatter token — the only visual category in the file that isn't tokenized, unlike Colors/Typography/Rounded/Spacing. *Fix:* add an `elevation` token block, or state explicitly why shadows are the one exception.
- **low** Node radius literals (9px/12px/15px) and stroke-widths aren't tied to the `rounded` scale. Likely intentional (these are circle sizes, not corner radii) but a one-line rationale would remove the ambiguity.
- No findings of restated source material, table-shaped prose, or unread sections — both files stay tight. Key Flow narrative language (e.g. Flow 1's climax beat) matches the shape-reference examples' own use of scene-setting prose in Key Flows, so it isn't miscategorized "editorial voice creep" in EXPERIENCE.md.

## 7. Inheritance discipline — thin
Checked sources-frontmatter resolution, verbatim UJ naming, Glossary consistency, component-name identity across all sections/files, and EXPERIENCE.md token references resolving to DESIGN.md.
### Findings
- **critical** The community-visualization toggle is named two different — and semantically conflicting — ways across the pair. EXPERIENCE.md consistently calls it the "Community-visualization toggle" (Component Patterns line 56, Interaction Primitives line 77, Key Flow 1 step 4), matching FR-7 exactly (toggling only the *formation visualization*, never re-running detection — stated explicitly in both files). DESIGN.md instead names the same control, and its token key, `community-detection-toggle` (frontmatter line 179; Components prose line 261) — which contradicts the very rule stated two sentences later in that same DESIGN.md bullet ("toggling ... doesn't need its own hue" / never affects detection). A story-writer or architect implementing off DESIGN.md's token name alone could reasonably (and wrongly) build a control that gates actual Leiden community detection (FR-6), which both spines explicitly forbid. *Fix:* rename the DESIGN.md token/component to `community-visualization-toggle` to match EXPERIENCE.md and the FR it actually realizes.
- **low** Glossary term "Tag" is capitalized consistently in EXPERIENCE.md (line 59) and the PRD Glossary (§3), but DESIGN.md's node-detail-panel description lowercases it ("tags render as small ... chips," line 264) in the same sentence that capitalizes "Relationships." *Fix:* capitalize to match the Glossary.
- **low** EXPERIENCE.md's scrubber row points to `DESIGN.md.scrubber` (line 57), but the frontmatter path is actually `components.scrubber`. Harmless but imprecise as a cross-reference.
- **low** DESIGN.md's full component name is "Local/Global Search **mode** toggle" (line 252) vs EXPERIENCE.md's "Local/Global Search toggle" (Component Patterns line 55) — minor wording drift, not a semantic conflict like the community-toggle issue above.

## 8. Shape fit — strong
Checked DESIGN.md's section order against the canonical sequence and EXPERIENCE.md's required-default sections against the checklist, including whether dropped/invented sections earn their place.
### Findings
- No findings on ordering: DESIGN.md runs Brand & Style → Colors → Typography → Layout & Spacing → Elevation & Depth → Shapes → Components → Do's and Don'ts exactly in canonical order. EXPERIENCE.md carries all eight required defaults (Foundation, IA, Voice and Tone, Component Patterns, State Patterns, Interaction Primitives, Accessibility Floor, Key Flows) in the expected order.
- **low** "Responsive & Platform" is dropped from EXPERIENCE.md, which is defensible (desktop-only stream app, no breakpoints) — but the justification for dropping it lives only in DESIGN.md's Layout & Spacing section (line 237), not in EXPERIENCE.md itself. A reader of EXPERIENCE.md alone sees the section simply missing with no pointer to why. *Fix:* add a one-clause cross-reference in EXPERIENCE.md's Foundation section.
- No invented sections that fail to earn their place; none omitted that should be required-when-applicable given this is a single-user, desktop-only, no-auth app.

## Mechanical notes

- Sources frontmatter (`brief.md`, `addendum.md`, `prd.md`) all resolve and were read; UJ-1/UJ-2/UJ-3 names are verbatim from the PRD.
- The "Ingestion in progress" row's 2026-09-20 edit is otherwise clean: no other State Pattern, Voice and Tone entry, or Key Flow step still asserts the old "queries proceed against a partial graph" allowance — Flow 1's steps are correctly sequenced (ingest → detect → query) and the Voice and Tone "ready" copy example reinforces the new gated behavior. The one loose end is the stale Chat panel Component Patterns row flagged in §3.
- The referenced supersession source, `_bmad-output/implementation-artifacts/spec-demo-ready-showcase-workflow.md`, exists and its I/O matrix ("Query attempted before readiness ... blocked with clear 'graph still building' guidance ... No backend query call is made") matches the EXPERIENCE.md text verbatim in substance.
- `ARCHITECTURE-SPINE.md` exists at `_bmad-output/planning-artifacts/architecture/architecture-graph-rag-visualisation-2026-09-19/ARCHITECTURE-SPINE.md`; the memlog's note that its AD-14 needs a companion update is a flagged, out-of-remit item, not a spine-pair defect — noted here only for completeness, not scored.
- No Mermaid diagrams appear in either file.
- No broken `{path.to.token}` references found in either file (verified by extracting every occurrence in both files and checking against DESIGN.md's frontmatter).
