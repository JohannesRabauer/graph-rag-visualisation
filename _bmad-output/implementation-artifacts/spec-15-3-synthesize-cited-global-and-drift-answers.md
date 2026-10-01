---
title: 'Synthesize Cited Global and DRIFT Answers'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: 'ac3b2b16e2cc49bd091211b308a40f522fb5b239'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Global and DRIFT Search still answer with fixed templates: "Across the corpus, the strongest signal is that …" and "DRIFT matched a relevant Community and then grounded the answer locally: …". Only Local (Story 15.2) is LLM-written and cited, so the three modes are not comparable.

**Approach:** Reuse Story 15.2's machinery: `LlmPort.synthesizesAnswers()`/`synthesizeAnswer`, `ContextItem`, `CitationResolver`, `TEXT_UNIT` steps, the `citations` response array and not-in-context → `noAnswer`.
- **Global** context: the top Community summaries, plus each Community's highest-weight member Text Units.
- **DRIFT:** each sub-question branch gathers Local-style context under its `SUB_QUESTION_SPAWNED` step. One final synthesis runs over the union of branch contexts and is recorded as the drift tree's `SYNTHESIS` step, after all branches.

Offline, both modes keep today's templated answers and traces exactly.

## Boundaries & Constraints

**Always:**
- **Offline unchanged.** With a null or non-synthesizing `LlmPort`, Global and DRIFT run today's exact code paths, including Story 15.1's semantic seed path when an embedding port is semantic. `citations` is `[]` on success. All existing tests stay untouched and green.
- **Global, synthesizing path:**
  - Communities: the top 3 from Story 15.1's semantic matching. Otherwise the top 3 by `KeywordMatcher` score (score > 0, score descending, id tiebreak). With no candidate, the existing no-match or `noCommunitiesYet` behaviour applies.
  - Each Community becomes a `COMMUNITY` step and a context item (title + summary).
  - Directly after each Community, up to 2 member Text Units become `TEXT_UNIT` steps and context items. They are ranked by: the sum of the weights of Relationships with both endpoints among the Community's members that cite the unit, plus 1 per member Entity citing it. Ties keep first-seen order.
  - A Text Unit already added for an earlier Community is not repeated.
- **DRIFT, synthesizing path:**
  - Candidates and steps are as today: 15.1's top 3, or the keyword candidates.
  - Sub-questions still come from `LlmPort.deriveDriftSubQuestions`.
  - For each sub-question, after its `SUB_QUESTION_SPAWNED` step, the branch gathers Local-style context with 15.2's assembly rules and caps (seeds, one-hop Relationships, cited Text Units), recording its steps there. The branch makes no LLM call.
  - The final synthesis runs once over the union of all branch context items, plus the candidate Community summaries. Items are de-duplicated by identity (entity identity, edge id, Text Unit id) and numbered in first-seen order.
  - It is recorded as the single `SYNTHESIS` step (identifier = first candidate's id, label = the answer text) after the last branch.
  - If no branch found any context, today's "did not find a graph-grounded local match yet" outcome stays.
- **Both modes:**
  - Citations are resolved with `CitationResolver`.
  - Success responses gain `citations`.
  - Not-in-context (normalized sentinel, blank, or blank after resolution) maps to the existing `noAnswer` shape, keeping the steps.
  - An LLM failure surfaces as the existing 502 `{error}` shape. No retries.
- **Shared assembly:** Local-style context assembly is shared with `AnswerLocalSearch` (an extracted package-private assembler), not copied.
- `drift-tree.js` and replay handle `TEXT_UNIT` steps inside branches without errors. They render as branch children or are skipped visually; full passage UI is Story 15.4.

**Never:**
- No change to Local's behaviour, the Vector Baseline, `deriveDriftSubQuestions` prompts, or the citation UI (Story 15.4).
- No per-branch LLM answers.
- No retries.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Global cited | 3 communities, LLM cites a member TU | answer with `[1]`; `citations[0]` = that TU; COMMUNITY+TEXT_UNIT steps in order | none |
| Global TU caps | community whose members cite 6 TUs | ≤2 TEXT_UNIT steps after that COMMUNITY, highest weight first; no TU repeated across communities | none |
| Global not in context | LLM NOT_IN_CONTEXT | Global noAnswer shape; steps kept | none |
| DRIFT branches | 2 sub-questions | per branch: SUB_QUESTION_SPAWNED then its ENTITY/RELATIONSHIP/TEXT_UNIT steps; one SYNTHESIS step last | none |
| DRIFT union dedupe | both branches reach the same TU | context lists it once; one citation | none |
| DRIFT no branch context | branches find no seeds | today's no-local-match outcome, no LLM synthesis call | none |
| Citations ⇄ trace | any synthesized Global/DRIFT answer | every `citations[].textUnitId` is a TEXT_UNIT step of that trace | none |
| Offline | non-synthesizing / null port | today's templates and traces; `citations` = [] | none |
| LLM failure | synthesize throws | — | 502 `{error}` |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/io/graphrag/core/usecase/AnswerLocalSearch.java` -- Story 15.2's synthesizing path: context assembly with the 3/10/5 caps, `loadTextUnit`, `excerpt`, `CitationResolver` use, and the not-in-context normalization. Extract the context assembly (seeds → relationships → text units, recording steps and `ContextItem`s) into a package-private `LocalContextAssembler`, so DRIFT branches can reuse it. Local behaviour must stay identical.
- `graphrag-core/.../usecase/CitationResolver.java` -- reuse as is.
- `graphrag-core/.../usecase/AnswerGlobalSearch.java` -- has the semantic path (15.1, `similarCommunities`), keyword selection, and the templates. Add an `LlmPort` constructor argument while keeping the existing constructors. `GlobalSearchAnswer` (`noAnswer, answer, reason, steps`) gains `citations`, with the old constructor and factories kept.
- `graphrag-core/.../usecase/AnswerDriftSearch.java` -- candidates, the `deriveDriftSubQuestions` call, `SUB_QUESTION_SPAWNED` steps, the nested `AnswerLocalSearch`, and today's SYNTHESIS template. `DriftSearchAnswer` gains `citations` in the same way.
- `graphrag-core/.../port/GraphStorePort.java` -- `communityMemberships(corpusId)`, `entities`, `relationships`, `textUnit`. Use these for Global member Text Units.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- the GLOBAL and DRIFT query branches pass `llmPort` (already available) and add `citations` on success. The 502 `LlmCallFailedException` handler from 15.2 already exists.
- `graphrag-web/src/main/resources/static/js/drift-tree.js` -- `analyzeSteps` (~L43): `SUB_QUESTION_SPAWNED` opens a branch and the first `SYNTHESIS` closes them all. `highlightStep` is at ~L187. `TEXT_UNIT` inside a branch must not break it.
- `graphrag-web/src/main/resources/static/js/replay.js` -- `updatePhaseAndHint` (~L375-420). The synthesis hint text ("The first branch whose local search followed a relationship becomes the answer") must describe the synthesized path when the trace contains `TEXT_UNIT` steps, and stay as today otherwise.
- `graphrag-web/src/main/resources/static/js/help.js` -- `renderGlobal` (~L368-413) infers the best community from the answer text. It must not throw for synthesized answers; falling back to the first COMMUNITY step is fine.
- Tests: `AnswerGlobalSearchTest`, `AnswerDriftSearchTest` (unchanged), `CorpusControllerGlobalSearchTest`, `CorpusControllerDriftSearchTest`, `CorpusControllerTest`, `DriftTreeReplayUiTest`.

## Tasks & Acceptance

**Execution:**
- `LocalContextAssembler.java` (new), `AnswerLocalSearch.java` -- extract the assembly with no behaviour change; the existing 15.2 tests prove it.
- `GlobalSearchAnswer.java`, `AnswerGlobalSearch.java` -- the synthesizing path.
- `DriftSearchAnswer.java`, `AnswerDriftSearch.java` -- the synthesizing path.
- `AnswerGlobalSearchSynthesisTest`, `AnswerDriftSearchSynthesisTest` -- cover every matrix row with a recording fake synthesizing `LlmPort`, including a non-synthesizing port giving output identical to today's constructors.
- `CorpusController.java` + tests -- GLOBAL and DRIFT `citations` (`[]` offline). With a fake synthesizing port, the cited ids are TEXT_UNIT steps in `GET /api/traces/{id}` for both modes. Check the noAnswer shape.
- `drift-tree.js`, `replay.js`, `help.js` -- tolerate and describe synthesized traces.
- `graphrag-web/.../static/help/global-search.html`, `drift-search.html`, `graphrag-core/CHANGELOG.md` -- docs.

**Acceptance Criteria:**
- Given a live corpus with a synthesizing LLM, when Global and DRIFT questions are asked, then both return LLM-written answers whose `[n]` markers index into `citations`, and every cited Text Unit is a `TEXT_UNIT` step of that trace.
- Given a DRIFT trace from the synthesizing path, when it is replayed in the drift tree, then the Synthesis node is reached only after all branches, as today, and the replay throws no errors.
- Given offline mode, when Global and DRIFT questions are asked, then answers and traces equal today's, and all existing tests pass.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 28 findings — high 0, medium 6, low 20, false 2, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) mode-chooser.html contradicts itself about LLM use — bullets and paragraph qualified as offline behaviour; with a key, answers are LLM-written and seeds matched by meaning
  - `[low]` `[patch]` (blind) local-search.html is half updated — last bullet updated
  - `[medium]` `[patch]` (blind) The DRIFT synthesis context is unbounded (tied candidates and branches) — synthesizing path caps candidates at 3; test with 5 tied communities
  - `[low]` `[patch]` (blind) The prompt citation rule omits Community summaries — one rule: only source passages are citable
  - `[low]` `[reject]` (blind) UI detects synthesis by TEXT_UNIT presence — a synthesized answer without any loaded passage is rare; an explicit flag would add API surface
  - `[medium]` `[patch]` (blind) help.js Global: text-quote inference overrides the synthesized label — synthesized handling takes precedence; UI test added
  - `[low]` `[reject]` (blind) Global keyword candidates ignore titles — spec: keyword score over summaries as today; titles are LLM-derived from the same content
  - `[low]` `[patch]` (blind) CHANGELOG files a breaking change under Added — moved to Changed as a breaking note
  - `[low]` `[reject]` (blind) Untested Global branches: missing TU, no memberships — covered by shared assembler and skip semantics; low value
  - `[low]` `[reject]` (blind) Full graph re-read per DRIFT branch — at most 3 branches after the cap; demo-scale corpora
  - `[low]` `[reject]` (blind) DRIFT gives up when no branch has a seed — the spec keeps today's no-local-match outcome deliberately
  - `[low]` `[reject]` (blind) Coupled constants SEMANTIC vs SYNTHESIS count — both are 3; no diverging caller today
  - `[low]` `[reject]` (blind) The DRIFT trace repeats passages across branches — spec: each branch records its own steps under its SUB_QUESTION_SPAWNED
  - `[medium]` `[patch]` (edge) DRIFT keyword candidates are unbounded — same root cause as row 3
  - `[medium]` `[patch]` (edge) help.js note mismatch when a non-first summary is quoted — same root cause as row 6
  - `[low]` `[patch]` (edge) help.js claims an answer on a not-in-context result — no-answer handling added with row 6
  - `[low]` `[reject]` (edge) Synthesized trace without TEXT_UNIT is described as templated — same as row 5
  - `[low]` `[patch]` (edge) The drift-tree tooltip claims an answer on not-in-context — tooltip for synthesisIndex === -1
  - `[low]` `[reject]` (edge) Global with nothing citable still synthesizes — answer stays grounded in the community summaries; citations []
  - `[low]` `[reject]` (edge) Redundant lookups of missing TUs — negligible
  - `[low]` `[patch]` (edge claim) mode-chooser contradiction — same root cause as row 1
  - `[low]` `[patch]` (edge claim) local-search bullet — same root cause as row 2
  - `[medium]` `[patch]` (verification-gap) The synthesized Global help live view is untested — HelpPaneUiTest case added
  - `[medium]` `[patch]` (verification-gap) Synthesizing DRIFT with semantic seeding is untested — core test added
  - `[false]` `[reject]` (intent) The union also includes the candidate summaries — the spec explicitly includes them; summaries are the DRIFT entry context
  - `[low]` `[reject]` (intent) No SYNTHESIS step on a DRIFT not-in-context trace — there is no answer to label; the drift tree is handled via the tooltip (row 18)
  - `[false]` `[reject]` (intent) noAnswer omits citations — AD-29: noAnswer and error shapes are unchanged
  - `[low]` `[reject]` (intent) The UI test uses a rewritten trace, not a real LLM run — no key in CI; core and controller tests cover the real code path

## Verification

Run every Maven command with `OPENAI_API_KEY` unset (as in CI). `-Dapi.version=1.44` is needed for Testcontainers with the local Docker.

**Commands:**
- `env -u OPENAI_API_KEY mvn -B -q -pl graphrag-core,graphrag-adapter-langchain4j test` -- expected: all pass
- `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44` -- expected: BUILD SUCCESS (all modules, UI tests included)

## Auto Run Result

Status: done

**Summary:** With a synthesizing LLM, Global and DRIFT answers are written and cited the same way as Local (Story 15.2).
- **Global:** context is the top 3 Communities (semantic, else keyword). Each is followed by up to 2 member Text Units, ranked by internal Relationship weight plus citing members, with no Text Unit repeated across Communities.
- **DRIFT:** at most 3 candidates spawn branches. Each branch gathers Local-style context under its `SUB_QUESTION_SPAWNED` step through the shared `LocalContextAssembler`, with no per-branch LLM call. A single synthesis runs over the candidate summaries plus the de-duplicated union, and is recorded as the final `SYNTHESIS` step.
- **Both:** responses return `citations`, and not-in-context maps to `noAnswer`. Offline keeps today's templates and traces.

**Files changed:**
- core: new `LocalContextAssembler`; `AnswerLocalSearch` refactored onto it; `AnswerGlobalSearch`, `AnswerDriftSearch`, `GlobalSearchAnswer`, `DriftSearchAnswer`; `CHANGELOG.md`.
- adapter: `OpenAiLlmPort` — Community summary items labelled; single "only passages are citable" rule.
- web: `CorpusController` (GLOBAL/DRIFT citations); `drift-tree.js`, `replay.js`, `help.js` (synthesized traces, no-answer tooltip, Global live view); `upload.js` (`noAnswer` in the `graphrag:answer` event); help pages for global, drift, local and mode-chooser.
- tests: `AnswerGlobalSearchSynthesisTest`, `AnswerDriftSearchSynthesisTest`, `SemanticTestFixtures`, `CorpusControllerTest` (+4), `DriftTreeReplayUiTest` (+1), `HelpPaneUiTest` (+1), `OpenAiLlmPortTest`.

**Review findings:** 28 findings. 14 rows were patched, covering 7 distinct fixes:
- DRIFT candidate cap;
- one prompt citation rule;
- `help.js` synthesized precedence and no-answer handling;
- drift-tree no-answer tooltip;
- semantic DRIFT synthesis test;
- synthesized Global help UI test;
- help-text and CHANGELOG consistency.

0 deferred. 14 rejected; reasons are in the Review Triage Log.

**Follow-up review recommendation:** true, because 4 medium entries were patched. Named unverified risk: Global and DRIFT synthesis have never run against the real OpenAI model, and the UI tests use rewritten traces.

**Verification:** `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44` gave BUILD SUCCESS. Test counts: core 173, neo4j 63, langchain4j 31, parsing 10, web 187, 0 failures.

**Residual risks:**
- The UI identifies synthesized traces only by the presence of `TEXT_UNIT` steps.
- DRIFT traces repeat passages across branches; only the prompt context is de-duplicated.
