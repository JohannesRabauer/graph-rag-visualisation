# PRD Quality Review — GraphRAG Lens

## Overall verdict

This is a well-calibrated hobby-project PRD: it names real trade-offs instead of smoothing them over, has an honest thesis (make GraphRAG's mechanics *visible*, not just working), and doesn't over-formalize a single-operator tool with personas or UJs it doesn't need. The main weakness is done-ness clarity on the core retrieval/visualization mechanics (FR-6, FR-9, FR-10 in particular) — an engineer picking this up cold would know *what* to build but not always *how to tell it's done*. For the stated stakes (solo learning project, live-stream demo, no external users) this is a pass with notes rather than a blocker.

## Decision-readiness — strong

The PRD surfaces genuine trade-offs rather than hedging. §4.4's Local/Global Search toggle is explicitly framed as "a deliberate choice over automatic routing... so the two methods can be shown side by side rather than hidden" — a real decision with a named alternative given up. Similarly §4.5 states replay-after-completion was chosen "over live-streaming, since replay supports rewinding" and §4.2/FR-5 states no-retry/no-fallback is "by design," reinforced by the counter-metric SM-C1 explicitly warning against undoing that decision for a "smoother-looking stream." These are decisions stated as decisions, with the cost named.

The two Open Questions (§9) are genuinely open — deferred to specific downstream workflows (UX pass, architecture) rather than rhetorical questions answered in the next breath.

### Findings
None at critical/high severity. This dimension holds up.

## Substance over theater — strong (one soft spot)

No persona theater: the three JTBDs (§2.1) are honestly framed as three hats worn by one person (creator / streamer / future maintainer), not inflated into separate personas. The Vision (§1) is specific to this product — "captures exactly which nodes and communities the answer drew on, then lets you scrub back and forth through that retrieval trace" is not swappable into a generic GraphRAG PRD. The Reliability and Single-user NFRs (§5) are concrete rather than boilerplate ("the system does not implement retries or cached fallback... but a failure must always surface as a clear, visible error state").

### Findings
- **medium** — Provider-flexibility NFR is adjective-shaped, not bound (§5) — "the LLM integration must not hardcode assumptions that would block swapping the LLM provider later" has no testable consequence of its own; it points to the brief's addendum for mechanism, which is acceptable, but as written in the PRD it reads as the one boilerplate-flavored NFR in an otherwise concrete section. *Fix:* either drop it from Cross-Cutting NFRs (since the addendum already owns it) or add one concrete criterion (e.g., "provider selection is config-driven, not a hardcoded client reference in ingestion/query code").

## Strategic coherence — strong

There's a real thesis: GraphRAG's value is in showing *why* an answer came out the way it did, not just producing an answer. Every feature cluster (ingestion → live-LLM graph construction → visible community formation → dual-mode query → replayable trace) serves that thesis rather than reading as a feature backlog. Success Metrics validate the thesis directly — SM-1 ("can confidently and correctly explain GraphRAG live") and SM-2 ("genuinely engaging to watch") are not activity metrics in disguise, and SM-C1 is a real counter-metric that would catch the thesis being undermined by convenience shortcuts (auto-retry, caching). MVP scope reads as "experience/problem-solving" shape and the feature ordering matches that, not an easiest-first list.

### Findings
None at critical/high severity.

## Done-ness clarity — thin

This is the dimension carrying the most real risk, though it's forgivable at hobby stakes rather than disqualifying. Roughly half the FRs have an explicit "Consequences (testable)" block (FR-1, FR-2, FR-4, FR-12 implicitly via FR-9/10); the other half state a capability with no verifiable condition attached.

### Findings
- **high** — FR-9 and FR-10 (Local Search / Global Search) have no testable consequence at all (§4.4). "System answers the query using Local Search" doesn't say what distinguishes a correct Local answer from a Global one in the trace, whether the UI must indicate which mode produced the answer, or what happens if the selected mode has no relevant neighborhood/community data to work from. Since the side-by-side contrast of these two modes is the PRD's own stated reason for making the toggle explicit (§4.4), leaving their success conditions unstated undercuts the feature's own rationale. *Fix:* add one consequence each, e.g. "the Retrieval Trace records which mode was used" and "a query with no matching neighborhood/community surfaces a visible empty-result state rather than an empty or malformed answer."
- **medium** — FR-6 (Detect communities, §4.3) has no consequence at all — no bound on when detection runs relative to ingestion, what happens with a corpus too small/sparse to cluster meaningfully, or what "done" means beyond "runs." FR-7 partially compensates by embedding a negative constraint inline ("not just a static rendering of the final grouping"), but FR-6 has nothing. *Fix:* add a minimal consequence, e.g. "community detection runs automatically after FR-4 completes and produces at least one Community grouping, or a visible message if the graph is too sparse to cluster."
- **low** — FR-3, FR-8, FR-11, FR-14, FR-15 are simple enough (one-click dataset selection, chat input, render, docker-compose, env var) that their done-ness is close to self-evident from the FR text itself; flagging only for completeness, not as an action item.

## Scope honesty — strong

Non-Goals (§6) does real work — eight explicit exclusions covering deployment shape, backend choice, file types, benchmarking, safety-net behavior, live-streaming, packaging, and marketing polish. The one deferred item with residual ambiguity (library extraction) is flagged with `[NOTE FOR PM]` at §7.2 rather than silently dropped. The Assumptions Index (§10) correctly reports zero outstanding inline `[ASSUMPTION]` tags — a check of the body confirms none exist, so the roundtrip is honest, not just declared. Open-items density (2 Open Questions, 1 NOTE FOR PM, 0 assumptions) is appropriately low for a solo pre-implementation PRD.

### Findings
None.

## Downstream usability — strong

This PRD does feed downstream work (Open Questions explicitly route to `bmad-ux` and `bmad-architecture`), so this dimension matters more than "standalone" would allow. The Glossary (§3) is used consistently: `Corpus`, `Entity`, `Relationship`, `Community`, `Local Search`, `Global Search`, `Retrieval Trace`, and `Replay` all appear with matching capitalization across the Vision, UJs, and FRs. FR IDs (FR-1–FR-15) are contiguous with no gaps or duplicates; UJ-1/UJ-2 and SM-1/SM-2/SM-C1 likewise. Cross-references resolve — e.g. FR-12 correctly cites "(FR-9, FR-10)," and every FR's "Realizes UJ-n" points to a UJ that exists.

### Findings
- **low** — "Document Set" (from the glossary entry "Corpus / Document Set," §3) is never used again anywhere in the document; only "Corpus" is used inline. Not drift exactly, but the dual-naming in the glossary entry is unused, so it's a candidate for trimming. Not action-critical.

## Shape fit — strong

The PRD correctly reads its own shape: single-operator/hobby project, capability-spec-with-a-light-UJ-overlay rather than a full consumer-product UJ suite. Two UJs, each with a named, contextualized protagonist ("the creator," mid-stream vs. pre-stream) — appropriate density, not padded to look thorough and not so thin that the live-demo experience (which genuinely matters here, since the product's value is presentational) goes unaddressed. JTBD-3 (future maintainer / library extraction) is deliberately not mapped to any FR, which is consistent with §0's explicit statement that implementation-level and architecture-level concerns live outside this document — this is honest scoping, not an oversight.

### Findings
None.

## Mechanical notes

- **Glossary drift:** none of concern. Terms are used consistently in case and form throughout. The unused "Document Set" alias in the Corpus glossary entry (§3) is the only loose thread — see Downstream usability finding above.
- **ID continuity:** FR-1 through FR-15 contiguous, no gaps/duplicates. UJ-1/UJ-2 and SM-1/SM-2/SM-C1 contiguous. All cross-references (FR→UJ, FR→FR) resolve to entries that exist.
- **Assumptions Index roundtrip:** clean — zero inline `[ASSUMPTION]` tags in the body, and the index (§10) correctly states none are outstanding, noting the one prior assumption (Local/Global toggle mechanism) was resolved before this draft rather than left dangling.
- **UJ protagonist naming:** both UJs name "the creator" as protagonist with situational context (mid-stream vs. pre-stream setup) carried inline — no floating UJs.
- **Required sections for stakes/type:** all present and appropriately weighted for a hobby/solo capability-spec PRD — Vision, Target User (JTBD + Non-Users + UJs), Glossary, Features/FRs, Cross-Cutting NFRs, Non-Goals, MVP Scope, Success Metrics (with counter-metric), Open Questions, Assumptions Index. Nothing over-built, nothing structurally missing.
