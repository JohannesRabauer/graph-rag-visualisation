---
title: GraphRAG Lens — Design
status: draft
created: 2026-09-19
updated: 2026-09-19
name: GraphRAG Lens
description: Live GraphRAG-on-Neo4j visualizer for a solo creator's coding stream. Instrument register — a lab-bright canvas where the graph is the only thing that gets to be loud.
colors:
  paper: '#FAFAF9'
  panel: '#FFFFFF'
  chrome: '#F1F2F4'
  page-surround: '#DCE0E5'
  ink-900: '#14181C'
  ink-600: '#4B5563'
  ink-400: '#8B94A0'
  line: '#E4E7EB'
  line-strong: '#CBD1D8'
  accent: '#2563EB'
  accent-soft: '#DCE7FD'
  global: '#0E7C86'
  global-soft: '#DCF2F1'
  active: '#E85D2B'
  active-soft: '#FFE9DE'
  node-fill: '#FFFFFF'
  node-line: '#4B5563'
  community-1: '#FDE7D8'
  community-1-label: '#C77F4F'
  community-2: '#DCE9FD'
  community-2-label: '#5C82C4'
  community-3: '#E4F5DE'
  community-3-label: '#6FA363'
  community-4: '#F4E3F7'
  community-4-label: '#9B6BA8'
  community-5: '#FBF3D0'
  community-5-label: '#B99A2E'
typography:
  ui:
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif'
    note: 'Default system sans for all chrome, chat, and labels — the interface is not the star, so it borrows the platform voice rather than asserting a brand typeface.'
  body:
    fontFamily: '{typography.ui.fontFamily}'
    fontSize: 13.5px
    lineHeight: '1.65'
  question:
    fontFamily: '{typography.ui.fontFamily}'
    fontSize: 13px
    lineHeight: '1.5'
  data-mono:
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'
    note: 'Reserved exclusively for data and step labels: step counters, trace captions, corpus chip metadata, the URL bar. Never used for prose.'
    fontSize: 11px
  eyebrow:
    fontFamily: '{typography.ui.fontFamily}'
    fontSize: 10.5px
    fontWeight: '700'
    letterSpacing: 0.08em
    note: 'Uppercase micro-labels (canvas title, idle-state eyebrow, section headers).'
  tag:
    fontFamily: '{typography.ui.fontFamily}'
    fontSize: 10px
    fontWeight: '700'
    letterSpacing: 0.06em
    note: 'Uppercase — search-mode tag on an answer ("Local Search · Answer").'
rounded:
  sm: 6px
  DEFAULT: 7px
  md: 8px
  lg: 10px
  full: 9999px
spacing:
  '1': 4px
  '2': 8px
  '3': 12px
  '4': 16px
  '5': 20px
  '6': 24px
  '7': 28px
  '8': 32px
  grid-unit: 24px
components:
  chat-panel:
    background: '{colors.panel}'
    border-right: '1px solid {colors.line}'
    width: '340px'
  mode-toggle:
    border: '1px solid {colors.line-strong}'
    radius: '{rounded.DEFAULT}'
    active-local-background: '{colors.accent-soft}'
    active-local-foreground: '{colors.accent}'
    active-global-background: '{colors.global-soft}'
    active-global-foreground: '{colors.global}'
    inactive-foreground: '{colors.ink-400}'
    label-typography: '{typography.tag}'
  message-question:
    background: '{colors.ink-900}'
    foreground: '#FFFFFF'
    radius: '10px 10px 2px 10px'
    typography: '{typography.question}'
  message-answer:
    foreground: '{colors.ink-900}'
    typography: '{typography.body}'
    tag-color-local: '{colors.accent}'
    tag-color-global: '{colors.global}'
  replay-cta:
    border: '1px dashed {colors.line-strong}'
    radius: '{rounded.DEFAULT}'
    foreground: '{colors.ink-600}'
    emphasis-foreground: '{colors.ink-900}'
  composer:
    border-top: '1px solid {colors.line}'
    input-border: '1px solid {colors.line-strong}'
    input-radius: '{rounded.md}'
    send-button-background: '{colors.ink-900}'
    send-button-radius: '{rounded.md}'
  graph-canvas:
    background: '{colors.paper}'
    grid-line: '{colors.line}'
    grid-cell: '24px'
    title-typography: '{typography.eyebrow}'
    title-color: '{colors.ink-400}'
  node-default:
    fill: '{colors.node-fill}'
    stroke: '{colors.node-line}'
    stroke-width: '1.5px'
    radius: '9px'
  node-active:
    fill: '{colors.active-soft}'
    stroke: '{colors.active}'
    stroke-width: '2.5px'
    radius: '15px'
  node-previous-step:
    fill: '{colors.accent-soft}'
    stroke: '{colors.accent}'
    stroke-width: '2px'
    radius: '12px'
  edge-default:
    stroke: '{colors.node-line}'
    stroke-width: '1px'
  edge-traversed:
    stroke: '{colors.active}'
    stroke-width: '2px'
  edge-upcoming:
    stroke: '{colors.node-line}'
    stroke-width: '1px'
    dash: '3 3'
  community-hull:
    fills: ['{colors.community-1}', '{colors.community-2}', '{colors.community-3}', '{colors.community-4}', '{colors.community-5}']
    opacity: '0.55–0.7'
    label-typography: '{typography.data-mono}'
    label-colors: ['{colors.community-1-label}', '{colors.community-2-label}', '{colors.community-3-label}', '{colors.community-4-label}', '{colors.community-5-label}']
  step-badge:
    background: '{colors.panel}'
    border: '1px solid {colors.line-strong}'
    radius: '{rounded.sm}'
    typography: '{typography.data-mono}'
    emphasis-color: '{colors.active}'
  scrubber:
    background: '{colors.panel}'
    border-top: '1px solid {colors.line}'
    rail-color: '{colors.line-strong}'
    fill-color: '{colors.accent}'
    tick-default-border: '{colors.line-strong}'
    tick-done-border: '{colors.accent}'
    tick-now-border: '{colors.active}'
    tick-now-fill: '{colors.active}'
    caption-typography: '{typography.data-mono}'
  transport-button:
    radius: '{rounded.sm}'
    border: '1px solid {colors.line-strong}'
    background: '{colors.panel}'
    play-background: '{colors.ink-900}'
    play-border: '{colors.ink-900}'
  corpus-chip:
    background: '{colors.paper}'
    border: '1px solid {colors.line}'
    radius: '{rounded.full}'
    typography: '{typography.data-mono}'
    foreground: '{colors.ink-600}'
  community-detection-toggle:
    off-background: '{colors.panel}'
    off-border: '{colors.line-strong}'
    on-background: '{colors.accent-soft}'
    on-foreground: '{colors.accent}'
  error-banner:
    background: '{colors.active-soft}'
    border: '1px solid {colors.active}'
    foreground: '{colors.ink-900}'
    emphasis-foreground: '{colors.active}'
  node-detail-panel:
    background: '{colors.panel}'
    border-left: '1px solid {colors.line}'
    width: '300px'
    heading-typography: '{typography.eyebrow}'
    body-typography: '{typography.body}'
    tag-chip-background: '{colors.chrome}'
    tag-chip-typography: '{typography.data-mono}'
---

## Brand & Style

GraphRAG Lens is a scientific instrument, not a consumer product. It's a solo hobby project built by one developer to understand GraphRAG deeply enough to teach it live, on stream — so the visual register is deliberately "oscilloscope," not "app." A bright, evenly-lit white canvas, hairline chrome, precise ticked controls, and exactly two saturated colors doing real work (blue for Local Search, teal for Global Search) plus one warm alert tone reserved for "this is active right now." Everything else — panels, borders, labels — stays desaturated and quiet on purpose, because the product's whole value proposition is that the *graph* is interesting, not the UI wrapped around it.

The core design mandate, straight from the discovery pass: the app must be a "no-brainer to use." Ease-of-use is not itself the differentiator to sell — it exists so that a live audience's attention (and the creator's own) stays on GraphRAG mechanics, never on figuring out the interface. Chrome recedes. Tutorial-grade clarity wins over cleverness every time.

Light mode is a deliberate, non-negotiable choice for v1 (not a default with a dark twin deferred) — a lab-bright canvas keeps graph-line and community-hull contrast at its highest for a teaching tool people will squint at through a stream capture.

## Colors

The palette holds two structural neutrals, two "mode" accents, one alert/active color, and a small categorical set for Communities. Nothing else gets to be a color.

- **Paper (`#FAFAF9`)** is the base canvas for the graph canvas and idle states — a hair warmer than pure white so the grid doesn't glare on camera.
- **Panel (`#FFFFFF`)** is the surface for the chat panel, composer, scrubber bar, and any raised control — the brightest neutral, reserved for "where you interact," distinct from "where you look" (`{colors.paper}`).
- **Chrome (`#F1F2F4`)** is the browser-chrome-style top bar only — never used inside the product surface itself.
- **Ink-900 (`#14181C`)** is primary text and the default node stroke's darkest use (send button, question bubble fill). **Ink-600 (`#4B5563`)** is secondary text and the default edge/node-stroke color. **Ink-400 (`#8B94A0`)** is tertiary/placeholder text and inactive toggle labels.
- **Line (`#E4E7EB`)** is the hairline for every border, divider, and canvas grid — kept at the lowest contrast that still reads. **Line-strong (`#CBD1D8`)** is reserved for things people manipulate directly: the scrubber rail, input borders, toggle borders, transport buttons.
- **Accent (`#2563EB`)** means **Local Search** everywhere it appears: the active toggle state, the answer tag, the "previous step" node ring, the scrubber fill. **Accent-soft (`#DCE7FD`)** is its tint, used only as a fill behind accent-colored elements, never as a standalone chip.
- **Global (`#0E7C86`)** means **Global Search**, the cool teal complement to accent-blue, used identically (active toggle, answer tag) but never mixed with Accent in the same component. **Global-soft (`#DCF2F1`)** is its tint.
- **Active (`#E85D2B`)** is the single warm, alert-adjacent color in the system. It marks "the current step" — the actively traversed edge, the current node in a Replay, the "now" tick on the scrubber — and doubles as the error-state color (FR-5, FR-9/FR-10 failure states) precisely because both meanings share the same instinct: "look here, something is happening that needs your attention right now." **Active-soft (`#FFE9DE`)** is its fill tint (active-node halo, error banner background).
- **Community-1 through Community-5** are hull-tint colors for community visualization (main-screen toggle and the always-on Explore page view). The mockup ships three (`community-1` orange, `community-2` blue, `community-3` green) because its demo dataset has three Communities. This spine extends the set to five, using the same tint/label pairing logic and choosing simple, visually separated categorical hues (orange, blue, green, purple, gold) in the spirit of a ColorBrewer qualitative set, since the PRD sets no ceiling on Community count. Colorblind-safety is a soft, best-effort goal per the memlog, not a validated/contrast-tested palette — an acceptable v1 gap, not a blocker.

Avoid: introducing a third "mode" hue (Local/Global stays exactly two), using Active for anything decorative, tinting Panel or Paper away from neutral, and giving Community hulls saturated fills (they stay pale tints so node/edge lines read on top of them).

## Typography

One font family throughout — the system UI sans (`-apple-system` / Segoe UI stack) — because GraphRAG Lens has no brand voice to assert typographically; the instrument register means type recedes exactly like chrome does. There is no display/hero typography anywhere in this product.

The one hard rule: **monospace (`{typography.data-mono}`) is reserved exclusively for data** — step counters ("03 / 05"), trace captions, the corpus chip's document count, the browser URL bar. It never appears in prose, chat messages, or button labels. This is what gives the interface its "instrument reading" feel: numbers and state look measured, sentences look written.

- `{typography.eyebrow}` — uppercase, tracked-out micro-labels: canvas title ("Knowledge Graph — Replaying Trace"), idle-state eyebrow, section headers. Never body-length text.
- `{typography.tag}` — uppercase, small, bold: the search-mode tag on an answer bubble ("Local Search · Answer").
- `{typography.question}` / `{typography.body}` — ordinary sentence case for chat question and answer content. This is where the tutorial-clarity mandate lives: plain sentences, no jargon left unexplained.

## Layout & Spacing

Spacing scale: 4 / 8 / 12 / 16 / 20 / 24 / 28 / 32px, plus a `{spacing.grid-unit}` of 24px that drives the graph canvas's background grid specifically (not general layout spacing) — it's what gives the canvas its "graph paper" instrument feel.

The main screen is a fixed two-region layout: a 340px chat panel (`{components.chat-panel.width}`) on the left, the graph canvas filling the remainder on the right, both inside a single bordered "browser" frame. There is no responsive collapse for v1, and no separate Responsive & Platform section: the app targets a developer's own machine and a stream capture, not phone or tablet viewports, per the PRD's single-user/local/browser-based scope.

Density is deliberately low: generous internal padding (16–20px) inside chat messages and canvas headers, hairline dividers instead of heavy card borders, and a visible grid on the canvas background rather than a solid fill — motion and structure should read as precise and mechanical, not busy.

## Elevation & Depth

Elevation is minimal and functional, never decorative. The outer app frame gets a single soft shadow (`0 1px 2px rgba(20,24,28,.06), 0 12px 32px rgba(20,24,28,.10)`) to lift it off the page surround (`{colors.page-surround}`, `#DCE0E5`) — this is the only "floating" surface in the system. Inside the app, the step-badge and idle-state card get a light shadow (`0 2px 6px rgba(20,24,28,.08)`) to read as an overlay on the canvas. Everything else — chat panel, scrubber, canvas — sits flush, separated only by `{colors.line}` hairlines. No hover-lift, no card shadows on nodes or messages: depth is reserved for "this floats above the graph," never for routine hierarchy.

## Shapes

Corners are consistently soft-but-tight: `{rounded.sm}` (6px) for small controls (transport buttons, step badge), `{rounded.DEFAULT}` (7px) for the mode toggle and replay CTA, `{rounded.md}` (8px) for the composer input and send button, `{rounded.lg}` (10px) for the outer app frame and idle-state card. `{rounded.full}` is reserved for true circular/pill shapes only: nodes, community-hull labels' swatch dots, and the corpus chip. Nothing in the chrome uses a hard 0px corner or a full pill outside those two cases — the instrument register is precise, not sharp.

## Components

- **Chat panel** (`{components.chat-panel}`) — left rail, `{colors.panel}` background, right hairline border. Contains the mode toggle, message thread, and composer, top to bottom.
- **Local/Global Search mode toggle** (`{components.mode-toggle}`) — two-segment control, uppercase tag-weight labels. Active segment fills with the matching mode's soft tint and text in its accent color (`{colors.accent-soft}`/`{colors.accent}` for Local, `{colors.global-soft}`/`{colors.global}` for Global); inactive segment is `{colors.ink-400}` text on `{colors.panel}`. A one-line explanatory hint below the toggle always states what the active mode does in plain language (tutorial-clarity mandate) — for example, "Local Search traverses specific Entities and Relationships around your question."
- **Message bubbles** — question bubbles (`{components.message-question}`) are dark-filled, right-aligned, sharp-cornered on the tail side. Answer content (`{components.message-answer}`) is unbubbled body text tagged with a small colored mode label. Every answer that has a Retrieval Trace carries a **Replay CTA** (`{components.replay-cta}`): a dashed-border pill-ish row reading "Replay this answer's Retrieval Trace — N steps."
- **Composer** (`{components.composer}`) — single-line text input plus a dark circular-ish send button with a play-style glyph. Placeholder copy stays plain ("Ask a question about the Corpus…").
- **Graph canvas** (`{components.graph-canvas}`) — the hero surface. Faint 24px grid on `{colors.paper}`. An uppercase eyebrow title state-labels what's showing ("Knowledge Graph — Replaying Trace" / "Knowledge Graph — Resting"). A legend row lists visible Community names with color swatches when the community-detection toggle (main screen) or the always-on Explore view is active.
- **Nodes and edges** — default nodes (`{components.node-default}`) are small white-filled, dark-stroked circles labeled below in body type. The node just-visited in a Replay step gets the accent ring (`{components.node-previous-step}`); the currently active node gets the larger warm-active ring (`{components.node-active}`). Edges default to a thin dark hairline (`{components.edge-default}`); the edge just traversed thickens and turns Active-colored (`{components.edge-traversed}`); edges not yet reached in the trace render dashed (`{components.edge-upcoming}`).
- **Community hulls** (`{components.community-hull}`) — soft, pale ellipses behind their member nodes, each with an uppercase monospace label in a matching darker tint. Visible only when the main-screen toggle is ON, or always on the Explore page.
- **Step badge** (`{components.step-badge}`) — a small floating monospace chip overlaid on the canvas during Replay, reading, for example, "Step 3 / 5 — traversed outwitted," with the step number and traversed-relationship name in `{colors.active}`.
- **Retrieval Trace scrubber** (`{components.scrubber}`) — bottom bar of the canvas region. Transport buttons (step-back / play-pause / step-forward, `{components.transport-button}`) sit left of a horizontal track: a thin rail (`{colors.line-strong}`) with an accent-colored fill up to the current position, and discrete circular ticks per trace step — done ticks ring in accent, the current tick is a larger filled Active dot, future ticks are hollow line-strong outlines. A monospace step counter ("03 / 05") sits at the right; a one-line monospace caption below states the trace in plain sequence ("matched X → traversed Y → Z → next: …").
- **Corpus chip** (`{components.corpus-chip}`) — small pill in the app bar naming the active Corpus and document count (for example, "Sherlock Holmes — Demo Dataset · 12 documents"), monospace, with a small neutral status dot.
- **Community-detection toggle** (`{components.community-detection-toggle}`, main screen only) — defaults ON for a fresh Corpus's first run, and can be toggled freely afterward; when ON, adopts the accent tint (reuses `{colors.accent-soft}`/`{colors.accent}` rather than introducing a third color, since this toggle is orthogonal to the Local/Global mode toggle and doesn't need its own hue).
- **Error banner** (`{components.error-banner}`) — appears inline in the chat thread or canvas header on an LLM-call or extraction failure (FR-5); Active-soft fill, Active border, plain-language message, no icon glyphs beyond the existing warm-color cue.
- **Explore page canvas** — reuses the graph canvas, node/edge, and community-hull tokens above wholesale (`{components.graph-canvas}`, `{components.node-default}`, `{components.community-hull}`); Communities render with their hulls always visible here (no toggle, unlike the main screen). No chat panel occupies the left rail, so the canvas has the full frame width to itself.
- **Node detail panel** (`{components.node-detail-panel}`) — Explore page only, slides in from the right on node click. Eyebrow heading names the Entity; body text lists its Relationships and any details; tags render as small `{colors.chrome}`-filled monospace chips in a wrapping row. Closes on clicking elsewhere on the canvas or clicking the same node again.

## Do's and Don'ts

| Do | Don't |
|---|---|
| Keep the graph canvas the brightest, most detailed surface on screen | Add decorative color, gradients, or texture to chat/chrome that competes with the graph |
| Use `{colors.accent}` for Local Search and `{colors.global}` for Global Search, consistently, everywhere | Introduce a third saturated "mode" color, or swap accent/global meaning between components |
| Reserve `{typography.data-mono}` for data (steps, counts, trace captions) | Set prose, buttons, or chat messages in monospace |
| Explain toggles and modes in one plain sentence near the control | Ship a control (Local/Global, community toggle) with no inline explanation of what it does |
| Use `{colors.active}` for "this is the current step" and for error/failure states | Use `{colors.active}` decoratively, or introduce a second red/alert hue |
| Keep Community hulls pale/translucent tints | Fill Community hulls with saturated color that competes with node/edge legibility |
| Show the scrubber's done/now/future ticks distinctly at a glance | Make Replay feel like a generic media-player scrub bar with no step semantics |
