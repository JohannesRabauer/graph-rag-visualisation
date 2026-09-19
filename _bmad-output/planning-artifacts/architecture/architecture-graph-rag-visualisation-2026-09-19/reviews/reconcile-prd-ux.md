---
title: Reconciliation — PRD + EXPERIENCE.md vs. Architecture Spine
type: review
scope: architecture-graph-rag-visualisation-2026-09-19
inputs:
  - _bmad-output/planning-artifacts/prds/prd-graph-rag-visualisation-2026-09-19/prd.md
  - _bmad-output/planning-artifacts/ux-designs/ux-graph-rag-visualisation-2026-09-19/EXPERIENCE.md
  - _bmad-output/planning-artifacts/architecture/architecture-graph-rag-visualisation-2026-09-19/ARCHITECTURE-SPINE.md
created: 2026-09-19
status: findings-only (spine not modified)
---

# Reconciliation Findings — PRD / EXPERIENCE.md vs. ARCHITECTURE-SPINE.md

## 1. FR coverage of the Capability → Architecture Map

The map's row ranges (FR-1–FR-3, FR-4–FR-5, FR-6–FR-7, FR-8–FR-11, FR-12–FR-13, FR-14–FR-15, FR-16–FR-17) do span all 17 FRs with no numeric gap — structurally, every FR has a row.

However, having a row is not the same as having the FR's *testable behavior* actually constrained by the ADs that row cites. One row fails this test:

- **Query Interface (FR-8–FR-11)** is governed by AD-1 (core has zero framework deps), AD-3 (LLM access is port-only), AD-6 (detection/summary always run), AD-11 (Communities are first-class nodes). None of these say anything about the actual request/response contract for submitting a query (FR-8), returning an answer (FR-11), or signaling the FR-9/FR-10 "no answer found" consequence. Compare this to Retrieval Trace & Playback (FR-12–FR-13), which is anchored by AD-5's concrete contract (`GET /api/traces/{traceId}`, in-memory store keyed by UUID) — Query Interface has no equivalent AD defining its own endpoint/transport/shape. Two implementers could independently build: one a synchronous `POST /api/query` returning `{answer, traceId}`, another something SSE-based reusing AD-12's stream — both consistent with every AD currently cited, which means the row's "Governed by" column is misleading about how much it actually pins down. **This is the root cause of gaps 2 and 3 below.**

## 2. Cross-Cutting NFRs — do they each have a home in the spine?

| NFR (PRD §5) | Home in spine? | Assessment |
| --- | --- | --- |
| UI tone (modern/minimalist, browser-only) | Not addressed, and not listed in the spine's own **Deferred** section either. | The PRD itself defers this to the `bmad-ux` pass, and EXPERIENCE.md/DESIGN.md do own it — so functionally nothing falls through. But the spine text gives no signal that this omission is deliberate; a reader of the spine alone (without cross-referencing the PRD) can't tell "architecture doesn't need to say anything about this" from "this was missed." **Minor gap: not a behavioral risk, but a documentation-completeness one** — worth a one-line Deferred-section entry ("UI tone/visual direction — owned by DESIGN.md/EXPERIENCE.md, no architectural constraint needed") so the spine is self-contained on the question. |
| Reliability (no retry/fallback; failures always surface) | AD-5 note ("no fallback/safety-net ethos"), Consistency Conventions "State & cross-cutting" row ("LLM-call failures (extraction or generation) surface as a visible error and are logged; never retried automatically"), AD-12's `error` SSE event. | Mostly has a home — explicitly covers both extraction and *generation* failures in prose. But the mechanism for surfacing a **generation**-time failure has no defined transport, because (per §1 above) the query contract itself is undefined. AD-12's `error` event is scoped to the per-Corpus ingestion/progress stream, not to query answering. So the *principle* has a home; the *transport* for one of its two named cases (generation failures) does not. See gap 3 below. |
| Single-user, local-only | AD-8 (exactly two containers, no auth container), and explicitly listed in **Deferred** ("Authentication/authorization," "Multi-tenancy / hosted deployment"). | Has a clear, explicit home. No gap. |
| Provider flexibility (LLM swap-ability) | AD-3 (LLM access is port-only; only `graphrag-adapter-langchain4j` may import LangChain4j/OpenAI types). | Has a clear, explicit home. No gap. |

## 3. EXPERIENCE.md State Patterns vs. AD-7/AD-12's SSE contract

AD-12 fixes a single multiplexed stream, `GET /api/corpora/{corpusId}/progress`, with named events (example list: `entity-extracted`, `community-detected`, `ingestion-complete`, `error`). Checking each EXPERIENCE.md state against it:

| EXPERIENCE.md state | Transport implied | Covered by AD-7/AD-12? |
| --- | --- | --- |
| Idle / empty | None needed (no corpus yet) | N/A — fine |
| Ingestion in progress | SSE `entity-extracted` events on the per-corpus stream | Yes |
| Community-detection in progress | SSE `community-detected` events on the same stream | Yes |
| Upload rejected | Synchronous REST response to the upload call | Fine — not a progress event, doesn't need AD-12 |
| **LLM-call failure** — EXPERIENCE.md explicitly generalizes FR-5's ingestion-scoped failure state to *also* cover a failure "during answer generation, since it's the same class of live LLM call" | Ingestion-time: AD-12's `error` event, on the per-corpus stream — **covered**. Generation-time (mid-query): **no defined transport** — the query/answer path has no AD (see §1), so there is no event or response shape that a frontend could reliably listen for to render this half of the state. | **Partially missing.** The state EXPERIENCE.md describes as one unified "LLM-call failure" treatment actually needs two different transports in this architecture, and only one of them is specified. |
| **No-answer-found** (FR-9/FR-10 consequence) | Belongs to the query response, not the progress stream | **Missing entirely** — not addressable via AD-12 (wrong stream, wrong scope) and there is no query-response contract elsewhere to carry it either. No AD says whether this is a distinct field, a special HTTP status, or reuses the generic `{"error": ...}` shape meant for real failures (risking the two being conflated in an implementation). |
| Populated / answered (FR-11) | Query response | Same gap as above — the shape that carries a successful answer + `traceId` is only implied by AD-5 ("traceId ... generated when the answer is produced and returned alongside it"), never explicitly specified as a full response contract. |
| Explore page states (FR-16/FR-17) | Plain REST read of already-persisted graph — not a progress concern | Fine — doesn't need AD-12; a request/response detail low-stakes enough to leave to implementation, unlike the query-answer path which carries three distinct outcome states (answer / no-answer / failure) that a frontend must be able to tell apart. |

**Net finding:** AD-7/AD-12 fully supports the two ingestion-side states (ingestion-in-progress, community-detection-in-progress) and the ingestion-side half of LLM-call-failure. It does **not** reach the three query/answer-side states (no-answer-found, populated/answered, and the generation half of LLM-call-failure), because no AD defines the query request/response contract at all. These three states are real, PRD-mandated, testable behaviors (FR-9/FR-10 consequences, FR-11) with no transport/shape defined anywhere in the spine — two implementers could genuinely diverge here.

## 4. Secondary observation (not explicitly asked for, flagged because it surfaced during the check)

EXPERIENCE.md's "Ingestion in progress" row states a query submitted mid-ingestion "proceeds against whatever graph state exists at that moment, rather than queuing or being rejected." No AD addresses concurrent Neo4j read/write safety between the in-flight `IngestCorpus`/`DetectCommunities` writes and a simultaneous `AnswerLocalSearch`/`AnswerGlobalSearch` read (e.g., partial-graph query semantics, GDS Leiden running concurrently with Cypher writes). This is adjacent to, but distinct from, the query-contract gap above and could independently cause divergent implementations (one adds locking/isolation, another doesn't).

## Summary of gaps

1. **No AD defines the query/answer request-response contract** (FR-8–FR-11) — the Capability Map cites AD-1/AD-3/AD-6/AD-11 for this row, but none constrain the actual endpoint, transport, or payload shape, unlike the concrete contract AD-5 gives Retrieval Trace fetch.
2. **"No answer found" (FR-9/FR-10) has no defined wire representation** anywhere in the spine — not a distinct field, status, or event; risks being conflated with the generic LLM-failure error shape.
3. **AD-12's SSE event contract does not reach answer-generation failures** — it fully covers ingestion/extraction failures (`error` event on the per-corpus stream) but generation-time LLM failures (the other half of EXPERIENCE.md's unified "LLM-call failure" state) have no defined transport, because the query path itself is unconstrained.
4. **UI tone NFR is absent from the spine's own Deferred section** — functionally fine (PRD routes it to UX, which owns it), but the spine doesn't say so itself, making the omission look accidental rather than deliberate to a reader of the spine alone.
5. **(Secondary) No AD addresses concurrency between in-flight ingestion writes and mid-ingestion queries**, despite EXPERIENCE.md explicitly permitting queries to run against a partially-built graph.
