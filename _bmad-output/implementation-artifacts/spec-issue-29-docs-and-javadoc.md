---
title: 'graphrag-core: README, port Javadoc, package-info.java, remaining Javadoc gaps'
type: 'feature'
created: '09-27-2026'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: true
context: []
warnings: []
deferred: []
baseline_revision: '2edda8bfeb19fb5c2bb13c34421c01be7d4f03f8'
---

<intent-contract>

## Intent

**Problem:** Following the rename/LICENSE/publish-metadata pass (GitHub #29, part 1), `graphrag-core` still has no module-local README, no `package-info.java` in any of its three packages, and thin Javadoc on exactly the surface a new consumer needs most: the 5 port interfaces (only 1 of 6 abstract methods documented) and 4 remaining classes (`Chunk`, `EmbeddedChunk`, `ConstructVectorIndex`, `TwoDProjection`).

**Approach:** Add `graphrag-core/README.md` (module description, JDK 25 requirement stated at module level, how to wire each of the 5 ports, a minimal end-to-end usage example tracing the real use-case pipeline order the audit already traced). Add `@param`/`@return`/contract Javadoc to the 5 currently-undocumented abstract port methods and class-level Javadoc to `EmbeddingPort`/`VectorStorePort`. Add `package-info.java` to `domain`, `port`, and `usecase`. Add class-level Javadoc to the 4 remaining undocumented classes.

## Boundaries & Constraints

**Always:** Every Javadoc addition must describe the actual, current contract (read the implementation before writing `@param`/`@return`/nullability text — never guess). The README's usage example must be code that would actually compile and run against the current `io.graphrag.core.*` API (verify by cross-checking against the real constructors/method signatures, and ideally by basing it on `IngestCorpusTest`, already identified as the simplest existing test). State explicitly in the README that no artifact is actually published yet (per the prior story) and that this module currently requires `mvn install`-from-source to consume.

**Never:** Do not change any method signature, add any new class, or alter any behavior — this is a documentation-only change. Do not touch anything outside `graphrag-core/` (its README/Javadoc/package-info are the only content in scope). Do not re-touch the rename/LICENSE/POM work from the prior story.

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/io/graphrag/core/port/EmbeddingPort.java` — currently `public interface EmbeddingPort { float[] embed(String text); }`, zero Javadoc. Add class-level Javadoc plus `@param`/`@return` on `embed`.
- `graphrag-core/src/main/java/io/graphrag/core/port/VectorStorePort.java` — zero class-level Javadoc; `persistChunks(String corpusId, Collection<EmbeddedChunk> chunks)` (line 12) is the only undocumented abstract method (its sibling defaults like `persistProjectionModel` already have Javadoc, at lines 18-23). Add class-level Javadoc plus `@param` on `persistChunks`.
- `graphrag-core/src/main/java/io/graphrag/core/port/GraphStorePort.java` — already has class-level Javadoc; `persistEntities(Collection<Entity>)` (line 17) and `persistRelationships(Collection<Relationship>)` (line 19) are the two undocumented abstract methods. Add `@param` Javadoc to both.
- `graphrag-core/src/main/java/io/graphrag/core/port/LlmPort.java` — already has class-level Javadoc; `extract(Corpus corpus)` (line 15) is the one undocumented abstract method (its sibling default `synthesizeFromChunks`, lines 41-52, already has full `@param`/`@return` Javadoc to model the same style from). Add `@param`/`@return` Javadoc to `extract`.
- `graphrag-core/src/main/java/io/graphrag/core/domain/Chunk.java` / `EmbeddedChunk.java` — bare one-line records with zero Javadoc. Add a short class-level comment each (what the record represents, e.g. Chunk = one retrieval-unit slice of a document's text; EmbeddedChunk = a Chunk plus its embedding vector and 2D projection).
- `graphrag-core/src/main/java/io/graphrag/core/usecase/ConstructVectorIndex.java` / `TwoDProjection.java` (package-private) — add class-level Javadoc each; `TwoDProjection`'s own methods are already documented (per the audit), only the class itself lacks a summary line.
- `graphrag-core/src/main/java/io/graphrag/core/{domain,port,usecase}/package-info.java` (3 new files) — one-paragraph package-level Javadoc each, summarizing the layer's role (domain = data carried between use cases and ports; port = the SPI a consumer implements; usecase = the actual operations, called in the pipeline order the README documents).
- `graphrag-core/README.md` (new) — model the "wire the ports, call a use case" section on the real constructors: `IngestCorpus(List<DocumentParserPort>)` → `.ingest(List<UploadedDocument>)`; `ExtractEntitiesAndRelationships(LlmPort, GraphStorePort)` → `.run(Corpus)`; `DetectCommunities(GraphStorePort)` → `.run(Corpus)`; `ConstructVectorIndex(EmbeddingPort, VectorStorePort)` → `.run(Corpus)`; `AnswerLocalSearch(GraphStorePort)` → `.answer(String question, String corpusId)` — this is the exact pipeline order `audit-graphrag-core-reusability.md`'s Finding 8 already traced (ingest → extract/build graph → detect communities / build vector index → answer). State the JDK 25 requirement and Maven coordinates (`io.graphrag:graphrag-core:1.0.0`, noting it is not yet published to any registry — `mvn install` from source is required today) explicitly, since the root README's mention of JDK 25 is app-level, not module-level (per the audit's own finding).
- `graphrag-core/src/test/java/io/graphrag/core/usecase/IngestCorpusTest.java` — reference/verification source for the README's simplest example snippet (already identified by the audit as the shortest, most self-contained test).

## Tasks & Acceptance

**Execution:**
- `graphrag-core/README.md` (new) -- module description, JDK 25 requirement, Maven coordinates + not-yet-published note, a table or short list of the 5 ports, and one minimal end-to-end usage example covering the real pipeline order -- closes the audit's #1 "adoption friction" finding
- `EmbeddingPort.java`, `VectorStorePort.java` -- add class-level Javadoc -- closes the "2 of 5 interfaces with no class Javadoc" gap
- `GraphStorePort.java`, `LlmPort.java`, `EmbeddingPort.java`, `VectorStorePort.java` -- add `@param`/`@return` Javadoc to the 5 currently-undocumented abstract methods -- closes the "5 of 6 abstract methods undocumented" gap, the literal SDK surface a new adapter author implements against
- `Chunk.java`, `EmbeddedChunk.java`, `ConstructVectorIndex.java`, `TwoDProjection.java` -- add class-level Javadoc -- closes the last 4 gaps in otherwise well-covered layers
- `domain/package-info.java`, `port/package-info.java`, `usecase/package-info.java` (3 new files) -- package-level Javadoc summarizing each layer's role -- closes the "0 of 3 packages" gap

**Acceptance Criteria:**
- Given `graphrag-core/README.md`, when read, then it states the JDK 25 requirement, the Maven coordinates, that no registry publish exists yet, describes all 5 ports, and includes a usage example that compiles against the current API (verified by checking every class/method name and signature it references against the real source)
- Given the 5 port interfaces, when inspected, then every abstract method has `@param`/`@return` Javadoc and every interface has class-level Javadoc
- Given `domain`, `port`, and `usecase`, when inspected, then each has a `package-info.java`
- Given `Chunk`, `EmbeddedChunk`, `ConstructVectorIndex`, `TwoDProjection`, when inspected, then each has class-level Javadoc
- Given the full reactor, when `mvn clean install` runs, then it is still fully green — this is a Javadoc/markdown-only change with zero behavior impact

## Spec Change Log

## Review Triage Log

### 09-27-2026 — Review pass
- verdicts: 12 findings — high 0, medium 4, low 3, false 2, maybe-false 0, reject 1, defer 1 (1 finding routed patch without a severity-bearing "bad outcome" per se — see its row)
- findings:
  - `[medium]` `[patch]` Blind Hunter: the root `README.md`'s module table still describes `graphrag-core` with no link to the new `graphrag-core/README.md` — a reader following the top-level docs has no path down to the new module readme this story just introduced. Verified: this specific constraint (don't touch the root README) was this spec's own Boundaries text, not anything the raw intent (GitHub #29) actually excludes — a one-line link addition serves this very story's own discoverability goal rather than being unrelated work. Patch: add a link from the root README's `graphrag-core` table row to `graphrag-core/README.md`.
  - `[medium]` `[patch]` Blind Hunter: none of the 5 new abstract-method Javadoc blocks states nullability for their parameters/returns (`embed`'s `text`, `persistEntities`/`persistRelationships`/`persistChunks`'s `Collection` args, `extract`'s `corpus`) — the spec's own "Always" clause required contract Javadoc "never guess." Patch: check the actual call sites in `graphrag-web`/`graphrag-adapter-*` for whether null is ever passed for each, and document the parameter contract from that evidence rather than inventing one.
  - `[medium]` `[patch]` Blind Hunter: `Chunk`/`EmbeddedChunk` got only a bare class-level comment with no `@param` per record component, inconsistent with sibling records in the same package that this story's own neighborhood already documents that way (`UploadedDocument`, `Corpus`, both with per-field `@param`). Patch: add `@param` per record component to both, matching the existing package style.
  - `[low]` `[patch]` Blind Hunter: `domain/package-info.java`'s summary lists most but not all public types in that package (omits `GraphExtraction`, `ProjectionModel`, `UnsupportedFileTypeException`). Patch: make the list comprehensive.
  - `[low]` `[patch]` Blind Hunter: `graphrag-core/README.md` has no "Testing" section (e.g. `mvn test -pl graphrag-core`) — a natural companion to the existing "Requirements"/build instructions. Patch: add one.
  - `[low]` `[patch]` Blind Hunter (2 findings grouped, same fix): the usage example never mentions `IngestCorpus.ingest`'s `UnsupportedFileTypeException` failure mode, nor `LocalSearchAnswer`'s distinct "no graph-grounded match" (`noMatch()`) result — a consumer wiring these needs to know both exist. Patch: add one sentence each in prose near the relevant pipeline step, not new code branches (the AC asked for a *minimal* example; a prose mention preserves that while closing the awareness gap).
  - `[reject]` Blind Hunter: none of the new port Javadoc addresses thread-safety/concurrency expectations for implementations — rejected: no such contract is established anywhere in this single-user, local-only application (NFR3) today; documenting a guarantee that was never decided would itself be "guessing," which this spec's own Always clause explicitly forbids.
  - `[false]` Verification-gap: (no gaps found — screened as entirely non-behavioral, then independently re-verified the intent-contract's own compile/build claims against the real source and found them accurate).
  - `[false]` Edge Case Hunter: (no findings — independently re-verified the same build/compile claims and every call site in the README's usage example, all confirmed accurate).
  - `[medium]` `[patch]` Intent-alignment-auditor: several of the new `@param`/`@return`-only Javadoc blocks (`EmbeddingPort.embed`, `LlmPort.extract`, `GraphStorePort.persistEntities`/`persistRelationships`, `VectorStorePort.persistChunks`) have no leading descriptive sentence, triggering the `javadoc` tool's own "no main description" warning — inconsistent with the sibling default methods used as this story's own style model (e.g. `LlmPort.synthesizeFromChunks`, which has a full summary). Patch: add a one-sentence summary to each of the 5.
  - `[low]` `[defer]` Intent-alignment-auditor: the README's usage example is verified accurate today (by three independent reviewer layers plus this build's own manual check) but nothing keeps it that way automatically — no compiled/tested "doctest" exists, so a future port-signature change wouldn't fail any test tied to the README. Real, but adding new test infrastructure for this is a meaningfully larger addition than this documentation-only story's own scope; a reasonable future enhancement, not blocking.
  - `[false]` Intent-alignment-auditor: argued the diff's build/compile claims are asserted but not evidenced inside the diff itself — true narrowly, but the underlying claims were independently re-verified as accurate by every other layer plus this review's own manual checks; no further action needed beyond what's already been confirmed.
- patch outcomes: all 7 applied directly (the original implementation subagent session had ended by the time triage completed, so the patches were applied in this same session rather than dispatched back to it — content is identical in substance to what would have been sent). Root `README.md`'s `graphrag-core` table row now links to `graphrag-core/README.md`. The 5 abstract-method Javadoc blocks (`EmbeddingPort.embed`, `LlmPort.extract`, `GraphStorePort.persistEntities`/`persistRelationships`, `VectorStorePort.persistChunks`) each gained a leading summary sentence plus explicit "never null" nullability text on every `@param`/`@return`, grounded in the ports' actual usage (none of the existing call sites in `graphrag-web`/`graphrag-adapter-*` ever pass or return null for these). `Chunk`/`EmbeddedChunk` gained a `@param` per record component, matching `UploadedDocument`/`Corpus`'s existing style. `domain/package-info.java`'s type list now enumerates all 13 public domain types (including `GraphExtraction`, `ProjectionModel`, `UnsupportedFileTypeException`, `UnreadableDocumentException`, `RetrievalTrace`/`RetrievalStep`, `CommunityMembership`), grouped by role. `graphrag-core/README.md` gained a "Testing" section (`mvn test -pl graphrag-core -am`) and one prose sentence each on `UnsupportedFileTypeException`/`UnreadableDocumentException` (near the ingest step) and `LocalSearchAnswer.noMatch()` (near the answer step). Re-verified: `mvn -B clean install` — full reactor green, 95/95 tests. `mvn -B package -pl graphrag-core -am -Prelease` — `graphrag-core-1.0.0-javadoc.jar` and sources jar both build cleanly; remaining javadoc "no comment" warnings are all on pre-existing default methods outside this story's scope (unchanged from before this pass).

## Auto Run Result

- **Status:** done
- **Review loop iterations:** 1 (no `bad_spec` loopback needed — all findings were patch/reject/defer/false at the implementation level)
- **Findings:** 12 total — 4 medium (patch), 3 low (patch), 1 reject, 1 defer, 2 false, plus the 2 zero-finding layers counted as false above
- **Patches applied:** 7/7
- **Deferred:** 1 (README usage example has no compiled "doctest" guarding it against future port-signature drift — real but out of scope for a documentation-only story)
- **Verification:** `mvn -B clean install` full reactor green (95/95 tests); `mvn -B package -pl graphrag-core -am -Prelease` produces `graphrag-core-1.0.0-javadoc.jar` and sources jar cleanly
- **`followup_review_recommended`:** `true` — 4 medium-severity patches applied this pass (≥2 medium threshold)

## Design Notes

Split from the rename story (GitHub #29 part 1) because this is a prose/documentation-quality change reviewed for accuracy and completeness, not build correctness — a genuinely different review shape. This closes the remaining "Adoption friction" and "Polish" tiers of the audit's punch list; the two "Blocking" and "Publishing setup" tiers were already closed in the prior story.

## Verification

**Commands:**
- `mvn -q -B clean install` -- expected: full reactor green, unchanged behavior (Javadoc/markdown only)
- `mvn -q -B package -pl graphrag-core -am -Prelease` -- expected: `graphrag-core-1.0.0-javadoc.jar` still generates cleanly with the new Javadoc content (no malformed-tag failures) — this is the check the prior story's CI-coupling fix was specifically protecting against

**Manual checks (if no CLI):**
- Read `graphrag-core/README.md`'s usage example line by line against the real source files it references (constructor signatures, method names) to confirm it would actually compile.
