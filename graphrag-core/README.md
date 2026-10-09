# GraphRAG Lens :: Core

[![Maven Central](https://img.shields.io/maven-central/v/dev.rabauer.graphrag/graphrag-core)](https://central.sonatype.com/artifact/dev.rabauer.graphrag/graphrag-core)

`graphrag-core` is the framework-free hexagonal core of GraphRAG Lens: the
domain model and the port interfaces (the SPI) that a consuming application
implements and wires together, plus the use cases that orchestrate them. It
carries no dependency on any web framework, graph-database driver, or
LLM-orchestration library (AD-1) — nothing in this module imports Spring, the
Neo4j Java Driver, or LangChain4j, and the build enforces that ban.

## Requirements

- **JDK 21+** — this module and the testkit are compiled for Java 21 (class-file major
  version 65), so Java 21 applications can use it. CI builds and tests it on
  JDK 21 and JDK 25. Building the whole reactor (web app and adapters) needs
  JDK 25.
- Apache Maven 3.9+

## Maven coordinates

```xml
<dependency>
    <groupId>dev.rabauer.graphrag</groupId>
    <artifactId>graphrag-core</artifactId>
    <version>2.0.1</version>
</dependency>
```

For the [testkit](#testkit-proving-an-adapter-correct), add
`dev.rabauer.graphrag:graphrag-core-testkit` with the same version and
`<scope>test</scope>`.

Both are released on Maven Central; no extra repository configuration is needed. To
build the current development version (`-SNAPSHOT`) yourself, clone this
repository and install it into your local Maven repository:

```
mvn install -pl graphrag-core -am
```

## An exact code graph in five calls

For a non-text source — classes, methods and calls from a code scan — no
extraction model is involved; only Community summaries may use one:

```java
// Ports: your own GraphStorePort (or only a GraphReadPort for queries), an optional LlmPort for summaries.
GraphStorePort graph = ...;                 // e.g. Neo4jGraphStoreAdapter with prefixes and CORE detection
LlmPort llm = new MySmallModelPort();       // extends PromptedLlmPort, or LlmPort.none()

// 1. Import the exact graph (Entities/Relationships/TextUnits with attributes and SourceLocators).
ImportResult imported = new ImportKnowledgeGraph(graph, llm, null).run("my-repo",
        KnowledgeGraphImport.of(textUnits, entities, relationships),
        ImportKnowledgeGraph.Options.defaults().withCommunities(DetectCommunities.Options.defaults()
                .withParallelism(2).withMaxWallTime(Duration.ofMinutes(5))
                .withFailurePolicy(FailurePolicy.ISOLATE_ITEM).withReuseSummaries(true)));

// 2. Retrieve for an agent — no synthesis, a trace with path:start-end on every item.
RetrievalResult callers = new RetrieveLocalContext(graph, SeedMatchers.forCode(null),
        LocalRetrievalOptions.defaults().withDirection(LocalRetrievalOptions.Direction.INCOMING)
                .withIncludeRelationshipTypes(Set.of("CALLS")))
        .retrieve("Who calls OrderService.placeOrder?", "my-repo");
RetrievalResult overview = new RetrieveGlobalContext(graph, null, SeedMatchers.forCode(null),
        GlobalRetrievalOptions.defaults()).retrieve("How does placeOrder work?", "my-repo");

// 3. Re-index a changed file, then refresh the Communities once per batch.
UpdateSources updates = new UpdateSources(graph, null, llm, null);
updates.replaceSource("my-repo", "src/main/java/com/shop/OrderService.java", graphOfThatFile, null);
updates.recomputeCommunities("my-repo");
```

`CodeGraphExampleTest` in `graphrag-core-testkit` runs this path on a 50-class
graph, offline.

## The ports

A consumer implements these five interfaces (all in `dev.rabauer.graphrag.core.port`)
to plug in its own technology choices. Every default method already has a
usable fallback; only the abstract methods listed below are required.

| Port | Purpose | Abstract method(s) you implement |
| --- | --- | --- |
| `DocumentParserPort` | Parses source documents (e.g. PDF, text) during ingestion. | `boolean supports(String filename)` |
| `LlmPort` | LLM-driven knowledge-graph extraction and related generation. Every capability is optional (see [Optional LLM capabilities](#optional-llm-capabilities)). | `GraphExtraction extract(Corpus corpus)` (a port without extraction returns `GraphExtraction.empty()`) |
| `GraphReadPort` | Reads the knowledge graph: everything the query use cases need. | none (every method has a default; implement the corpus-scoped reads you have) |
| `GraphWritePort` | Persists the knowledge graph and detects Communities. | `persistEntities(Collection)`, `persistRelationships(Collection)`, `detectCommunities(String)` |
| `GraphStorePort` | Both of the above (`extends GraphReadPort, GraphWritePort`), with the default `detectCommunities`. | `void persistEntities(Collection<Entity> entities)`, `void persistRelationships(Collection<Relationship> relationships)` |
| `EmbeddingPort` | Turns chunk text into a dense embedding vector. `embedAll(List<String>)` (default: loops `embed`) is the batch call; override it when the model has one. | `float[] embed(String text)` |
| `VectorStorePort` | Persists and queries a corpus's embedded chunks and its fitted 2D projection model. | `void persistChunks(String corpusId, Collection<EmbeddedChunk> chunks)` |

`GraphStorePort.detectCommunities(String corpusId)` is the grouping that
`DetectCommunities` summarizes and persists. Its default is the core's
modularity-based detector (see [Communities without GDS](#communities-without-gds));
override it to plug in a native algorithm (the Neo4j adapter uses GDS Leiden
by default). It returns groups of member identities
(`Entity.normalizedIdentity()`); their order does not matter, because
`DetectCommunities` numbers Communities by each group's first member in
`entities(corpusId)`.

## Optional LLM capabilities

`LlmPort` has one abstract method, `extract(Corpus)`. It stays abstract only
so that `LlmPort` remains a functional interface: existing callers implement
it as a lambda. Every other method has a deterministic default. Four
capability flags tell the use cases whether a model is really behind a
method:

| Flag | Default | Effect when `false` |
| --- | --- | --- |
| `extractsEntities()` | `true` | `ExtractEntitiesAndRelationships` makes no extraction call; it still splits and persists the Text Units. |
| `summarizesCommunities()` | `false` | `DetectCommunities` still calls `summarizeCommunity` (an override is always honoured), but runs sequentially. |
| `derivesSubQuestions()` | `false` | DRIFT uses one deterministic sub-question per candidate Community. |
| `synthesizesAnswers()` | `false` | The `Answer*` use cases return their templated answers. |

`LlmPort.none()` is a port with every capability off. A port that only
summarizes Communities (the typical case for an exact, scanned graph)
implements `extract(Corpus)` as `return GraphExtraction.empty();`, returns
`false` from `extractsEntities()` and overrides
`summarizeCommunity(members, relationships)`.

## Importing an exact graph (no LLM extraction)

When the graph is already exact — for example classes, methods and calls
from a bytecode scan — skip extraction entirely and import it with
`ImportKnowledgeGraph`:

```java
KnowledgeGraphImport graph = KnowledgeGraphImport.of(textUnits, entities, relationships);
ImportResult result = new ImportKnowledgeGraph(graphStore, llmPort, embeddingPort)
        .run("my-repo", graph);   // ImportKnowledgeGraph.Options.defaults()
```

The import never calls `extract`. It:

1. merges Entities with the same `normalizedIdentity()` and Relationships with
   the same `(source, type, target)`, **summing** their weights (repeated
   call edges add up);
2. handles Relationship endpoints that are neither imported nor already
   stored per `Options.missingEndpoints()`: `CREATE` a placeholder Entity
   (default), `KEEP` the edge as given, or `DROP` and count it;
3. persists Text Units, then Entities, then Relationships;
4. persists caller-supplied Communities and memberships as given
   (`graph.withCommunities(...)`, for example one Community per package) and
   skips detection, or else runs `DetectCommunities` (switch off with
   `Options.withDetectCommunities(false)`; minimum size via
   `withMinCommunitySize`); a membership whose Community was not supplied
   gets a Community with the deterministic title and summary;
5. embeds Entities and Communities when the `EmbeddingPort` is semantic
   (switch off with `withEmbed(false)`).

The only model calls an import can make are Community summaries and
embeddings. `ImportResult` reports the counts, placeholders, dropped
Relationships and where the Communities came from (`SUPPLIED`, `DETECTED`,
`NONE`).

## Code and other structured sources

The domain model is not tied to prose. Entity and Relationship types are
free-form strings (`Class`, `Method`, `CALLS`, `IMPLEMENTS`, …); only text
extraction restricts them, to `EntityTypes.ALL` by default or to the list
passed to `new ExtractEntitiesAndRelationships(llm, store, entityTypes)`.

`Entity`, `Relationship`, `TextUnit`, `Citation`, `RetrievalStep` and
`Community` carry two optional fields:

- `attributes` — a free-form `Map<String, String>` (for example `kind`,
  `module`, `package`, `visibility`, `callCount`). Null keys/values and blank
  keys are dropped; the map is unmodifiable and sorted by key, so equality and
  JSON are stable. `attribute(key)` returns an `Optional`.
- `locator` — a `SourceLocator(path, startLine, endLine)`, 1-based and
  inclusive, rendered as `path:startLine-endLine` by `format()` and parsed back
  by `SourceLocator.parse(...)`. `0` lines mean "the whole source"; a null
  locator means "unknown".

The existing constructors are kept and create elements without attributes and
locator, so nothing changes for text corpora. `with(...)`, `withAttributes`
and `withLocator` derive copies that keep the other fields; merging
(`GraphElementMerger`) keeps the first locator and unites the attributes (the
first value wins).

Retrieval carries them through: every `ENTITY`, `RELATIONSHIP` and `TEXT_UNIT`
step of a trace and every `Citation` exposes the element's locator and
attributes, so an answer item can be verified at `path:startLine-endLine`.

The Neo4j adapter stores attributes as two parallel list properties
(`attributeKeys`, `attributeValues`) and a locator as `locatorPath`,
`locatorStartLine` and `locatorEndLine`. Elements persisted before these
properties existed read back with empty attributes and no locator.

## Communities without GDS

`dev.rabauer.graphrag.core.community` holds a pure-Java, deterministic
community detector, so any store gets modularity-based Communities without a
graph-algorithm plugin:

- `ModularityCommunityDetector` — Louvain local moving and aggregation with a
  Leiden-style connectivity refinement (every Community is connected).
  Weighted by `Relationship.weight()`, undirected. `Options`: `seed` (visit
  order; default 42, every seed reproducible), `resolution` (default 1.0;
  higher gives more, smaller Communities), `maxLevels`, `maxIterationsPerLevel`,
  `refineConnectivity`. `detectHierarchy(...)` returns every aggregation level,
  finest first; `modularity(...)` scores a partition.
- `ConnectedComponentsCommunityDetector` — the grouping the port used before.
- `GraphCommunities.detect(detector, entities, relationships)` runs any
  `CommunityDetector` over a graph; `GraphCommunities.defaultDetector()` is the
  one `GraphStorePort.detectCommunities` uses by default.

A detector can also be plugged in per run (`DetectCommunities.Options.withDetector`)
or per store (`new InMemoryGraphStoreAdapter(detector)`, or
`Neo4jGraphStoreOptions.withCommunityDetection(CORE)` / `withCoreDetector` for
Neo4j without the GDS plugin). With GDS Leiden, a GDS failure still fails the
detection unless `withFallbackToCoreDetector(true)` is set; the fallback logs a
warning.

To skip detection entirely, supply Communities (for example packages or
modules) through `ImportKnowledgeGraph` (see above).

### Summarizing Communities: options and result

`new DetectCommunities(store, llm, DetectCommunities.Options)` configures a run;
`run(corpusId, progress, onCommunityDetected)` returns a
`CommunityDetectionResult`. The existing constructors and `detect(...)` behave
exactly as before (sequential, unlimited, the first failure propagates).

| Option | Default | Effect |
| --- | --- | --- |
| `minCommunitySize` | 3 | Smaller groups produce no Community. |
| `detector` | the store's | Grouping to use instead of `GraphStorePort.detectCommunities`. |
| `parallelism` | 1 | Summaries in flight at once; only for a port whose `summarizesCommunities()` is true. |
| `maxSummaries` | unlimited | Port calls per run; the rest are `SKIPPED_BUDGET`. |
| `maxWallTime` | unlimited | Time budget; summaries not finished by then are `SKIPPED_BUDGET`. |
| `failurePolicy` | `FAIL_RUN` | `ISOLATE_ITEM`: a failing summary is `FAILED` (with its error) and the run continues. |
| `reuseSummaries` | off | Reuse a stored Community's summary when its content hash matches (attributes `contentHash`, `summaryStatus`). |
| `summaryMemberOrder` | stored entity order | A `Comparator<Entity>` that sorts the members before the first `maxSummaryMembers` are handed to the port. Without it a large Community is summarized from the members the store lists first. The Community keeps every member. |
| `maxSummaryMembers` | 25 | Members handed to the port per Community. |
| `hierarchyLevel` | `-1` (coarsest) | Which level of the detector's hierarchy (`CommunityDetector.detectHierarchy`; 0 = finest) becomes the Communities. |
| `maxCommunitySize` | 0 (no limit) | Larger groups are split along the finer levels, down to level 0. With a level or a size and no `detector`, the core's modularity detector is used instead of the store's own grouping. |
| `statsAttributes` | `module`, `package` | Entity attributes counted for the `CommunityStats` the port gets (`LlmPort.summarizeCommunity(members, relationships, stats)`: full size, counts by attribute). |

Every detected Community and membership is persisted in every case; one whose
summary failed or was skipped gets the deterministic title and summary, and
the result's `status` is `PARTIAL` (else `COMPLETE`, or `EMPTY` when no group
reached the minimum size). Per Community, `summaries()` says `GENERATED`,
`REUSED`, `DETERMINISTIC`, `FAILED` or `SKIPPED_BUDGET`; `count(status)` counts
them. The content hash covers the member identities and descriptions and the
internal Relationships handed to the port, so a re-run regenerates only
Communities whose content changed; a fallback summary is never reused, so it
is retried. A `ProgressListener` is told after each summary, in Community
order.

## Retrieval-only queries

For consumers that are themselves language models (a coding agent over MCP,
for example), the retrieval-only use cases return the assembled context and
the full trace **without** calling `synthesizeAnswer` or any other
`LlmPort` method (DRIFT only asks the port for sub-questions):

| Use case | Options | Picks |
| --- | --- | --- |
| `RetrieveLocalContext(graph, seedMatcher, LocalRetrievalOptions)` | `LocalRetrievalOptions` | seed Entities, then a breadth-first expansion |
| `RetrieveGlobalContext(graph, embeddingPort, memberMatcher, GlobalRetrievalOptions)` | `GlobalRetrievalOptions` | the best Communities, their best members and member Text Units |
| `RetrieveDriftContext(graph, llmPort, embeddingPort, seedMatcher, DriftRetrievalOptions)` | `DriftRetrievalOptions` | candidate Communities, one sub-question each, a Local expansion per sub-question |

Each returns a `RetrievalResult`; `toContextItems()` turns it into the
numbered context `LlmPort.synthesizeAnswer` takes, for callers that
synthesize themselves. The `Answer*` use cases are unchanged; Local Search's
answer context is now built by the same expansion engine with
`LocalRetrievalOptions.answerContext()`.

### Seed matchers

`SeedMatcher` decides which Entities a question is about
(`dev.rabauer.graphrag.core.retrieval`):

- `KeywordSeedMatcher` — the classic whole-word overlap with a small typo
  tolerance. The default when the `EmbeddingPort` is not semantic.
- `SemanticSeedMatcher` — the Entities most similar to the question
  (`GraphStorePort.similarEntities`, which a store may answer from any vector
  store); falls back to keywords via `SeedMatchers.defaultFor(embeddings)`.
- `IdentifierSeedMatcher` — code-aware: splits `camelCase`, `snake_case`,
  dots, `::`, `#` and `$`, ignores parameter lists, and matches qualified
  names, qualified suffixes (`OrderService.placeOrder`), simple class and
  method names, other name segments, near misses (one or two edits) and
  shared camel-case words, plus plain question words against name words.
  Besides the Entity name it matches the `qualifiedName`, `simpleName` and
  `signature` attributes. Package segments (the lower-case segments in front
  of the first upper-case one) are not among a name's words and score only 3
  as a token, so `BrokerService` does not match every class in a package
  called `broker`. The normalised names are indexed once per corpus: reuse one
  matcher instance across questions.
- `HybridSeedMatcher` — reciprocal-rank fusion (`1/(60 + rank)`) of any
  matchers; `SeedMatchers.forCode(embeddings)` fuses identifiers with
  semantic seeds.

**Scores are only comparable within one matcher** (identifier 0 to 100 and
more, fusion about 0.03, keyword counts), so never add raw scores of different
matchers. Compose by rank with `HybridSeedMatcher` / `SeedMatchers.fuseByRank`,
or on a common scale with `SeedMatchers.normalized(matcher)` and
`SeedMatchers.weightedSum(matchers, weights)`. `SeedMatchers.validated(matcher)`
checks the contract (finite scores, best first, at most `limit`) while you
develop a matcher.

### Reading a large corpus

The retrieval use cases read by identity (`entities(corpusId, identities)`,
`relationshipsTouching`): `RetrieveGlobalContext` reads the Communities and
memberships, then only the members of the picked Communities, and
`ImportKnowledgeGraph` looks up only the missing Relationship endpoints. Override
those two reads with indexed lookups in your store. A matcher that scores every
Entity still reads them all; wrap the store in
`new CachingGraphReadPort(store)` (call `invalidate(corpusId)` after writes, or
pass a maximum age) to read each corpus once.

### Local expansion options

`LocalRetrievalOptions.defaults()` (agent-oriented) /
`answerContext()` (exactly Local Search's synthesis context):

| Option | `defaults()` | Meaning |
| --- | --- | --- |
| `seedLimit` | 3 | Seeds asked from the matcher. |
| `maxHops` | 1 | Breadth-first depth; 0 = seeds only. |
| `maxNodes` | 50 | Entities, seeds included. |
| `maxRelationships` | 25 | Relationships. |
| `maxTextUnits` | 10 | Text Units (snippets). |
| `maxItems` | 100 | Items overall. |
| `includeRelationshipTypes` / `excludeRelationshipTypes` | all / none | Case-insensitive type filters (e.g. only `CALLS`). |
| `minWeight` | 1 | Weight threshold (e.g. call counts). |
| `ordering` | `WEIGHT_DESC` | `WEIGHT_DESC`, `WEIGHT_ASC`, `STORED` or `NEW_NODES_FIRST` (Relationships that reach a not-yet-included Entity first); ties keep stored order. |
| `maxRelationshipsPerHop` | unlimited | The most Relationships one hop adds, so heavy edges among the seeds cannot use the global cap up before a new Entity is reached. |
| `maxNewNodesPerHop` | unlimited | The most Entities one hop may newly reach; keeps a hub from taking over a hop. |
| `relationshipComparator` | none | A `Comparator<Relationship>` that orders every hop's candidates instead of `ordering`. |
| `direction` | `BOTH` | `OUTGOING` (what it calls) or `INCOMING` (its callers). |
| `includeNeighborEntities` | true | Reached Entities become items (with locators). |
| `includeMemberTextUnits` | true | Every included Entity's own Text Units are ranked, not only the seeds'. |

Text Units are ranked by +1 per seed citing them, +1 per other included
Entity citing them (with `includeMemberTextUnits`) and +weight per included
Relationship citing them; a missing Text Unit is skipped.

### Trace schema

`RetrievalResult` and everything in it are plain records (no annotations);
Jackson 2 and 3 write and read them as-is. Example (Local, abridged):

```json
{
  "mode": "LOCAL",
  "question": "Who calls placeOrder?",
  "corpusId": "shop",
  "status": "MATCHED",
  "reason": "",
  "items": [
    {
      "number": 1,
      "kind": "ENTITY",
      "identifier": "com.shop.order.orderservice#placeorder(order)::method",
      "label": "com.shop.order.OrderService#placeOrder(Order)",
      "text": "com.shop.order.OrderService#placeOrder(Order) (Method)",
      "locator": { "path": "src/main/java/com/shop/order/OrderService.java", "startLine": 20, "endLine": 35 },
      "attributes": { "kind": "method" },
      "score": 95.0,
      "hop": 0
    },
    {
      "number": 2,
      "kind": "RELATIONSHIP",
      "identifier": "com.shop.web.ordercontroller#create(orderrequest)::method->CALLS->com.shop.order.orderservice#placeorder(order)::method",
      "label": "com.shop.web.OrderController#create(OrderRequest) —CALLS→ com.shop.order.OrderService#placeOrder(Order)",
      "text": "com.shop.web.OrderController#create(OrderRequest) -[CALLS]-> com.shop.order.OrderService#placeOrder(Order)",
      "locator": { "path": "src/main/java/com/shop/web/OrderController.java", "startLine": 22, "endLine": 22 },
      "attributes": {},
      "score": 3.0,
      "hop": 1
    }
  ],
  "trace": {
    "traceId": "",
    "steps": [
      { "kind": "ENTITY", "identifier": "…", "label": "…", "locator": { "…": "…" }, "attributes": { "kind": "method" } }
    ]
  },
  "warnings": []
}
```

| Field | Meaning |
| --- | --- |
| `mode` | `LOCAL`, `GLOBAL` or `DRIFT`. |
| `status` | `MATCHED`; `NO_MATCH` (nothing matched, `reason` says why); `NO_COMMUNITIES` (Global/DRIFT on a corpus without Communities). |
| `items[].number` | 1-based, in touch order; the trace lists the same elements in the same order (DRIFT adds `SUB_QUESTION_SPAWNED` steps that are not items, and may repeat a step in several branches while items are de-duplicated). |
| `items[].kind` | `ENTITY`, `RELATIONSHIP`, `TEXT_UNIT` or `COMMUNITY`. |
| `items[].identifier` | Entity: `name::type` lower-cased (`Entity.normalizedIdentity()`). Relationship: `sourceIdentity->TYPE->targetIdentity`. Text Unit: its id. Community: its id. |
| `items[].label` / `text` | Short label (name, edge, excerpt of 200 characters, title) / full text (`name (type): description`, `source -[TYPE]-> target: description`, the whole passage, `title: summary`). |
| `items[].locator` | `{path, startLine, endLine}` (1-based, inclusive; 0 = whole file) or `null`; `SourceLocator.format()` renders `path:start-end`. |
| `items[].attributes` | The element's attributes, keys sorted. |
| `items[].score` | Seed score, Relationship weight, Text Unit rank score or Community score; comparable within one kind of one result. |
| `items[].hop` | 0 for seeds, member Entities and Communities, the expansion depth for reached elements, -1 for Text Units. |
| `trace.steps[]` | `RetrievalStep(kind, identifier, label, locator, attributes)`, also `SUB_QUESTION_SPAWNED` (identifier: the Community id, label: the sub-question). |
| `warnings` | Visible, non-fatal problems, e.g. a failed sub-question derivation that fell back. |

New fields are additive: a consumer that ignores unknown fields keeps
working when later versions add some.

## Read port, write port

`GraphStorePort` is split into `GraphReadPort` (Text Units, Entities,
Relationships, Communities, memberships, similarity lookups) and
`GraphWritePort` (persist, retype, embeddings, detect). `GraphStorePort`
extends both, so existing implementations and callers are unchanged.

Every query use case — `AnswerLocalSearch`, `AnswerGlobalSearch`,
`AnswerDriftSearch`, `CompareAnswers`, `CompareAllModes`, the `Retrieve*`
use cases and the seed matchers — takes only a `GraphReadPort`. An
application with its own graph can implement just the read side over it.
Three read methods exist for large graphs and have filtering defaults worth
overriding with indexed lookups: `entity(corpusId, identity)`,
`entities(corpusId, identities)` and `relationshipsTouching(corpusId,
identities)`; Local expansion calls the last two once per hop instead of
reading the whole graph.

## Small local models

Small models (for example Ollama `llama3.2`) often wrap JSON in prose, add
trailing commas or run into the token limit. Three pieces keep them usable,
with failures still visible:

1. **`LenientJson`** (`dev.rabauer.graphrag.core.llm`, JDK only) finds the
   first JSON object in a reply and repairs it: markdown fences and prose
   around it, trailing or missing commas, single quotes, unquoted keys, and
   JSON cut off mid-string are tolerated; `Result.repaired()` tells whether
   anything was fixed. `extractObjectText` hands strict JSON to an adapter's
   own JSON library.
2. **`PromptedLlmPort`** — an abstract `LlmPort` with the prompts, the JSON
   Schemas (`PromptedLlmPort.Schemas`) and the parsing for Community
   summaries, DRIFT sub-questions, the GraphRAG-vs-Vector verdict and
   (opt-in) extraction and answer synthesis. Implement only
   `complete(CompletionRequest)`:

   ```java
   class OllamaLlmPort extends PromptedLlmPort {
       private final ChatClient chat;   // e.g. Spring AI
       OllamaLlmPort(ChatClient chat) { this.chat = chat; }

       @Override
       protected String complete(CompletionRequest request) {
           // request.messages(): the prompt (and, on the retry or a gleaning turn, the earlier turns)
           // request.jsonSchema(): pass to Ollama's `format` for schema-constrained output
           return callOllama(request.messages(), request.jsonSchema(), request.maxOutputTokens());
       }
   }
   ```

   An unusable reply (no object, a required field empty) is asked once more
   with the bad reply and a corrective message; if that fails too, the item
   fails with an `LlmReplyException` (purpose, attempts, last reply). A
   failure of `complete` itself propagates unchanged and is never retried.
   Fewer DRIFT sub-questions than candidates are filled with the
   deterministic ones. `Options`: `extraction`, `synthesis` (both off),
   `correctiveRetry` (on), output-token limits, and `gleanings` (0). With
   `withGleanings(1)`, extraction asks once more, in the same conversation,
   for the Entities and Relationships the first reply missed. This improves
   recall, especially for small models, at the cost of one more call per
   Text Unit.
3. **Per-item failure isolation** (`FailurePolicy.ISOLATE_ITEM`): a failed
   Community summary becomes `FAILED` with its error and the deterministic
   summary (`DetectCommunities`); a failed Text Unit extraction is recorded in
   the `ExtractionReport` of `ExtractEntitiesAndRelationships.runWithReport`
   and persisted without Entities; a failed sub-question derivation in
   `RetrieveDriftContext` falls back with a warning. The default everywhere
   except `RetrieveDriftContext` stays `FAIL_RUN`.

`OpenAiLlmPort` parses its replies leniently too and offers the corrective
retry behind `new OpenAiLlmPort(apiKey, model, true)` (off by default). The
deterministic offline stand-ins (`LlmPort.none()`, `LangChain4jLlmPort`,
`LangChain4jEmbeddingPort`) are unchanged.

## Incremental updates

`UpdateSources` re-indexes per source (a file or document) instead of the
whole corpus. A graph element's source (`Sources.of(...)`) is its `source`
attribute, else its locator's path, else (Text Units) its document name.

```java
UpdateSources updates = new UpdateSources(graphStore, vectorStoreOrNull, llmPort, embeddingPort);
updates.replaceSource("repo", "src/main/java/com/shop/OrderService.java", newGraphOfThatFile, null);
// ... more changed files ...
updates.recomputeCommunities("repo");   // once per batch
```

`removeBySource(corpusId, sourceId)` deletes the source's Text Units; its
Entities and the Entities only its Text Units cited; its Relationships, the
ones touching a removed Entity and the ones only its Text Units cited;
orphans (Entities this removal disconnected that have no source, no Text
Units and no Relationship left, e.g. placeholders for external types); and,
with a `VectorStorePort`, the source's chunks. Kept elements that also cited
a removed Text Unit are re-persisted without it. It returns a
`SourceRemoval` with the counts, the removed identities and the stale
Community ids. `replaceSource` = remove + `ImportKnowledgeGraph` (detection
off by default).

What becomes stale:

| Derived data | After a removal | Recompute with |
| --- | --- | --- |
| Entity embeddings | deleted with their Entities; new Entities are embedded by the import | — |
| Community memberships | memberships of deleted Entities are gone; new Entities have none | `recomputeCommunities` |
| Community summaries, content hashes | stale for every Community in `staleCommunityIds` | `recomputeCommunities` (with summary reuse: unchanged Communities keep their summary, no model call) |
| Community embeddings | stale | `recomputeCommunities` (re-embeds with a semantic port) |
| Vector index projection model | kept as is | a full `ConstructVectorIndex` |

`DetectCommunities.recompute(corpusId)` (used by `recomputeCommunities`)
reads the reusable summaries, deletes every Community of the corpus, then
detects afresh, so no stale Community or membership survives.

The write port's delete methods (`deleteTextUnits`, `deleteEntities` —
cascading to the Entity's Relationships, memberships and embedding —,
`deleteRelationships`, `deleteCommunities`) and
`VectorStorePort.deleteChunksOf` throw `UnsupportedOperationException` by
default, so a store without them fails loudly. The in-memory, testkit and
Neo4j stores implement them.

## Neo4j: sharing a database, injected sessions

`Neo4jGraphStoreAdapter` can live next to another tool's graph in the same
database:

```java
Neo4jGraphStoreAdapter store = new Neo4jGraphStoreAdapter(
        () -> driver.session(SessionConfig.forDatabase("neo4j")),   // the caller owns the driver
        Neo4jGraphStoreOptions.defaults()
                .withPrefixes("GraphRag", "GRAPHRAG_")                // :GraphRagEntity, :GRAPHRAG_RELATIONSHIP
                .withCommunityDetection(Neo4jGraphStoreOptions.CommunityDetection.CORE)); // no GDS plugin
```

- `withPrefixes(labelPrefix, relationshipTypePrefix)`: every label
  (`Entity`, `Community`, `TextUnit`) and relationship type
  (`RELATIONSHIP`, `BELONGS_TO`, `MENTIONED_IN`) is prefixed; constraint,
  vector index and GDS projection names get the lower-cased label prefix
  (`graphrag_entity_embedding`). Prefixes must be letters, digits and `_`,
  starting with a letter. Nothing the adapter runs matches an unprefixed
  label, so a foreign `:Entity`, `:Java:Type` or `INVOKES` graph is never
  read, changed or deleted.
- Every node and relationship is also keyed by `corpusId`.
- `new Neo4jGraphStoreAdapter(Supplier<Session>, options)`: the adapter opens
  and closes one session per operation and never closes the driver.
  `withCreateConstraints(false)` skips declaring constraints when the schema
  is managed elsewhere.
- `entity`, `entities(corpusId, identities)` and `relationshipsTouching` are
  indexed queries.

`Neo4jVectorStoreAdapter` (the plain vector-RAG baseline's `:Chunk` nodes) is
not namespaced.

## Testkit: proving an adapter correct

`dev.rabauer.graphrag:graphrag-core-testkit` (on Maven Central, released
together with and versioned like `graphrag-core`; use it with test scope)
ships JUnit 5 contract tests an adapter extends, plus fixtures:

```xml
<dependency>
    <groupId>dev.rabauer.graphrag</groupId>
    <artifactId>graphrag-core-testkit</artifactId>
    <version>2.0.1</version>
    <scope>test</scope>
</dependency>
```

| Class | Extend it with | Checks |
| --- | --- | --- |
| `GraphReadPortContract` | `givenGraph(ContractGraph)`: load the fixture your way, return your read port | corpus scoping, fields, attributes and locators of Entities/Relationships/Text Units/Communities, stable reads, empty-not-null for unknown corpora, Text Unit and Entity lookups, `relationshipsTouching`, bounded similarity lookups |
| `GraphStorePortContract` | `newStore()` | the read contract with the fixture written through your write side, plus: re-persisting replaces, `detectCommunities` covers every Entity once, embeddings make similarity lookups correct (`supportsSimilarity()` hook), deletes cascade and `UpdateSources.removeBySource` removes a file (`supportsDeletion()` hook) |
| `VectorStorePortContract` | `newStore()` | chunks and embeddings round-trip per corpus, re-persisting replaces, deleting a document's chunks keeps the rest (`supportsDeletion()` hook), the projection model round-trips (`supportsProjectionModel()` hook) |
| `EmbeddingPortContract` | `port()` | finite, non-empty vectors of one dimension (blank text included), determinism (`deterministic()` hook), and for a semantic port nearby meanings closer than unrelated ones (`semanticProbe()` hook) |
| `CodeGraphRetrievalContract` | `newStore()` | the whole non-text path on your store: import `CodeGraphFixture` (50 classes), detect Communities with the core detector, retrieval-only Local and Global for a camelCase question, with locators |

`ContractGraph` is the small fixture (unique corpus ids per test, so a shared
database is fine; every contract vector has `VECTOR_DIMENSIONS` = 3
dimensions); `CodeGraphFixture` the 50-class code graph;
`InMemoryGraphStore` a complete reference `GraphStorePort` for application
tests. Example:

```java
class MyNeo4jReadPortTest extends GraphReadPortContract {
    @Override
    protected GraphReadPort givenGraph(ContractGraph graph) {
        writeIntoMySchema(graph);              // e.g. :Java:Type nodes, INVOKES edges
        return new MyGraphReadPort(driver);
    }
}
```

In this repository the contracts run against `InMemoryGraphStoreAdapter`,
`InMemoryVectorStoreAdapter`, `Neo4jGraphStoreAdapter` (plain Neo4j, core
detector), `Neo4jVectorStoreAdapter`, `LangChain4jEmbeddingPort` and the
testkit's own `InMemoryGraphStore`; `CodeGraphExampleTest` in the testkit
walks through the code-graph path step by step.

## Usage: wiring the ports and running the pipeline

The use cases in `dev.rabauer.graphrag.core.usecase` are called in this order: ingest
the corpus, extract entities/relationships into the graph, then detect
communities and build the vector index (either order, both depend only on
the graph/corpus), and finally answer a question.

The example below wires each port with the simplest possible in-memory
implementation, then runs the full pipeline end to end. It is modeled on
`IngestCorpusTest`, the simplest existing test in this module, and compiles
against the current API.

```java
import dev.rabauer.graphrag.core.domain.*;
import dev.rabauer.graphrag.core.port.*;
import dev.rabauer.graphrag.core.usecase.*;

import java.util.*;

class GraphRagLensQuickstart {

    public static void main(String[] args) {
        // 1. Wire the ports. In a real application these would be adapters
        //    (e.g. graphrag-adapter-neo4j, graphrag-adapter-langchain4j) —
        //    here they are minimal in-memory/test-doubles for illustration.
        DocumentParserPort txtParser = filename -> filename != null && filename.endsWith(".txt");

        LlmPort llmPort = corpus -> new GraphExtraction(
                List.of(new Entity("Ada Lovelace", "Person")),
                List.of());

        List<Entity> persistedEntities = new ArrayList<>();
        List<Relationship> persistedRelationships = new ArrayList<>();
        GraphStorePort graphStorePort = new GraphStorePort() {
            @Override
            public void persistEntities(Collection<Entity> entities) {
                persistedEntities.addAll(entities);
            }

            @Override
            public void persistRelationships(Collection<Relationship> relationships) {
                persistedRelationships.addAll(relationships);
            }

            @Override
            public Collection<Entity> entities(String corpusId) {
                return persistedEntities;
            }

            @Override
            public Collection<Relationship> relationships(String corpusId) {
                return persistedRelationships;
            }
        };

        EmbeddingPort embeddingPort = text -> new float[]{text.length(), 0f};
        VectorStorePort vectorStorePort = (corpusId, chunks) -> { /* no-op store */ };

        // 2. Ingest an uploaded corpus.
        IngestCorpus ingestCorpus = new IngestCorpus(List.of(txtParser));
        UploadedDocument document = new UploadedDocument("bio.txt",
                "Ada Lovelace worked on the Analytical Engine.");
        Corpus corpus = ingestCorpus.ingest(List.of(document));

        // 3. Extract entities/relationships and persist them into the graph.
        ExtractEntitiesAndRelationships extract =
                new ExtractEntitiesAndRelationships(llmPort, graphStorePort);
        extract.run(corpus);

        // 4. Detect communities from the persisted graph.
        DetectCommunities detectCommunities = new DetectCommunities(graphStorePort);
        detectCommunities.run(corpus);

        // 5. Chunk, embed, and index the corpus's documents.
        ConstructVectorIndex constructVectorIndex =
                new ConstructVectorIndex(embeddingPort, vectorStorePort);
        constructVectorIndex.run(corpus);

        // 6. Answer a question by traversing the persisted graph. If no
        //    matching entity is found, the answer is LocalSearchAnswer.noMatch().
        AnswerLocalSearch answerLocalSearch = new AnswerLocalSearch(graphStorePort);
        LocalSearchAnswer answer = answerLocalSearch.answer("Ada Lovelace", corpus.id());
        System.out.println(answer.answer());
    }
}
```

`IngestCorpus.ingest(...)` throws `UnsupportedFileTypeException` if none of the
wired `DocumentParserPort`s support a given document's filename, and
`UnreadableDocumentException` if a document that is supported cannot actually
be parsed.

## Testing

```
mvn test -pl graphrag-core -am
```

This runs this module's unit tests (and those of `graphrag-core` itself, via
`-am`) without requiring a running Neo4j instance or an LLM/embedding API
key — every port in the tests above is exercised through a plain in-memory
implementation, not a real adapter.

## Package layout

- `dev.rabauer.graphrag.core.domain` — the data carried between use cases and ports
  (corpora, chunks, entities, relationships, communities, retrieval traces,
  `SourceLocator`, `Attributes`, `Sources`).
- `dev.rabauer.graphrag.core.port` — the SPI a consumer implements (see the table
  above).
- `dev.rabauer.graphrag.core.usecase` — the actual operations, called in the pipeline
  order shown above, plus import, retrieval-only queries and incremental updates.
- `dev.rabauer.graphrag.core.retrieval` — seed matchers, retrieval options and the
  `RetrievalResult` / `RetrievedItem` records.
- `dev.rabauer.graphrag.core.community` — the pure-Java community detectors.
- `dev.rabauer.graphrag.core.llm` — `LenientJson` and `PromptedLlmPort` for small models.

The companion artifact `graphrag-core-testkit` (package `dev.rabauer.graphrag.testkit`)
holds the port contracts, the fixtures and `InMemoryGraphStore`.
