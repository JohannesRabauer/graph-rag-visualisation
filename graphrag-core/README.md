# GraphRAG Lens :: Core

`graphrag-core` is the framework-free hexagonal core of GraphRAG Lens: the
domain model and the port interfaces (the SPI) that a consuming application
implements and wires together, plus the use cases that orchestrate them. It
carries no dependency on any web framework, graph-database driver, or
LLM-orchestration library (AD-1) — nothing in this module imports Spring, the
Neo4j Java Driver, or LangChain4j, and the build enforces that ban.

## Requirements

- **JDK 25** — this module (like the rest of the reactor) is compiled and
  tested against JDK 25. Point `JAVA_HOME`/`PATH` at a JDK 25 install before
  building.
- Apache Maven 3.9+

## Maven coordinates

```xml
<dependency>
    <groupId>dev.rabauer.graphrag</groupId>
    <artifactId>graphrag-core</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

**This artifact is not yet published to any Maven registry.** There is no
Maven Central, GitHub Packages, or other remote coordinate you can point at
today. To consume it, clone this repository and install it into your local
Maven repository:

```
mvn install -pl graphrag-core -am
```

## The ports

A consumer implements these five interfaces (all in `dev.rabauer.graphrag.core.port`)
to plug in its own technology choices. Every default method already has a
usable fallback; only the abstract methods listed below are required.

| Port | Purpose | Abstract method(s) you implement |
| --- | --- | --- |
| `DocumentParserPort` | Parses source documents (e.g. PDF, text) during ingestion. | `boolean supports(String filename)` |
| `LlmPort` | LLM-driven knowledge-graph extraction and related generation. Every capability is optional (see [Optional LLM capabilities](#optional-llm-capabilities)). | `GraphExtraction extract(Corpus corpus)` (a port without extraction returns `GraphExtraction.empty()`) |
| `GraphStorePort` | Persists and queries the knowledge graph. | `void persistEntities(Collection<Entity> entities)`, `void persistRelationships(Collection<Relationship> relationships)` |
| `EmbeddingPort` | Turns chunk text into a dense embedding vector. | `float[] embed(String text)` |
| `VectorStorePort` | Persists and queries a corpus's embedded chunks and its fitted 2D projection model. | `void persistChunks(String corpusId, Collection<EmbeddedChunk> chunks)` |

`GraphStorePort.detectCommunities(String corpusId)` is the grouping that
`DetectCommunities` summarizes and persists. Its default is connected
components over `entities(corpusId)`/`relationships(corpusId)`; override it to
plug in a modularity-based algorithm (the Neo4j adapter uses GDS Leiden). It
returns groups of member identities (`Entity.normalizedIdentity()`); their
order does not matter, because `DetectCommunities` numbers Communities by each
group's first member in `entities(corpusId)`.

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
  (corpora, chunks, entities, relationships, communities, retrieval traces).
- `dev.rabauer.graphrag.core.port` — the SPI a consumer implements (see the table
  above).
- `dev.rabauer.graphrag.core.usecase` — the actual operations, called in the pipeline
  order shown above.
