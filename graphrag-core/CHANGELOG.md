# Changelog

All notable changes to `graphrag-core` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Gleaning for `PromptedLlmPort` extraction.** `PromptedLlmPort.Options.withGleanings(n)` (default `0`) runs up to `n` more turns of the extraction conversation per Text Unit. Each turn asks only for the Entities and Relationships missed so far (`PromptedLlmPort.GLEANING_PROMPT`), and the results are merged by identity. Gleaning stops early when a turn adds nothing new or its reply cannot be used; what was found so far is kept. Each turn costs one more call per Text Unit.
- **Map-reduce Global Search.** New domain record `CommunityPoint(communityId, text, score)`, and new `LlmPort.mapsCommunities()` (default `false`) and `LlmPort.mapCommunities(question, communities)`.
  - When the port synthesizes answers and maps Communities, `AnswerGlobalSearch` reads up to 30 Communities (`MAX_MAPPED_COMMUNITIES`), most similar first with a semantic `EmbeddingPort`, otherwise by keyword score then id.
  - The Communities are read in batches of 5 (`MAP_BATCH_SIZE`), each recorded as a `COMMUNITY` step, and the port returns key points scored 0–100.
  - The 20 best points above 0 (`MAX_REDUCE_POINTS`) become `COMMUNITY` context items. Up to two member Text Units are added for each of the first five Communities the points come from (`MAX_PASSAGE_COMMUNITIES`). One `synthesizeAnswer` call writes the cited answer (the reduce step).
  - No point above 0 gives the not-in-context answer without that call.
  - `PromptedLlmPort` maps whenever synthesis is on (`Purpose.COMMUNITY_POINTS`, `Schemas.COMMUNITY_POINTS`, up to 3 points per Community), so `OpenAiLlmPort` and the web app use map-reduce too.
  - A synthesizing port that does not map keeps the three-Community path, and the templated path is unchanged.
- **Entity name hints.** New `LlmPort.extract(TextUnit, List<String> entityTypes, List<String> knownEntityNames)`; its default ignores the names. `ExtractEntitiesAndRelationships` passes each Text Unit the 50 most-mentioned Entity names resolved so far in the run (`EntityResolver.mostMentionedNames(int)`). `PromptedLlmPort` lists them before the passage, so the model reuses their spelling instead of creating duplicates. A port that wraps another must forward the new overload, as `StageClock` does.
- **Description summaries.** New `LlmPort.summarizesDescriptions()` (default `false`) and `LlmPort.summarizeDescription(elementName, description)`.
  - With a summarising port, `ExtractEntitiesAndRelationships` keeps up to 8,000 characters of each element's distinct description sentences during a run. What it persists per Text Unit is still cut to `GraphElementMerger.DESCRIPTION_LIMIT`, which is now public (1,000).
  - At the end of the run, each Entity and Relationship whose description outgrew the limit is summarised once and persisted again.
  - A failed summary fails the run under `FailurePolicy.FAIL_RUN`. Under `ISOLATE_ITEM` it keeps the cut description.
  - `PromptedLlmPort` implements this with `Options.withDescriptionSummaries(true)`, `Purpose.DESCRIPTION_SUMMARY` and `Schemas.DESCRIPTION_SUMMARY`.
  - `GraphElementMerger` gains `merge(..., int descriptionLimit)`, `mergeDescriptions(existing, added, limit)` and `capDescription`.
  - Without a summarising port, nothing changes.
- **`PromptedLlmPort.compareAnswers`.** The GraphRAG-vs-Vector verdict is now written by the model (`Purpose.VERDICT`, `Schemas.VERDICT`, output-token limit `Options.verdictTokens()`, default 256). Before, it was always the rule-based text. An unusable reply fails with `LlmReplyException`, and `CompareAnswers` falls back to the rule-based verdict as before.

### Changed

- **Better `PromptedLlmPort` prompts.**
  - **Extraction:** asks for each entity's most complete name, spelled the same way every time. It skips pronouns and generic nouns, allows only relationships between listed entities, and asks for relationship types as lowercase `verb_phrase`s. Consistent names and types mean fewer duplicate Entities and Relationships across Text Units.
  - **Community summary:** now includes Relationship descriptions and asks which members matter most.
  - **DRIFT sub-questions:** each one must be answerable from its own Community.
  - **Answer:** says that Entity, Relationship and Community items are background that is never cited, rules out outside knowledge, and asks for a partial answer that names what is missing.
- `OpenAiLlmPort` (adapter, not released with the core) is now a `PromptedLlmPort`. It uses the core's prompts, schemas and lenient parsing, and only implements `complete`, sending the conversation in OpenAI JSON mode. Every failure is still an `OpenAiLlmPort.LlmCallFailedException`: a network error, a reply cut off by the output-token limit, or a reply that stays unusable. `summarizesCommunities()` is now true. DRIFT sub-questions stay deterministic. A blank Community summary now fails instead of falling back to the templated one. There is a new constructor taking `PromptedLlmPort.Options`, and the web app reads `GRAPHRAG_EXTRACTION_GLEANINGS` (default 0) and `GRAPHRAG_DESCRIPTION_SUMMARIES` (default false).
- **Breaking for record patterns:** `PromptedLlmPort.Options` gains the record components `gleanings`, `verdictTokens` and `descriptionSummaries`. The seven-argument constructor is kept; it means no gleaning, 256 verdict tokens and no description summaries. A negative `gleanings` throws `IllegalArgumentException`.

### Fixed

- **`IdentifierSeedMatcher` no longer counts package segments.** The lower-case segments in front of the first upper-case one (`org.pulsar.broker` in `org.pulsar.broker.service.BrokerService`) are not among a name's words, and a token equal to one scores only 3 (below the minimum of 6). Before, `BrokerService` half-matched (12.5) every class in a package called `broker`, and a plain word such as "broker" matched all of them. A qualified token that is the start of a name (`com.acme.order`) now scores 30 for the classes in that package. Names without an upper-case segment (Python, Go) have no package and score as before.
- **`IdentifierSeedMatcher` indexes the normalised names once per corpus.** It keeps the forms of the Entities it saw last per corpus id and reuses them while `entities(corpusId)` returns equal Entities in the same order, so a question no longer normalises every name again (about 10 s cold for 2.4k Entities). Any change to the Entities rebuilds that corpus's index. Reuse one matcher instance.

## [2.0.1] - 2026-10-06

### Added

- **`graphrag-core-testkit` on Maven Central** (`dev.rabauer.graphrag:graphrag-core-testkit`), released together with `graphrag-core` under the same version. Its POM no longer has a parent.

### Changed

- `graphrag-core` and `graphrag-core-testkit` are now compiled for Java 21 (class-file major version 65) instead of Java 25, so Java 21 applications can use them. The API is unchanged. Building them needs JDK 21 or newer; CI builds and tests them on JDK 21 and 25, and the release workflow fails if any class is not major version 65.

## [2.0.0] - 2026-10-06

The first release on Maven Central (`dev.rabauer.graphrag:graphrag-core:2.0.0`). It contains breaking changes, including the namespace move below.

### Added — reuse for exact, non-text graphs (Codebase Atlas)

- **Optional LLM capabilities.** `LlmPort` gains the capability flags `extractsEntities()` (default `true`), `summarizesCommunities()` and `derivesSubQuestions()` (default `false`), in the style of `synthesizesAnswers()`, and `LlmPort.none()`, a port with every capability off. `extract(Corpus)` stays the only abstract method so `LlmPort` remains a functional interface; a port without extraction returns the new `GraphExtraction.empty()`. `ExtractEntitiesAndRelationships` makes no extraction call for a null port or one whose `extractsEntities()` is `false`, and persists only the Text Units.
- **`ImportKnowledgeGraph`** use case with `KnowledgeGraphImport`, `ImportKnowledgeGraph.Options`, `ImportKnowledgeGraph.MissingEndpoints` and `ImportResult`: imports pre-built Text Units, Entities and Relationships (and optionally caller-supplied Communities and memberships) without any extraction call, then optionally detects Communities and embeds. Repeated Relationships have their weights summed (`GraphElementMerger.mergeSummingWeights`).
- `DetectCommunities.detect(String corpusId)` / `detect(String corpusId, callback)` and `EmbedGraphElements.run(String corpusId)` for corpora known only by id.
- **Attributes and source locators.** New domain record `SourceLocator(path, startLine, endLine)` (`format()` renders `path:start-end`, `parse(...)` reads it back) and helper `Attributes`. `Entity`, `Relationship`, `TextUnit`, `Citation` and `RetrievalStep` gain the components `attributes` (`Map<String, String>`, never null, key-sorted) and `locator` (nullable); `Community` gains `attributes`. Every existing constructor is kept and creates elements without them; `with(...)`, `withAttributes(...)` and `withLocator(...)` derive copies. `Relationship.sourceIdentity()` / `targetIdentity()`. Merging and entity resolution keep attributes and locators; Local, Global and DRIFT trace steps and citations expose the touched element's locator and attributes. The Neo4j adapter persists both as properties.
- **Free-form Entity types.** `ExtractEntitiesAndRelationships(LlmPort, GraphStorePort, List<String> entityTypes)` restricts extraction to a caller's type list (the last entry is the catch-all); `EntityTypes.normalize(type, allowed)`. The default stays `EntityTypes.ALL`.

- **Core community detector.** New package `dev.rabauer.graphrag.core.community`: `CommunityDetector`, `WeightedEdge`, `ModularityCommunityDetector` (deterministic, seedable Louvain with a Leiden-style connectivity refinement; resolution, levels and iterations configurable; `detectHierarchy`, `modularity`), `ConnectedComponentsCommunityDetector` and `GraphCommunities`.
- **`DetectCommunities.Options`** (minimum size, detector, bounded parallelism, summary budget by count and wall time, `FailurePolicy`, summary reuse by content hash), `DetectCommunities.ProgressListener`, `run(corpusId[, progress, callback])` returning the new `CommunityDetectionResult` (status `COMPLETE`/`PARTIAL`/`EMPTY` and a `SummaryStatus` per Community), and the constants `CONTENT_HASH_ATTRIBUTE` / `SUMMARY_STATUS_ATTRIBUTE`. `FailurePolicy` (`FAIL_RUN`, `ISOLATE_ITEM`). `ImportKnowledgeGraph.Options` carries `DetectCommunities.Options`; `ImportResult.detection()`.
- Neo4j adapter: `Neo4jGraphStoreOptions` with `CommunityDetection.CORE` (no GDS plugin needed) and an opt-in `fallbackToCoreDetector` for GDS failures; `InMemoryGraphStoreAdapter(CommunityDetector)`.

- **Retrieval-only queries.** `RetrieveLocalContext`, `RetrieveGlobalContext` and `RetrieveDriftContext` return a `RetrievalResult` (mode, status, reason, numbered `RetrievedItem`s with locator, attributes, score and hop, the full `RetrievalTrace`, warnings) without synthesizing; `toContextItems()` for callers that synthesize themselves. Options: `LocalRetrievalOptions` (seed limit, hops, node/relationship/Text Unit/item caps, relationship type include/exclude, weight threshold, ordering, direction, neighbour Entities, member Text Units; `defaults()` and `answerContext()`), `GlobalRetrievalOptions`, `DriftRetrievalOptions`. New package `dev.rabauer.graphrag.core.retrieval`.
- **Seed matchers.** `SeedMatcher` / `SeedMatch` with `KeywordSeedMatcher` (the default without a semantic model), `SemanticSeedMatcher`, the code-aware `IdentifierSeedMatcher` (camelCase, snake_case, `.`, `::`, `#`, qualified and simple names, fuzzy) and `HybridSeedMatcher` (reciprocal-rank fusion); `SeedMatchers.defaultFor`, `forCode`, `firstNonEmpty`; `Identifiers` (normalise, split and find code identifiers).
- Local Search's synthesis context is now assembled by the same expansion engine (with `LocalRetrievalOptions.answerContext()`); its output is unchanged.

- **Read and write ports.** `GraphReadPort` (all query reads, plus the new `entity(corpusId, identity)`, `entities(corpusId, identities)` and `relationshipsTouching(corpusId, identities)` lookups with filtering defaults) and `GraphWritePort` (persist, retype, embeddings, `detectCommunities`); `GraphStorePort` extends both and keeps every method. The query use cases (`Answer*`, `Compare*`, `Retrieve*`) and the seed matchers take a `GraphReadPort`; Local expansion reads hop by hop. Source-compatible for callers; recompile against 2.0.
- **`graphrag-core-testkit`** (new artifact): `GraphReadPortContract`, `GraphStorePortContract`, `VectorStorePortContract`, `EmbeddingPortContract`, `CodeGraphRetrievalContract`, the fixtures `ContractGraph` and `CodeGraphFixture`, and the reference `InMemoryGraphStore`.
- `DetectCommunities` reports summaries of a port whose `summarizesCommunities()` is false (for example `LlmPort.none()`) as `DETERMINISTIC`.

- **Incremental updates.** `UpdateSources` with `removeBySource(corpusId, sourceId)` (returns `SourceRemoval`: removed Text Units, Entities, orphans, Relationships, trimmed elements, deleted chunks, stale Community ids), `replaceSource(...)` (upsert by source) and `recomputeCommunities(corpusId)`; `Sources` (an element's source: `source` attribute, locator path, document name); `DetectCommunities.recompute(corpusId)` (delete, then detect with summary reuse); `EmbedGraphElements.embedEntities` / `embedCommunities`. `ImportKnowledgeGraph` now embeds only the Entities it imported (and the Communities when it persisted some).
- `GraphWritePort.deleteTextUnits`, `deleteEntities` (cascading), `deleteRelationships`, `deleteCommunities` and `VectorStorePort.deleteChunksOf`; their defaults throw `UnsupportedOperationException`.
- Neo4j adapter: `Neo4jGraphStoreOptions.withPrefixes(labelPrefix, relationshipTypePrefix)` (validated, applied to labels, relationship types, constraint, vector index and projection names), `withCreateConstraints`, a `Neo4jGraphStoreAdapter(Supplier<Session>, options)` constructor, the delete methods, and indexed `entity` / `entities(corpusId, identities)` / `relationshipsTouching`. The in-memory adapters implement the deletes.

- **Small-model robustness.** `LenientJson` (find and repair the JSON object in a model reply; JDK only). `PromptedLlmPort`: an abstract `LlmPort` with core prompts, JSON Schemas, lenient parsing and one corrective retry over a single `complete(CompletionRequest)` method; `LlmReplyException` for an item whose reply stays unusable. `ExtractEntitiesAndRelationships(llm, store, entityTypes, FailurePolicy)` and `runWithReport(...)` returning an `ExtractionReport`. `OpenAiLlmPort` parses leniently and has an opt-in corrective retry (`OpenAiLlmPort(apiKey, model, correctiveRetry)`).

### Changed — default community grouping

- **Behaviour change:** the default `GraphStorePort.detectCommunities` is now the modularity-based `ModularityCommunityDetector` instead of connected components. Two groups joined by a single bridge now become two Communities. Stores that override the method (the Neo4j adapter with GDS Leiden) are unaffected. Use `ConnectedComponentsCommunityDetector` (per store or via `DetectCommunities.Options.withDetector`) for the previous grouping.

### Changed — for record patterns

- `Entity`, `Relationship`, `TextUnit`, `Citation`, `RetrievalStep` and `Community` have new record components (see above). Calls to the existing constructors still compile; record deconstruction patterns must list the new components.

### Changed

- **Breaking — namespace moved to `dev.rabauer.graphrag`.**
  - The Maven `groupId` changes from `io.graphrag` to `dev.rabauer.graphrag`; the `artifactId` `graphrag-core` is unchanged.
  - The packages move from `io.graphrag.core.{domain,port,usecase}` to `dev.rabauer.graphrag.core.{domain,port,usecase}`.
  - Consumers update their dependency coordinates and replace `io.graphrag.core` with `dev.rabauer.graphrag.core` in their imports. No class or method names changed.
  - The project version is now `2.0.0-SNAPSHOT`.
- **Behaviour change — Communities need at least 3 members.**
  - `DetectCommunities` now drops every group from `GraphStorePort.detectCommunities` with fewer than 3 distinct member identities.
  - A dropped group produces no `Community`, no `CommunityMembership`, no `LlmPort.summarizeCommunity` call and no `onCommunityDetected` callback. Its Entities stay ordinary Entities in the graph, still reachable by Local Search, but Global and DRIFT Search no longer see them.
  - Community ids stay `community-1..n`, contiguous over the kept groups, in the same deterministic order as before.
  - When no group is large enough, nothing is persisted and `detect` returns an empty list.
  - New public constant `DetectCommunities.MIN_COMMUNITY_SIZE = 3` and a new constructor `DetectCommunities(GraphStorePort, LlmPort, int minCommunitySize)`. The existing constructors default to 3; pass `1` to restore the old behaviour, where every group (singletons included) became a Community. A value below 1 throws `IllegalArgumentException`.
  - The `GraphStorePort.detectCommunities` contract is unchanged: adapters still return every group, singletons included.
  - The `noCommunitiesYet()` reasons of `GlobalSearchAnswer` and `DriftSearchAnswer` are reworded: a corpus can now have no Communities after detection has finished, so they no longer imply that detection is still running.
- **Behaviour change — the vector baseline writes cited answers.**
  - With a synthesizing `LlmPort` (`synthesizesAnswers()`), `AnswerVectorBaseline` no longer calls `synthesizeFromChunks`. It passes its top-k chunks (k = 5, unchanged) to `synthesizeAnswer` as numbered `TEXT_UNIT` context items whose `textUnitId` is the chunk id, and resolves the `[n]` markers with the same rules as the GraphRAG modes. Each citation is a `Citation(chunkId, documentName, excerpt)`.
  - When the model says the chunks do not answer the question, the result has no `answer`, a `reason` (`VectorBaselineAnswer.NOT_IN_CONTEXT_REASON`) and the retrieval steps so far, without a `SYNTHESIS` step.
  - With a non-synthesizing port the output is unchanged: the joined chunk texts, no citations, the same trace.
- **Breaking for record patterns:** `VectorBaselineAnswer` gains a sixth record component, `citations`, so its canonical constructor is now `(noChunks, answer, reason, steps, queryProjection, citations)`. The five-argument constructor and the existing factories are kept. New: `noAnswer()` (no chunks, or not in context), `synthesized(...)` and `notInContext(...)`.
- **Breaking for record patterns:** `VectorBaselineAnswer` gains two more record components, `ranking` (`List<RankedChunk>`) and `scoredChunkCount` (`int`), so its canonical constructor is now `(noChunks, answer, reason, steps, queryProjection, citations, ranking, scoredChunkCount)`. Record patterns that deconstruct it must list all eight components. The six- and five-argument constructors and every factory are kept and produce an empty ranking and `0`; `noChunksYet()` does too. New: `withRanking(ranking, scoredChunkCount)`.
- **Breaking for record patterns:** `Chunk` gains a fifth record component, `documentName` (`""` when unknown), filled by `ConstructVectorIndex` from `UploadedDocument.filename()`. The four-argument constructor is kept. The `ordinal` Javadoc now says what the code always did: the ordinal is corpus-wide, not per document.

### Added

- `CompareAllModes`: answers one question with all four retrieval methods (`CompareAllModes.Method`: LOCAL, GLOBAL, DRIFT, VECTOR), one after the other on the same corpus. Each `MethodRun` holds the outcome (`ANSWERED`, `NO_ANSWER` or `FAILED` with the exception; a failing method never stops the others), the answer or reason, citations, the trace steps, a step count per `RetrievalStep.Kind` (`footprint`), the vector ranking and scored-chunk count (Vector Search only) and a `StageTiming`. The `Comparison` adds an evidence table (`EvidenceRow`): every passage a method read, ranked or cited, with one `Mark` per method saying how far it got (`Use`: `NOT_RETRIEVED`, `RANKED_BELOW_CUTOFF`, `IN_CONTEXT`, `CITED`), its `[n]` citation numbers and, for Vector Search, its best rank. Vector chunks join the row of a Text Unit a graph method read when the Text Unit contains them (the `CompareAnswers` matching rule), else they get a row of their own. A deterministic summary states who answered, the fastest and slowest run and how much cited evidence is shared.
- Domain record `StageTiming(totalMs, retrievalMs, embeddingMs, embeddingCalls, llmMs, llmCalls)`: where one run's time went. Embedding and LLM time are measured by wrapping the run's `EmbeddingPort` and `LlmPort`; retrieval is the rest.
- `AnswerVectorBaseline` exposes its similarity ranking: `VectorBaselineAnswer.ranking()` holds the top `AnswerVectorBaseline.RANKING_SIZE = 12` chunks by cosine score, in descending order with the same ordering and tie-break as the top-k selection (a stable sort, so equal scores keep the vector store's order). `scoredChunkCount()` is the total number of chunks scored. Retrieval, top-k (5), the trace and the answers are unchanged.
- Domain record `RankedChunk(rank, chunkId, documentName, excerpt, score, used)`: one ranking row. `rank` is 1-based, `excerpt` follows the citation rule (first 200 characters, whitespace-collapsed, "…" when cut), and `used` is true exactly for the top-k chunks that feed the answer.
- `CompareAnswers`: answers one question fresh with a GraphRAG mode (`CompareAnswers.Mode`: LOCAL, GLOBAL or DRIFT) and with `AnswerVectorBaseline`, timing each side with `System.nanoTime`. It returns both answers, per-side `ComparisonStats` (context items, distinct documents, latency), which citations point at a passage both sides retrieved (a vector chunk whose whitespace-normalized text is contained in a Text Unit the graph side read, from the same document; a chunk with no document name matches on text alone), the passages each side retrieved (`CompareAnswers.RetrievedPassage`, cited or not), and a `ComparisonVerdict`. Graph context items count distinct (kind, identifier) pairs, so DRIFT's repeated branch steps count once; distinct documents ignore unknown (`""`) names. Answer failures propagate; a failing verdict call falls back to the rule text and is logged through `System.Logger`.
- `LlmPort.compareAnswers(question, graphAnswer, vectorAnswer, ComparisonFacts)`: a short verdict on where the two answers differ and why. The default is the deterministic `ComparisonVerdict.ruleBased(facts)` (source `RULE`), so existing implementations and lambdas need no change.
- Domain records `ComparisonStats`, `ComparisonFacts` (both sides' stats plus the passage overlap counts) and `ComparisonVerdict` (text plus source `LLM` or `RULE`).
- `AnswerGlobalSearch(GraphStorePort, EmbeddingPort, LlmPort)` constructor
  (Story 15.3). With a synthesizing port, Global Search takes the top 3
  Communities (semantic, else the top 3 keyword scores above zero, id as
  tiebreak) and records each as a `COMMUNITY` step and context item
  (`title: summary`), directly followed by up to 2 member Text Units as
  `TEXT_UNIT` steps, ranked by the summed weight of the internal
  Relationships citing them plus 1 per citing member Entity (ties in
  first-seen order); a Text Unit already added for an earlier Community is
  skipped. Citations are resolved as for Local Search. Without a candidate
  the existing no-match answer is returned with no LLM call. With a null or
  non-synthesizing port the output is unchanged.
- `AnswerDriftSearch` with a synthesizing `LlmPort` (Story 15.3): each
  sub-question branch assembles a Local-style context (same rules and caps
  as Local Search) under its `SUB_QUESTION_SPAWNED` step, with no LLM call
  of its own. One synthesis runs over the candidate Community summaries
  plus the union of the branch items (de-duplicated by entity identity,
  edge id and Text Unit id, numbered in first-seen order) and is recorded
  as the single `SYNTHESIS` step (identifier the first candidate's id,
  label the answer) after the last branch. If no branch found a seed, the
  existing no-local-match outcome is kept and no synthesis call is made.
  With a non-synthesizing port the output is unchanged.
- `GlobalSearchAnswer.synthesized(...)` / `notInContext(reason, steps)` and
  the same factories on `DriftSearchAnswer` (Story 15.3): `[i]` in a
  synthesized answer is `citations[i-1]`; not-in-context is a `noAnswer`
  result with the steps kept (no `SYNTHESIS` step for DRIFT).
- `LlmPort.synthesizeAnswer` may now receive `COMMUNITY` context items
  (Global and DRIFT); they are background, never cited.
- `LlmPort.synthesizesAnswers()` (default `false`) and
  `LlmPort.synthesizeAnswer(String question, List<ContextItem> context)`
  (default `null`): an answer-synthesizing LLM writes the Local Search answer
  from a numbered context and cites items inline as `[n]`. Existing
  implementations and lambdas keep the templated answer. Additive, minor
  change.
- `AnswerLocalSearch(GraphStorePort, EmbeddingPort, LlmPort)` constructor.
  With a synthesizing port, Local Search assembles a bounded context and
  records each item as a trace step as it is added: the seed Entities (up to
  3 semantic seeds, else the keyword seed), up to 10 Relationships touching a
  seed (highest `weight` first, ties in stored order), and up to 5 Text Units
  they cite (ranked by the summed weight of the citing Relationships plus 1
  per citing seed; a missing Text Unit is skipped, a store failure propagates). Citations
  pointing at anything but a Text Unit of that context are dropped and the
  rest renumbered `1..k` in order of first appearance. A `NOT_IN_CONTEXT` or
  blank answer becomes a no-answer result with the steps kept; an LLM failure
  propagates. With a null or non-synthesizing port the output is unchanged.
- `RetrievalStep.Kind.TEXT_UNIT`: a source passage read into a synthesized
  Local answer's context; identifier is the Text Unit id, label its excerpt
  (first 200 characters, whitespace-collapsed, "…" when cut). Not fully
  additive: an exhaustive `switch` over `RetrievalStep.Kind` without a
  `default` branch no longer compiles.
- `ContextItem(number, kind, text, textUnitId)`, `Citation(textUnitId,
  documentName, excerpt)` and `SynthesizedAnswer(notInContext, text)` value
  records. `SynthesizedAnswer.NOT_IN_CONTEXT` is the sentinel answer, and
  `isNotInContextSentinel(text)` matches it ignoring case, surrounding
  whitespace and quotes, and trailing punctuation.
- `LocalSearchAnswer` gains `noAnswer`, `reason` and `citations` components
  (`[i]` in the answer is `citations[i-1]`) and the `synthesized(...)` and
  `notInContext(reason, steps)` factories. The `(answer, steps)` constructor,
  `matched(...)` and `noMatch()` remain and produce no citations. Not fully
  additive: record deconstruction patterns (`LocalSearchAnswer(var a, var s)`)
  and calls to the canonical constructor must be updated to the five
  components.
- `EmbeddingPort.isSemantic()`: whether the port is a real semantic embedding
  model. The default is `true`; deterministic offline stubs return `false`.
  Additive, minor change.
- `GraphStorePort.persistEntityEmbeddings(String corpusId, Map<String, float[]> byIdentity)`
  and `persistCommunityEmbeddings(String corpusId, Map<String, float[]> byCommunityId)`
  (default no-ops), and `similarEntities(String corpusId, float[] query, int k)`
  / `similarCommunities(String corpusId, float[] query, int k)` (default empty
  lists): corpus-scoped embedding storage and top-k similarity lookup, most
  similar first. Existing implementations need no change; an empty result
  makes the searches fall back to keyword matching. Additive, minor change.
- `EmbedGraphElements(GraphStorePort, EmbeddingPort)` use case: with a
  semantic port, embeds every Entity (`name + ": " + description`) and
  Community (`summary`) of a corpus and persists the vectors; does nothing
  with a null or non-semantic port. An embedding failure propagates (no
  retry), so ingestion fails visibly. In the Neo4j adapter the vectors sit
  behind one vector index per label, `entity_embedding` and
  `community_embedding` (cosine, filtered by `corpusId`, shared by all
  corpora). The index dimension is fixed by the first vector ever persisted;
  persisting vectors of a different dimension (e.g. after changing the
  embedding model) now fails with an `IllegalStateException` naming the index
  and both dimensions.
- `SemanticMatchingException`: thrown by the semantic search path when
  embedding the question or the similarity lookup fails at query time. The
  searches do not fall back to keywords in that case.
- `AnswerLocalSearch(GraphStorePort, EmbeddingPort)`,
  `AnswerGlobalSearch(GraphStorePort, EmbeddingPort)` and
  `AnswerDriftSearch(GraphStorePort, LlmPort, EmbeddingPort)` constructors.
  With a semantic port, Local records the 3 most similar Entities as `ENTITY`
  steps (in similarity order) and hops from the first; Global records the 3
  most similar Communities and answers from the first; DRIFT uses the 3 most
  similar Communities as candidates. When the store returns no similar
  elements (e.g. a corpus ingested without embeddings), each mode falls back
  to its keyword path. The existing constructors keep the keyword behavior
  unchanged.
- `GraphStorePort.detectCommunities(String corpusId)`: returns the corpus's
  Entities grouped into Communities, as lists of member identities
  (`Entity.normalizedIdentity()` format). The default implementation is the
  connected-components grouping `DetectCommunities` used before, so existing
  implementations need no change. A graph store may override it with a native
  algorithm (the Neo4j adapter uses GDS Leiden). Additive, minor change.
- `Community.title()`: a short Community title. The canonical constructor is
  now `Community(id, title, summary)` (a null title becomes `""`); the
  `Community(id, summary)` constructor remains and sets an empty title.
- `CommunitySummary(title, summary)` value record, with `trimTitle(...)`
  (at most 6 words) and `deterministicTitle(members)` (first two distinct
  member names joined with " & ", or "Related entities").
- `LlmPort.summarizeCommunity(Collection<Entity> members,
  Collection<Relationship> relationships)`: writes a Community's title and
  summary from its members and internal Relationships. The default wraps the
  existing `summarizeCommunity(members)` with the deterministic title, so
  existing implementations need no change. Additive, minor change.

### Changed

- **Breaking for record patterns:** `GlobalSearchAnswer` and
  `DriftSearchAnswer` gain a fifth record component, `citations` (Story
  15.3), so their canonical constructors are now `(noAnswer, answer, reason,
  steps, citations)`. Record patterns and deconstruction that use four
  components (e.g. `case GlobalSearchAnswer(var n, var a, var r, var s)`) no
  longer compile and must add the `citations` component. The four-argument
  constructors and the existing factories still compile and produce no
  citations.
- `DetectCommunities` now gets its grouping from
  `GraphStorePort.detectCommunities(...)` instead of running its own BFS.
  Community ids stay `community-1..n`, assigned in the order of each group's
  first member in `entities(corpusId)`, with members in that order, whatever
  order the port returns; an Entity the port leaves out becomes a
  single-member Community. With the default port, Community ids and member
  sets are unchanged, but members are now listed in `entities(corpusId)`
  order instead of BFS discovery order; this also changes the order of the
  `onCommunityDetected` member lists, of the persisted memberships, and of the
  names in the fallback (no-LLM) summary.
- **Breaking for record patterns:** `Community`'s record components and
  canonical constructor are now `(id, title, summary)` instead of
  `(id, summary)`. Record patterns and deconstruction that use two components
  (e.g. `case Community(var id, var summary)`) no longer compile and must add
  the `title` component. The `Community(id, summary)` constructor still
  compiles and sets an empty title.
- `DetectCommunities` now calls the new two-argument `summarizeCommunity`
  with the Community's first 25 members (entity order) and its internal
  Relationships (both endpoints among those 25 members), the 30 highest-weight
  first with ties in stored order, and stores the returned title on each
  Community. A port returning null gets the deterministic title and summary.
  It reads `relationships(corpusId)` once per detection run.

## [1.0.0] - 2026-09-27

### Changed

- **Breaking:** renamed the module's Maven coordinates and Java package
  identity from `com.graphraglens` / `com.graphraglens.core.*` to a generic,
  reusable identity: `groupId io.graphrag`, packages `io.graphrag.core.domain`,
  `io.graphrag.core.port`, and `io.graphrag.core.usecase`. Any prior
  internal-only usage under the old `com.graphraglens.core` package must
  update its imports and `graphrag-core` dependency coordinates.
- `graphrag-core` now declares its own `groupId` explicitly rather than
  inheriting it from the `graphrag-parent` reactor POM, decoupling the
  module's identity from the host application's.

### Added

- First real, non-`SNAPSHOT` release version (`1.0.0`).
- Publish-ready POM metadata: `<licenses>` (Apache License 2.0), `<scm>`,
  `<developers>`, `<url>`, and a `<distributionManagement>` entry targeting
  GitHub Packages as a low-setup-cost default registry.
- `maven-source-plugin` and `maven-javadoc-plugin` executions bound to the
  `package` phase, producing sources and javadoc JARs alongside the main
  artifact.
- Repository root `LICENSE` file (Apache License 2.0, full text).

### Notes

- No behavior changed in this release: it is a coordinate/package rename
  plus publishing metadata only. Every existing test's assertions are
  unchanged; only import paths were updated.
- No artifact was actually published to any registry as part of this
  release — `<distributionManagement>` is declared but unused pending the
  maintainer running the publish step themselves against their own
  registry account.

<!--
  The `graphrag-core-1.0.0` tag referenced below does not exist yet: it is
  the tag this release is meant to be cut under once actually released, not
  a live link. Create it (and update this link if needed) at release time.
-->
[2.0.1]: https://github.com/JohannesRabauer/graph-rag-visualisation/tree/graphrag-core-v2.0.1
[2.0.0]: https://github.com/JohannesRabauer/graph-rag-visualisation/tree/graphrag-core-v2.0.0
[1.0.0]: https://github.com/JohannesRabauer/graph-rag-visualisation/tree/graphrag-core-1.0.0
