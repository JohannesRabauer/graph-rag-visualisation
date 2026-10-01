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
    <groupId>io.graphrag</groupId>
    <artifactId>graphrag-core</artifactId>
    <version>1.0.0</version>
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

A consumer implements these five interfaces (all in `io.graphrag.core.port`)
to plug in its own technology choices. Every default method already has a
usable fallback; only the abstract methods listed below are required.

| Port | Purpose | Abstract method(s) you implement |
| --- | --- | --- |
| `DocumentParserPort` | Parses source documents (e.g. PDF, text) during ingestion. | `boolean supports(String filename)` |
| `LlmPort` | LLM-driven knowledge-graph extraction and related generation. | `GraphExtraction extract(Corpus corpus)` |
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

## Usage: wiring the ports and running the pipeline

The use cases in `io.graphrag.core.usecase` are called in this order: ingest
the corpus, extract entities/relationships into the graph, then detect
communities and build the vector index (either order, both depend only on
the graph/corpus), and finally answer a question.

The example below wires each port with the simplest possible in-memory
implementation, then runs the full pipeline end to end. It is modeled on
`IngestCorpusTest`, the simplest existing test in this module, and compiles
against the current API.

```java
import io.graphrag.core.domain.*;
import io.graphrag.core.port.*;
import io.graphrag.core.usecase.*;

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

- `io.graphrag.core.domain` — the data carried between use cases and ports
  (corpora, chunks, entities, relationships, communities, retrieval traces).
- `io.graphrag.core.port` — the SPI a consumer implements (see the table
  above).
- `io.graphrag.core.usecase` — the actual operations, called in the pipeline
  order shown above.
