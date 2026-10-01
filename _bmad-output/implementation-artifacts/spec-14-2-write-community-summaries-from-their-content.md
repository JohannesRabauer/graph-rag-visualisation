---
title: 'Write Community Summaries from Their Content'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: '929b77a9a22c6712c12373d271a5251452f842bf'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Community summaries are written from member *names* only ("This community centers on A, B, C."), and Communities have no title. As a result the legend shows truncated summary text, hulls show raw `community-N` ids, and Global/DRIFT Search match against content-free summaries.

**Approach:** `DetectCommunities` hands the LLM each Community's members (with descriptions) and its internal Relationships (both endpoints are members, with descriptions, highest `weight` first, capped). The LLM returns a short title and a 2–4 sentence summary. `Community` gains a persisted `title`, which the legend, hull labels and detail panel use, falling back to today's summary-derived label when the title is empty.

## Boundaries & Constraints

**Always:**
- Summaries are still generated during detection (AD-6), one LLM call per Community, with no retries.
- The new core API is additive: the old `LlmPort.summarizeCommunity(Collection<Entity>)` and the 2-arg `Community(id, summary)` constructor keep working, so existing fakes and tests compile unchanged.
- Input caps are applied in core so every adapter gets bounded input:
  - at most 25 members, in entity order;
  - at most 30 internal Relationships, sorted by `weight` descending with ties kept in stored order.
- The OpenAI adapter additionally truncates each description in the prompt to 300 characters.
- The title is at most 6 words: the adapter trims longer LLM titles to the first 6 words. Null or blank becomes `""`.
- The offline stub (`LangChain4jLlmPort`) and the `LlmPort` default return a deterministic non-blank title and summary. The summary still names members, so the existing keyword Global Search tests keep matching.
- An LLM failure still propagates as `LlmCallFailedException`. A JSON response with a blank summary falls back to the deterministic summary.
- When the title is empty (older corpora, or a blank title), the UI uses exactly today's summary-derived label (`legendLabel`).

**Never:**
- No hierarchical summaries, no re-summarization on query, and no change to how Global/DRIFT score summaries (Epic 15).
- No new REST endpoints. Payloads change additively only (a `title` key).
- No change to Leiden grouping (Story 14.1).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Internal relationships only | community {A,B}; rels A–B, A–X (X outside) | LLM receives A–B only | none |
| Relationship cap | 40 internal rels, varied weights | 30 highest-weight rels passed, sorted by weight desc | none |
| Member cap | 30-member community | first 25 members (entity order) passed | none |
| OpenAI JSON response | `{"title":"Baker Street Detectives","summary":"…"}` | Community title + summary set, persisted, sent via SSE + graph endpoint | none |
| Long title | 9-word title | first 6 words kept | none |
| Blank/invalid fields | blank summary | deterministic fallback summary | none |
| Invalid JSON | non-JSON text | — | `LlmCallFailedException` (ingestion fails visibly, LLM message) |
| Offline stub | no API key | deterministic title + "This community centers on …" summary | none |
| Old corpus | `(:Community)` without `title` | read back with `title = ""`; UI shows summary-derived label | none |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/io/graphrag/core/domain/Community.java:6` -- `record Community(id, summary)`, whose compact constructor normalizes blanks. Make the canonical form `(id, title, summary)` with a null title becoming `""`, and add a `Community(id, summary)` constructor delegating with `""`. There are 42 call sites in 8 files; all use the 2-arg form.
- `graphrag-core/src/main/java/io/graphrag/core/domain/` -- new `record CommunitySummary(String title, String summary)`.
- `graphrag-core/src/main/java/io/graphrag/core/port/LlmPort.java:46` -- the default `summarizeCommunity(Collection<Entity>)` (deterministic "centers on"). Add a default `CommunitySummary summarizeCommunity(Collection<Entity> members, Collection<Relationship> relationships)` that wraps the old method's summary with a deterministic title. Title rule: the first two distinct member names joined with " & ", trimmed to 6 words, or "Related entities" when there are no names.
- `graphrag-core/src/main/java/io/graphrag/core/usecase/DetectCommunities.java` -- builds Communities (`new Community(id, summary)`, ~:74) and has a private `summarizeCommunity`. It has an inline fallback for `llmPort == null`, which must produce the same deterministic title rule. It reads `graphStorePort.relationships(corpusId)` once, filters by both endpoint identities (`Entity.identityOf`) in the member set, and applies the caps.
- `graphrag-adapter-langchain4j/src/main/java/com/graphraglens/adapter/langchain4j/OpenAiLlmPort.java:169-198` -- current text-model summary. Override the new 2-arg method using `jsonChatModel` with a prompt containing the word "json" (required by :60-62). Reuse the extraction pattern:
  - `ChatRequest` with `maxOutputTokens` (:127-134);
  - a package-private prompt builder like `extractionPrompt` (:147);
  - `stripMarkdownFences` and `objectMapper.readTree` parsing (:200-257).
- `graphrag-adapter-langchain4j/src/main/java/com/graphraglens/adapter/langchain4j/LangChain4jLlmPort.java:27-45` -- the offline stub. It overrides the old method; the new default covers the title. Override the new method only if needed for determinism.
- `graphrag-adapter-langchain4j/src/test/.../OpenAiLlmPortTest.java:127` -- `FakeChatModel`, reusable for the summarize tests.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jGraphStoreAdapter.java` -- `persistCommunities` (~:265-285, add `c.title = $title`) and `communities(String)` (~:412-426, `coalesce(c.title, '')`).
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` `communityEventPayload` (~:601-606) -- add `"title"`. It is used by the SSE `community-detected` event and by `GET /api/corpora/{id}/graph` `communities`.
- `graphrag-web/src/main/resources/static/js/graph-canvas.js`:
  - `legendLabel` (:136-145) is the fallback.
  - `addCommunity(communityId, summary, memberEntityIdentities)` (:924-961) gains an optional 4th `title` argument. The legend `name` is the title when it is non-blank, otherwise `legendLabel(...)`. The hull node `label` uses the same name instead of the raw id. `GraphLayoutCommunitySpacingUiTest:186` calls it with 3 args, which must keep working.
- `graphrag-web/src/main/resources/static/js/upload.js:1659,1964` -- the two `addCommunity` callers. Pass `data.title`. The detail panel (`openCommunityDetailPanel` :732-760) already shows `community.name` plus the full `community.summary`.
- `graphrag-web/src/main/resources/static/help/communities.html` -- mention titles and grounded summaries.

## Tasks & Acceptance

**Execution:**
- `graphrag-core/.../domain/Community.java`, `CommunitySummary.java` -- title field and the value record.
- `graphrag-core/.../port/LlmPort.java` -- additive 2-arg `summarizeCommunity` default with the deterministic title.
- `graphrag-core/.../usecase/DetectCommunities.java` -- gather internal relationships, cap, call the new method, set the title.
- `graphrag-core/src/test/.../DetectCommunitiesTest.java` -- tests for internal-only filtering, the relationship cap and order, the member cap, a title set from the port, and the deterministic default title.
- `graphrag-adapter-langchain4j/.../OpenAiLlmPort.java` + test -- JSON prompt (descriptions truncated to 300 chars, numbered members and relationships), parsing, the 6-word trim, blank fallback, and invalid JSON throwing `LlmCallFailedException`.
- `graphrag-adapter-langchain4j/.../LangChain4jLlmPortTest.java` -- the stub returns the same deterministic title and summary twice.
- `graphrag-adapter-neo4j/.../Neo4jGraphStoreAdapter.java` + test -- title round-trip, and a title-less node read as `""`.
- `graphrag-web/.../CorpusController.java` + `CorpusControllerTest` -- `title` in the SSE and graph-endpoint payloads.
- `graphrag-web/.../static/js/graph-canvas.js`, `upload.js` -- title in the legend, hull labels and detail-panel name, with fallback.
- `graphrag-web/src/test/.../` new `CommunityTitleUiTest` (Playwright, following existing `*UiTest` patterns) -- asserts the legend chip and hull label show the title, the detail panel shows the full summary, and a community with an empty title shows the summary-derived label.
- `graphrag-web/.../static/help/communities.html`, `graphrag-core/CHANGELOG.md` -- docs.

**Acceptance Criteria:**
- Given a live corpus, when detection runs, then each `summarizeCommunity` call receives only the Community's members (with descriptions) and its internal Relationships (with descriptions), capped highest-weight first.
- Given a Community with a title, when the main screen shows it, then the legend chip text and the hull label show the title, and clicking the hull opens a detail panel with the full summary.
- Given an offline corpus, when it builds, then every Community has a deterministic non-blank title, and all existing core, web and UI tests still pass.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 30 findings — high 0, medium 6, low 18, false 6, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) trimTitle splits on `"\s+"`, which Java reads as a space escape, so tabs and newlines break the 6-word cap — confirmed at CommunitySummary.java:29; changed to the escaped regex (`"\\s+"` in source) with a tab/newline test
  - `[low]` `[reject]` (blind) One failed summary aborts the whole detection run — by design: FR-5 says visible failure, no retries, and the spec keeps `LlmCallFailedException` propagating
  - `[false]` `[reject]` (blind) A blank LLM title is not replaced by the deterministic title — the spec and AC state that an empty title falls back to the summary-derived label in the UI; the behaviour matches
  - `[low]` `[reject]` (blind) A blank `CommunitySummary` from a custom port is stored as "Community cluster" — only third-party ports could do this; the in-repo ports never return a blank summary
  - `[medium]` `[patch]` (blind) Relationships are filtered against all members while only 25 are sent — confirmed at DetectCommunities.java:138; now filters on cappedMembers, and the test asserts endpoints are passed members
  - `[low]` `[reject]` (blind) The member cap keeps entity order, not hubs — a spec decision (first 25 in entity order); ranking adds complexity
  - `[low]` `[reject]` (blind) Relationships are loaded even without an LLM port — one read per run in offline mode; negligible
  - `[low]` `[patch]` (blind) The record shape change is not purely additive — the CHANGELOG now says the canonical components changed and record patterns break
  - `[low]` `[reject]` (blind) The error message embeds the raw LLM response — goes to logs only; the user sees the fixed SSE message
  - `[low]` `[reject]` (blind) Missing OpenAI tests (LENGTH, non-object JSON, null response) — LENGTH and truncated output fail parsing anyway; low value
  - `[low]` `[patch]` (blind) Re-adding a hull leaves stale summary data — the summary data is now refreshed alongside the label
  - `[false]` `[reject]` (blind) Neo4j: summary not coalesced, and the 2-arg ctor wipes the title — every Community node gets a summary (persist plus membership ON CREATE); persistCommunities is only fed by DetectCommunities with titles
  - `[low]` `[reject]` (blind) Prompt gaps: ellipsis, language, injection, quotes — cosmetic or speculative; uploads are the presenter's own corpus
  - `[low]` `[reject]` (blind) Global/DRIFT do not use the title — Epic 15 scope (semantic matching and answers)
  - `[medium]` `[patch]` (edge) Title split regex — same root cause as row 1
  - `[medium]` `[patch]` (edge) Relationships filtered against uncapped members — same root cause as row 5
  - `[false]` `[reject]` (edge) Blank summary from the port with a blank title — see row 4; the in-repo ports never return blanks
  - `[false]` `[reject]` (edge) Blank LLM title persisted empty — see row 3; matches spec and AC
  - `[false]` `[reject]` (edge) All-null members trigger a paid call — DetectCommunities filters null entities before grouping (14.1 orderedGroups)
  - `[low]` `[reject]` (edge) Surrogate pair cut at 300 chars — rare; harmless to the model
  - `[low]` `[patch]` (edge) A null port result makes a second, names-only LLM call — now uses the deterministic fallback, with a null-port test
  - `[low]` `[patch]` (edge) Hull summary data is stale on re-add — same root cause as row 11
  - `[low]` `[reject]` (edge) The 2-4 sentence count is not enforced — prompt-level contract; enforcing it would require rewriting model output
  - `[medium]` `[patch]` (edge) Claim: relationship endpoints are among the passed members — same root cause as row 5
  - `[medium]` `[patch]` (verification-gap) No test pins upload.js forwarding the title (SSE and restore) — the UI test now compares labels with the graph-endpoint titles, live and after a reload
  - `[low]` `[patch]` (verification-gap) No test for a null port result — added with row 21
  - `[low]` `[reject]` (verification-gap other) LENGTH finish reason untested — see row 10
  - `[low]` `[reject]` (verification-gap other) Hull relabel branch untested — the fix in row 11 is a one-line data refresh; covered indirectly by the restore assertion
  - `[low]` `[reject]` (intent) Search does not use titles and no real-LLM test exists — Epic 15 scope; there is no API key in CI
  - `[false]` `[reject]` (intent) Entity detail panel should show its Community summary — the AC names the Entity/Community detail panel, which shows the full summary in Community mode; no second reading was required by the spec

## Verification

**Commands:**
- `mvn -B -q -pl graphrag-core,graphrag-adapter-langchain4j test` -- expected: all pass
- `mvn -B -pl graphrag-adapter-neo4j -am test -Dapi.version=1.44` -- expected: BUILD SUCCESS
- `mvn -B -pl graphrag-web -am test -Dapi.version=1.44 -Dtest='CorpusControllerTest,CommunityTitleUiTest,GraphLayoutCommunitySpacingUiTest,MainScreenDetailPanelUiTest' -Dsurefire.failIfNoSpecifiedTests=false` -- expected: all pass except a pre-existing baseline failure in `MainScreenDetailPanelUiTest` (Sherlock "No relationships"), which must not get worse

## Auto Run Result

Status: done

**Summary:** Community summaries are now written from content. `DetectCommunities` passes each Community's members (first 25, with descriptions) and its internal Relationships (both endpoints among those members, top 30 by weight) to a new additive `LlmPort.summarizeCommunity(members, relationships)`, which returns a `CommunitySummary(title, summary)`. `Community` gains a persisted `title`. The legend, hull labels and detail panel show the title, and fall back to the summary-derived label when it is empty. The offline stub and default produce a deterministic "A & B" title.

**Files changed:**
- `graphrag-core/.../domain/Community.java`, `CommunitySummary.java` -- title field (2-arg ctor kept) and value record with title trimming and deterministic-title helpers.
- `graphrag-core/.../port/LlmPort.java` -- additive 2-arg `summarizeCommunity` default.
- `graphrag-core/.../usecase/DetectCommunities.java` -- internal-relationship filtering, caps, title, deterministic null fallback.
- `graphrag-adapter-langchain4j/.../OpenAiLlmPort.java` -- JSON-mode summary call with a numbered prompt, descriptions truncated to 300 chars, and the 6-word title.
- `graphrag-adapter-neo4j/.../Neo4jGraphStoreAdapter.java` -- persists and reads `c.title`, with `coalesce` for old nodes.
- `graphrag-web/.../CorpusController.java` -- `title` in the SSE and graph-endpoint payloads.
- `graphrag-web/.../static/js/graph-canvas.js`, `upload.js` -- title in legend, hull and detail panel, with fallback.
- `graphrag-web/.../static/help/communities.html` -- titles, grounded summaries, and the Leiden wording (fixing stale connected-components text from 14.1).
- Tests: `DetectCommunitiesTest`, `CommunitySummary` trim test, `OpenAiLlmPortTest`, `LangChain4jLlmPortTest`, `Neo4jGraphStoreAdapterTest`, `CorpusControllerTest`, new `CommunityTitleUiTest`.
- `graphrag-core/CHANGELOG.md` -- documents the changes and the breaking change for record patterns.

**Review findings:** 30 findings. 12 rows were patched, covering 6 distinct fixes:
- whitespace regex;
- relationship filter on the capped members;
- deterministic null fallback (no second LLM call);
- hull summary refresh;
- UI test pinning upload.js title forwarding, live and after reload;
- CHANGELOG record-shape note.

0 deferred. 18 rejected; the reasons are in the Review Triage Log.

**Follow-up review recommendation:** true. 3 medium entries were patched (regex, relationship filter, UI wiring test) plus low ones. Named unverified risk: the live OpenAI JSON summary call is only tested with a fake ChatModel and has never run against the real API.

**Verification:**
- `env -u OPENAI_API_KEY mvn -B install -Dapi.version=1.44`: BUILD SUCCESS. Totals: core 113, neo4j 58, langchain4j 23, parsing 10, web 171 (all UI tests included), 0 failures.

**Residual risks:**
- With a real key, a malformed summary response now fails ingestion visibly; the old plain-text call could not fail on format.
- Running tests locally with `OPENAI_API_KEY` set makes the Spring/UI tests hit the real LLM and flake.
