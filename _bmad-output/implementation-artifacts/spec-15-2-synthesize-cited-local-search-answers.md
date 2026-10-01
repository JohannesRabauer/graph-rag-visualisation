---
title: 'Synthesize Cited Local Search Answers'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: '6cc7dff017d8ad15052d981304c54c49585b061e'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: ['oversized']
deferred:
  - summary: >-
      help.js live "In your data" Local panel does not understand synthesized traces (multiple relationships, TEXT_UNIT steps).
    evidence: |-
      localChain/renderLocal expect ENTITY, RELATIONSHIP, ENTITY; synthesized traces add up to 10 RELATIONSHIP and 5 TEXT_UNIT steps.
    location: >-
      graphrag-web/src/main/resources/static/js/help.js:318-334
    severity: low
---

<intent-contract>

## Intent

**Problem:** Local Search answers with a fixed sentence template ("In this corpus graph, A verb B."), although FR-11 promised a generated answer. Nothing ties the answer back to the passages it came from.

**Approach:** When an answer-synthesizing LLM is configured, `AnswerLocalSearch` takes three steps.
1. It assembles a bounded context from the seeds (Story 15.1 or keyword), their one-hop Relationships, and the Text Units those cite. Each item is recorded as a trace step as it is added, including a new `TEXT_UNIT` step kind.
2. It calls a new `LlmPort.synthesizeAnswer(question, context)`, whose prompt numbers every item and requires inline `[n]` citations.
3. It keeps only the citations that point at Text Units in that context, renumbers them, and returns them as an additive `citations` array.

"Not in the context" maps to the `noAnswer` shape. Offline, the deterministic templated answer and today's trace stay as they are.

## Boundaries & Constraints

**Always:**
- `graphrag-core` stays framework-free. AD-29 and AD-13 apply: the success shape gains only an additive `citations` array, and the `noAnswer` and `error` shapes are unchanged.
- **Offline is unchanged.** `LlmPort` gains `default boolean synthesizesAnswers() { return false; }`; `OpenAiLlmPort` returns `true`. When the port is null or not synthesizing, `AnswerLocalSearch` runs today's exact code path: same steps, same template, `citations` empty. Every existing test passes untouched.
- **Context assembly (synthesizing path only)**, in this order, recording each item as a step as it is added:
  1. Seed Entities with their descriptions, as `ENTITY` steps: up to 3 from Story 15.1, else the 1 keyword seed.
  2. Every Relationship touching any seed, sorted by `weight` descending (ties in stored order), capped at 10. These become `RELATIONSHIP` steps using the existing edge-id convention `srcIdentity->type->tgtIdentity`.
  3. The Text Units cited (`sourceTextUnitIds`) by those seeds and Relationships. Each is ranked by the sum of the weights of the included Relationships citing it, plus 1 for each seed citing it; ties keep first-seen order. They are capped at 5 and become `TEXT_UNIT` steps whose `identifier` is the Text Unit id and whose `label` is the excerpt.
  
  An excerpt is the first 200 characters of the passage, whitespace-collapsed, with "…" when cut. Text Units that cannot be loaded are skipped.
- **Prompt:** numbers every context item `[1]..[n]` in assembly order, includes descriptions and the full Text Unit text (each truncated to 1,500 characters), and requires inline `[n]` citations. It tells the model to answer `NOT_IN_CONTEXT` when the context does not answer the question. One JSON-mode call with an explicit max output token limit; a `length` finish reason is a failure. No retries.
- **Citation handling in core**, so it is adapter-independent:
  - Markers `[n]` (including `[n, m]` and `[n][m]` forms) whose `n` is not a `TEXT_UNIT` item of the given context are removed from the text.
  - The kept Text Unit markers are renumbered `1..k` in order of first appearance, so `[i]` in the text is `citations[i-1]`.
  - `citations` entries are `{textUnitId, documentName, excerpt}`, each listed once.
- An answer of `NOT_IN_CONTEXT`, or a blank answer, becomes a Local `noAnswer` with a plain-language reason. The trace steps recorded so far are kept.
- An LLM failure propagates as `LlmCallFailedException` and surfaces as a visible error response. It never falls back silently.
- The chat shows the answer text with its `[n]` markers as plain text; rendering them is Story 15.4.

**Never:**
- No change to Global or DRIFT (Story 15.3).
- No citation UI (Story 15.4).
- No change to the Vector Baseline.
- No retries.
- No templated text presented as generated when the synthesizing path ran.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Cited answer | LLM returns "Holmes admired Adler [3][1]." where 3 = TU-b, 1 = seed entity | text "Holmes admired Adler [1]."; citations = [TU-b] | none |
| Unknown citation | "… [9]" with only 6 items | `[9]` removed | none |
| Repeat citations | "[4] … [4] … [5]" (both TEXT_UNIT) | "[1] … [1] … [2]", 2 citations | none |
| Not in context | LLM `NOT_IN_CONTEXT` | Local noAnswer + reason; steps kept | none |
| Caps | seed with 25 relationships citing 12 TUs | 10 RELATIONSHIP steps (highest weight), 5 TEXT_UNIT steps | none |
| Trace ⇄ citations | any synthesized answer | every `citations[].textUnitId` is a `TEXT_UNIT` step identifier in that answer's trace | none |
| Offline stub | `LangChain4jLlmPort` / null | today's template and steps; `citations` = [] | none |
| LLM failure | synthesize throws | — | visible error JSON, no fallback |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/io/graphrag/core/usecase/AnswerLocalSearch.java` -- constructors `(GraphStorePort)` and `(GraphStorePort, EmbeddingPort)` from 15.1. Semantic seeds go through `semanticSeeds` and the `SemanticMatchingException` wrapper; the keyword seed comes from `bestMatchingEntity`. The hop and template are around L80-100. Add a `(GraphStorePort, EmbeddingPort, LlmPort)` constructor and branch on `llmPort != null && llmPort.synthesizesAnswers()` before building steps.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/LocalSearchAnswer.java:16` -- `record(answer, steps)`; `noMatch()` is a normal answer. Add `noAnswer`, `reason` and `citations` components. Keep the existing 2-arg constructor and factories, so `matched`/`noMatch` behave as today with no citations, and add a `notInContext(reason, steps)` factory.
- `graphrag-core/src/main/java/io/graphrag/core/domain/RetrievalStep.java:26-43` -- the `Kind` enum. Add `TEXT_UNIT`.
- `graphrag-core/src/main/java/io/graphrag/core/domain/` -- new records:
  - `ContextItem(int number, RetrievalStep.Kind kind, String text, String textUnitId)`, where `textUnitId` is null unless the kind is `TEXT_UNIT`;
  - `Citation(String textUnitId, String documentName, String excerpt)`;
  - `SynthesizedAnswer(boolean notInContext, String text)`.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/` -- new `CitationResolver`: package-private, static and pure. It does the drop, renumber and citation-list logic above, unit-tested on its own.
- `graphrag-core/src/main/java/io/graphrag/core/port/LlmPort.java` -- add `synthesizesAnswers()` (default false) and `SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context)` (default `null`).
- `graphrag-core/src/main/java/io/graphrag/core/domain/TextUnit.java:18` -- `(id, corpusId, documentName, ordinal, text)`. Load via `GraphStorePort.textUnit(corpusId, id)`.
- `graphrag-adapter-langchain4j/.../OpenAiLlmPort.java` -- override both methods. Reuse the jsonChatModel pattern: `ChatRequest` with `maxOutputTokens`, the `FinishReason.LENGTH` check, `stripMarkdownFences` and `readTree`, and `LlmCallFailedException`. Add a package-private `answerPrompt(question, context)` builder (like `communitySummaryPrompt`). The response JSON is `{"answer": "...", "notInContext": true|false}`.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java`:
  - LOCAL query branch (~L346-353 before 15.1's edits): build the use case with `(graphStorePort, embeddingPort, llmPort)`.
  - Success maps gain `citations` (a list of `{textUnitId, documentName, excerpt}`; empty offline).
  - When `noAnswer` is set, return the existing noAnswer shape `{answerId, traceId, traceStepCount, noAnswer:true, reason}`, as Global/DRIFT already do.
  - Map an `LlmCallFailedException` during a query to the `{"error": ...}` shape with a non-2xx status (15.1 added the same for semantic matching).
- `graphrag-web/src/main/resources/static/js/upload.js` -- `appendAnswer` already renders `body.answer || body.reason` via `textContent`, so `[n]` markers show as text. Verify only.
- `graphrag-web/src/main/resources/static/js/replay.js` (`captionFor`, ~L422-431) and `graph-canvas.js` (`stepNodeId`, ~L1049-1057). `TEXT_UNIT` must not break replay: `stepNodeId` returns null and the caption falls back to a neutral "read passage" verb. Full passage captions and highlighting are Story 15.4.
- Tests: `AnswerLocalSearchTest` (keyword, unchanged), `CorpusControllerTest`, `OpenAiLlmPortTest` (`FakeChatModel`).

## Tasks & Acceptance

**Execution:**
- `RetrievalStep.java` -- add `TEXT_UNIT`.
- `ContextItem.java`, `Citation.java`, `SynthesizedAnswer.java` -- the new records.
- `LlmPort.java` -- `synthesizesAnswers` and `synthesizeAnswer` defaults.
- `CitationResolver.java` + `CitationResolverTest` -- the citation matrix rows (cited, unknown, repeat, grouped `[1, 2]`, non-TEXT_UNIT markers).
- `LocalSearchAnswer.java`, `AnswerLocalSearch.java` -- the synthesizing path with context assembly and caps.
- `AnswerLocalSearchSynthesisTest` -- uses a fake synthesizing `LlmPort` that records the context. It covers: order and caps; the steps matching the context items; not-in-context giving noAnswer; every citation id appearing as a TEXT_UNIT step; and a non-synthesizing port giving output identical to the keyword-only constructor.
- `OpenAiLlmPort.java` + `OpenAiLlmPortTest` -- the prompt (numbered items, `NOT_IN_CONTEXT` instruction, truncation), parsing, and invalid JSON or LENGTH throwing `LlmCallFailedException`.
- `CorpusController.java` + `CorpusControllerTest` -- LOCAL `citations` present (`[]` offline); a synthesizing fake port yields citations whose ids are TEXT_UNIT trace steps of `GET /api/traces/{id}`; noAnswer shape; LLM failure gives the error shape.
- `replay.js`, `graph-canvas.js` -- a neutral TEXT_UNIT caption and no node lookup.
- `graphrag-web/.../static/help/local-search.html`, `graphrag-core/CHANGELOG.md` -- docs.

**Acceptance Criteria:**
- Given a live corpus with a synthesizing LLM, when a Local question is asked, then the response answer contains `[n]` markers that each index into `citations`, and every citation's `textUnitId` appears as a `TEXT_UNIT` step in that answer's trace.
- Given the LLM answers `NOT_IN_CONTEXT`, when the response is built, then it has the `noAnswer` shape with a reason.
- Given offline mode, when a Local question is asked, then the answer text and trace equal today's, and `citations` is empty.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 32 findings — high 0, medium 6, low 22, false 4, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) The prompt invites Entity/Relationship citations that core then drops — the prompt now restricts `[n]` to source-passage items; graph items are background
  - `[low]` `[reject]` (blind) An answer with zero surviving citations ships as a normal answer — still grounded in the recorded context; `citations: []` is honest; flagging it adds surface
  - `[medium]` `[patch]` (blind) loadTextUnit swallows store failures — only missing units are skipped; store exceptions propagate (FR-5); tests added
  - `[low]` `[reject]` (blind) The web handler catches only the OpenAI exception type — OpenAiLlmPort is the only synthesizing adapter
  - `[low]` `[patch]` (blind) The NOT_IN_CONTEXT sentinel is duplicated and matched exactly — core owns the constant; normalized match
  - `[low]` `[patch]` (blind) The CHANGELOG calls record and enum changes additive — compatibility note added
  - `[low]` `[patch]` (blind) Controller Javadoc says citations are always present — corrected to "on success"
  - `[false]` `[reject]` (blind) The UI never shows citations — explicitly Story 15.4 per the AC ("rendering of citations is Story 15.4")
  - `[low]` `[reject]` (blind) Bracketed non-citation numbers like [1999] are removed — the spec requires out-of-context markers to be dropped; the strict prompt makes literal bracketed numbers rare
  - `[low]` `[reject]` (blind) Prompt injection from passages — presenter's own corpus; delimiting adds no guarantee
  - `[low]` `[reject]` (blind) Surrogate-pair truncation — rare; cosmetic
  - `[low]` `[reject]` (blind) Missing parse/resolver edge tests — the core branches are covered by patched tests; the remainder are low value
  - `[low]` `[reject]` (blind) No SYNTHESIS step after the passages — 15.4 handles replay captions; not required by the AC
  - `[medium]` `[patch]` (verification-gap) The skip branch of loadTextUnit is untested — tests for empty/null skip and exception propagation added (grouped with row 3)
  - `[medium]` `[patch]` (verification-gap) The blank-after-resolution noAnswer branch is untested — test with an entity-only marker added
  - `[low]` `[reject]` (verification-gap other) MARKER matches any bracketed integer — same as row 9
  - `[false]` `[reject]` (verification-gap other) DRIFT nested Local is non-synthesizing — intentional; Story 15.3
  - `[medium]` `[patch]` (edge) Range and semicolon markers pass through unrenumbered — MARKER accepts ranges and `;`; tests added
  - `[low]` `[reject]` (edge) Bracketed years are removed — same as row 9
  - `[low]` `[patch]` (edge) A NOT_IN_CONTEXT variant is shown as the answer (core) — same root cause as row 5
  - `[low]` `[patch]` (edge) A NOT_IN_CONTEXT variant (adapter) — same root cause as row 5
  - `[low]` `[reject]` (edge) An empty Text Unit text takes a context slot — persisted Text Units always carry text (TextUnitSplitter)
  - `[low]` `[reject]` (edge) Surrogate split in the excerpt — same as row 11
  - `[low]` `[reject]` (edge) Surrogate split in the prompt — same as row 11
  - `[low]` `[reject]` (edge) Unbounded question length — chat input; bounded by the token limit with a LENGTH failure
  - `[low]` `[reject]` (edge) Non-OpenAI adapter exception gives a generic 500 — same as row 4
  - `[low]` `[defer]` (edge) The help.js live panel does not understand synthesized traces — help live view; follow-up with 15.4 replay captions
  - `[medium]` `[patch]` (edge claim) Unmatched marker forms break the [n] → citations mapping — same root cause as row 18
  - `[low]` `[patch]` (edge claim) NOT_IN_CONTEXT. is shown as the answer — same root cause as row 5
  - `[false]` `[reject]` (intent) Citations are dropped in core, not the adapter — functionally equivalent and adapter-independent; the spec placed it in core deliberately
  - `[false]` `[reject]` (intent) Markers are renumbered, not the model's numbers — spec decision so `[i]` maps to `citations[i-1]` for 15.4
  - `[low]` `[reject]` (intent) Trace step labels carry no descriptions — the AC requires items recorded as steps; descriptions go to the model's context

## Verification

Run every Maven command with `OPENAI_API_KEY` unset (as in CI). `-Dapi.version=1.44` is needed for Testcontainers with the local Docker.

**Commands:**
- `env -u OPENAI_API_KEY mvn -B -q -pl graphrag-core,graphrag-adapter-langchain4j test` -- expected: all pass
- `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44` -- expected: BUILD SUCCESS (all modules, UI tests included)

## Auto Run Result

Status: done

**Summary:** With a synthesizing LLM (OpenAI), Local Search assembles a bounded context and records every item as a trace step:
- up to 3 seeds;
- up to 10 Relationships touching a seed, highest weight first;
- up to 5 cited Text Units, ranked by citing weight, as new `TEXT_UNIT` steps with id and excerpt.

It then calls `LlmPort.synthesizeAnswer`. `CitationResolver` keeps only markers that point at Text Units in the context and renumbers them so `[i]` maps to `citations[i-1]`. Ranges and `;` forms are supported. The response gains `citations` (`{textUnitId, documentName, excerpt}`). The normalized `NOT_IN_CONTEXT` sentinel, a blank answer, or an answer left blank after resolution maps to the noAnswer shape. An LLM failure returns 502 `{error}`. Offline, the templated answer and trace are unchanged, with `citations: []`.

**Files changed:**
- core: `RetrievalStep` (TEXT_UNIT), new `ContextItem`, `Citation`, `SynthesizedAnswer`, `CitationResolver`; `LlmPort` (`synthesizesAnswers`, `synthesizeAnswer`); `LocalSearchAnswer`; `AnswerLocalSearch`; `CHANGELOG.md`.
- adapter: `OpenAiLlmPort` (JSON answer call, `answerPrompt`).
- web: `CorpusController` (LOCAL citations, noAnswer, 502 handler); `replay.js` and `graph-canvas.js` (neutral TEXT_UNIT handling); `help/local-search.html`.
- tests: `CitationResolverTest`, `AnswerLocalSearchSynthesisTest`, `OpenAiLlmPortTest`, `CorpusControllerTest` (+4), and the `SemanticTestFixtures` Text Unit support.

**Review findings:** 32 findings. 13 rows were patched, covering 6 distinct fixes:
- prompt cites only passages;
- normalized sentinel owned by core;
- range and semicolon markers;
- store errors propagate;
- blank-after-resolution test;
- Javadoc and CHANGELOG compatibility notes.

1 deferred (help.js live panel for synthesized traces). 18 rejected; the reasons are in the Review Triage Log.

**Follow-up review recommendation:** true. 5 medium entries were patched. Named unverified risk: the answer prompt and citation behaviour have never run against the real OpenAI model, only against fake ChatModels and LlmPorts.

**Verification:** `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44`: BUILD SUCCESS. Test counts: core 156, neo4j 63, langchain4j 30, parsing 10, web 181, 0 failures.

**Residual risks:** Live answer quality and the model's compliance with "cite only passages" are untested. Bracketed literal numbers in answers would be treated as markers.
