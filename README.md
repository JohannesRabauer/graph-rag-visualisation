# GraphRAG Lens

GraphRAG Lens is a teaching and demo tool that shows **how GraphRAG works** rather than only what it answers.

**Website:** <https://johannesrabauer.github.io/graph-rag-visualisation/> — an illustrated walk through the whole application, built from the `docs/` folder and published by the [Website workflow](.github/workflows/pages.yml) on every push to `main`.

**Video:** <https://youtube.com/live/fWOptSc7wpk>

[![GraphRAG Lens video](https://img.youtube.com/vi/fWOptSc7wpk/maxresdefault.jpg)](https://youtube.com/live/fWOptSc7wpk)

You upload a corpus (plain text or PDF), watch it turn into a knowledge graph passage by passage, see communities form, and ask questions in three GraphRAG modes: **Local**, **Global** and **DRIFT**. Each answer is LLM-written and cites the exact source passages it used. A step-by-step **Retrieval Trace Replay** shows on the graph which entities, relationships, communities and passages each answer touched. A **Compare** view sets every GraphRAG answer against a plain vector-RAG baseline and explains where each approach does better, and why. A **four-way comparison** runs Local, Global, DRIFT and Vector Search on the same question and lines up their answers, traversal steps, latency (retrieval, embedding and LLM time) and evidence, so a wrong answer can be traced to retrieval, ranking or generation.

The heart of the project is [`graphrag-core`](graphrag-core/README.md), a framework-free GraphRAG library behind a hexagonal (ports and adapters) boundary. The web app and the adapters are one way of plugging it in.

---

## Contents

- [Quick start](#quick-start)
- [System architecture](#system-architecture)
- [The core: `graphrag-core`](#the-core-graphrag-core)
  - [Hexagonal shape](#hexagonal-shape)
  - [Ports](#ports)
  - [Use cases](#use-cases)
  - [Ingestion pipeline](#ingestion-pipeline)
  - [Answering a question](#answering-a-question)
  - [Grounding and citations](#grounding-and-citations)
  - [Domain model](#domain-model)
- [Persistence model (Neo4j)](#persistence-model-neo4j)
- [Live vs offline behaviour](#live-vs-offline-behaviour)
- [Web application](#web-application)
- [Build and test](#build-and-test)
- [Running the app](#running-the-app)
- [Definition of done](#definition-of-done)

---

## Quick start

```bash
OPENAI_API_KEY=sk-... docker compose up
```

Open <http://localhost:8080>. You can upload a `.txt` or `.pdf` corpus, load the built-in demo dataset, or use the **Offline Demo**, which makes no API calls. Without an `OPENAI_API_KEY`, the app runs fully offline with deterministic stand-ins for the LLM and the embeddings (see [Live vs offline behaviour](#live-vs-offline-behaviour)).

---

## System architecture

The system is two containers, the Spring Boot app and Neo4j (with the Graph Data Science plugin), plus the OpenAI API when a key is configured.

```mermaid
flowchart LR
    subgraph Browser["Browser — plain JavaScript, no build step"]
        UI["upload.js<br/>chat · corpus history · compare view"]
        GC["graph-canvas.js<br/>Cytoscape knowledge graph"]
        RP["replay.js · trace-pane.js · drift-tree.js<br/>Retrieval Trace Replay"]
        HP["help.js<br/>in-app explanations"]
    end

    subgraph App["graphrag-web — Spring Boot"]
        CC["CorpusController<br/>REST API"]
        SSE["CorpusProgressService<br/>SSE progress stream"]
        RTS["RetrievalTraceStore<br/>in-memory traces"]
        CFG["ParserConfig<br/>wires ports to adapters"]
    end

    subgraph Core["graphrag-core — framework-free"]
        UC["Use cases"]
        P["Ports"]
    end

    subgraph Adapters["Adapters"]
        N4J["graphrag-adapter-neo4j"]
        L4J["graphrag-adapter-langchain4j"]
        PRS["graphrag-adapter-parsing"]
    end

    DB[("Neo4j 2026.x<br/>+ GDS")]
    OAI(["OpenAI API<br/>chat + embeddings"])

    UI -- "fetch /api/…" --> CC
    UI -- "EventSource /progress" --> SSE
    CC --> UC
    CC --> RTS
    UC --> P
    CFG -. "wires adapters to" .-> P
    N4J -. "implements" .-> P
    L4J -. "implements" .-> P
    PRS -. "implements" .-> P
    N4J --> DB
    L4J --> OAI
```

### Modules

```mermaid
flowchart BT
    core["graphrag-core<br/><i>domain · ports · use cases</i>"]
    neo["graphrag-adapter-neo4j"]
    lc["graphrag-adapter-langchain4j"]
    parse["graphrag-adapter-parsing"]
    web["graphrag-web"]
    neo --> core
    lc --> core
    parse --> core
    web --> neo
    web --> lc
    web --> parse
    web --> core
```

| Module | Purpose |
| --- | --- |
| [`graphrag-core`](graphrag-core/README.md) | The GraphRAG library: domain model, port interfaces and use cases. It is **framework-free**: a Maven Enforcer rule bans Spring, the Neo4j driver and LangChain4j, so the core can be reused in any Java application. It is versioned and released independently (`dev.rabauer.graphrag:graphrag-core`). |
| `graphrag-adapter-neo4j` | `GraphStorePort` and `VectorStorePort` on Neo4j through the plain Java driver. It provides corpus-scoped writes, GDS Leiden community detection, vector indexes for semantic matching, and the durable corpus registry. It also has in-memory variants for tests. |
| `graphrag-adapter-langchain4j` | `LlmPort` and `EmbeddingPort` on OpenAI through LangChain4j (JSON-mode prompts, no retries). It also contains the deterministic offline stand-ins `LangChain4jLlmPort` and `LangChain4jEmbeddingPort`. |
| `graphrag-adapter-parsing` | `DocumentParserPort` for plain text and PDF (PDFBox). |
| `graphrag-web` | The Spring Boot app: REST and SSE endpoints, wiring, and the plain-JS frontend (Thymeleaf template, Cytoscape from a CDN, no Node/npm tooling). |

---

## The core: `graphrag-core`

### Hexagonal shape

All GraphRAG logic sits in the centre: splitting text into passages, extraction, entity resolution, community detection, semantic matching, context assembly, answer synthesis and citations. Everything the core needs from the outside world goes through one of five **ports**. Adapters implement the ports; the core never imports them.

```mermaid
flowchart TB
    subgraph Hexagon["graphrag-core — framework-free"]
        direction TB
        subgraph Ingest["Ingestion use cases"]
            direction LR
            I["IngestCorpus"] --> X["ExtractEntities<br/>AndRelationships"] --> D["DetectCommunities"] --> E["EmbedGraph<br/>Elements"]
            V["ConstructVectorIndex"]
        end
        subgraph Query["Query use cases"]
            direction LR
            L["AnswerLocalSearch"]
            G["AnswerGlobalSearch"]
            R["AnswerDriftSearch"]
            B["AnswerVectorBaseline"]
            C["CompareAnswers"]
            CA["CompareAllModes"]
        end
        subgraph Support["Building blocks"]
            direction LR
            TS["TextUnitSplitter"]
            ER["EntityResolver ·<br/>GraphElementMerger"]
            LCA["LocalContextAssembler"]
            CR["CitationResolver"]
            KM["KeywordMatcher"]
        end
        Domain[("Domain records<br/>Corpus · TextUnit · Entity · Relationship ·<br/>Community · Chunk · RetrievalStep · Citation")]
        Ingest --> Support
        Query --> Support
        Support --> Domain
    end

    subgraph Ports["Ports (dev.rabauer.graphrag.core.port)"]
        direction LR
        DP{{"DocumentParserPort"}}
        LP{{"LlmPort"}}
        EP{{"EmbeddingPort"}}
        GP{{"GraphStorePort"}}
        VP{{"VectorStorePort"}}
    end

    Hexagon --> Ports

    subgraph Adapters["Adapters"]
        direction LR
        PRS["graphrag-adapter-parsing<br/>Text · PDFBox"]
        L4J["graphrag-adapter-langchain4j<br/>OpenAI · offline stubs"]
        N4J["graphrag-adapter-neo4j<br/>Neo4j · GDS · in-memory"]
    end

    DP -. "implemented by" .-> PRS
    LP -. "implemented by" .-> L4J
    EP -. "implemented by" .-> L4J
    GP -. "implemented by" .-> N4J
    VP -. "implemented by" .-> N4J
```

Design rules that keep the core reusable:

- **Only the abstract methods are required.** Every port has a small set of abstract methods. Everything else is a `default` method with a sensible fallback, so a minimal adapter works out of the box and a richer one overrides what it can do natively. For example, `GraphStorePort.detectCommunities` defaults to connected components, and the Neo4j adapter overrides it with GDS Leiden.
- **Every read and write is scoped to a `corpusId`.** Many corpora live side by side in one store.
- **Failures are visible, never silent.** LLM and embedding errors propagate, and nothing is retried automatically. The app turns them into a FAILED corpus status or an `{"error": …}` response.
- **Capabilities are explicit.** `LlmPort.synthesizesAnswers()` and `EmbeddingPort.isSemantic()` tell the use cases whether a real model is behind the port. When there isn't one, they fall back to deterministic behaviour.

### Ports

| Port | Responsibility | Required methods | Notable optional capabilities |
| --- | --- | --- | --- |
| `DocumentParserPort` | Turn an uploaded file into text | `supports(filename)` | `extract(filename, bytes)` |
| `LlmPort` | Everything a language model does | `extract(Corpus)` | `extract(TextUnit, entityTypes)`, `summarizeCommunity(members, relationships)` → title + summary, `deriveDriftSubQuestions`, `synthesizeAnswer(question, context)` with `[n]` citations, `compareAnswers` → verdict |
| `EmbeddingPort` | Text → dense vector | `embed(text)` | `isSemantic()` |
| `GraphStorePort` | The knowledge graph | `persistEntities`, `persistRelationships` | Corpus-scoped CRUD for entities, relationships, Text Units, communities and memberships; `detectCommunities(corpusId)`; `persistEntityEmbeddings` / `similarEntities` and their community counterparts for semantic matching |
| `VectorStorePort` | The plain vector-RAG baseline | `persistChunks` | `chunks`, `persistProjectionModel` / `projectionModel` for a 2-D embedding projection (no longer shown in the UI) |

### Use cases

| Use case | What it does |
| --- | --- |
| `IngestCorpus` | Validates uploads and builds a `Corpus` from parsed documents. |
| `ExtractEntitiesAndRelationships` (`BuildKnowledgeGraph`) | Splits the corpus into overlapping **Text Units** (about 6,000 characters each, with about 600 characters of overlap). It asks the LLM for entities and relationships **one passage at a time**, resolves duplicates (`EntityResolver`), merges descriptions and provenance (`GraphElementMerger`), and persists after every passage, so the graph grows live. |
| `DetectCommunities` | Groups entities with `GraphStorePort.detectCommunities` (GDS Leiden on Neo4j; the core's pure-Java modularity detector by default) and keeps groups of **at least 3** members (`MIN_COMMUNITY_SIZE`). It asks the LLM for a title and summary written from the members' descriptions and internal relationships. Options add bounded parallelism, a per-run budget, per-item failure isolation and summary reuse. |
| `ImportKnowledgeGraph` | Imports an exact, pre-built graph (for example from a code scan) without any extraction call, then detects Communities (or takes the caller's) and embeds. |
| `EmbedGraphElements` | When the embedding model is semantic, embeds every entity (`name: description`) and community summary for meaning-based seed matching. |
| `ConstructVectorIndex` | Builds the vector baseline: 500-character chunks, their embeddings, and a fitted 2-D projection. |
| `AnswerLocalSearch` | Seeds on the entities closest to the question and walks their one-hop neighbourhood into the Text Units it cites. |
| `AnswerGlobalSearch` | Answers from the top community summaries and each community's most relevant passages. |
| `AnswerDriftSearch` | Starts from communities, spawns sub-questions, gathers Local-style context for each branch, and synthesizes once over the union. |
| `AnswerVectorBaseline` | Plain vector RAG: top-5 chunks by cosine similarity, synthesized and cited the same way. It also returns the similarity ranking (the top 12 scored chunks, the top 5 marked as used) and how many chunks it scored. |
| `CompareAnswers` | Runs a GraphRAG mode and the vector baseline fresh, then measures context, documents, overlap and latency, and produces a verdict. |
| `CompareAllModes` | Runs all four methods (Local, Global, DRIFT, Vector Search) one after the other on the same question. Per method: the answer, citations, traversal steps with a count per kind, and the time split into retrieval, embedding and LLM (`StageTiming`, measured by wrapping the ports). Across methods: an evidence table saying how far each passage got in each method (not retrieved, ranked below the vector cut-off, in context, cited). A failing method is reported, not thrown. |

### Ingestion pipeline

Ingestion runs asynchronously after an upload. Every step reports progress on the SSE stream, so the browser draws the graph while it is being built.

```mermaid
sequenceDiagram
    autonumber
    actor U as Browser
    participant W as CorpusController
    participant S as SSE stream
    participant X as ExtractEntitiesAndRelationships
    participant D as DetectCommunities
    participant E as EmbedGraphElements
    participant V as ConstructVectorIndex
    participant LLM as LlmPort
    participant EMB as EmbeddingPort
    participant G as GraphStorePort (Neo4j)
    participant VS as VectorStorePort (Neo4j)

    U->>W: POST /api/corpora (files)
    W-->>U: 201 corpusId
    U->>S: GET /api/corpora/{id}/progress
    par Knowledge graph
        loop for each Text Unit (passage)
            X->>LLM: extract(textUnit, entityTypes)
            LLM-->>X: entities + relationships (+ descriptions)
            X->>X: resolve duplicates, merge provenance and weight
            X->>G: persist (corpus-scoped MERGE)
            X-->>S: text-unit-extracted, entity-extracted, relationship-extracted
        end
        D->>G: detectCommunities(corpusId) — GDS Leiden
        loop each group with ≥ 3 members
            D->>LLM: summarizeCommunity(members, internal relationships)
            D->>G: persist community + memberships
            D-->>S: community-detected
        end
        E->>EMB: embed entities and community summaries
        E->>G: store embeddings (vector indexes)
        W-->>S: ingestion-complete
    and Vector baseline
        V->>EMB: embed 500-char chunks
        V->>VS: persist chunks + 2-D projection
    end
```

A failure in any step marks the corpus **FAILED** and sends an `error` event that names the step: extraction, community detection, embedding, or the LLM.

### Answering a question

All three GraphRAG modes, and the vector baseline, follow the same pattern: **find starting points → assemble a bounded context → record every item as a trace step → let the LLM write a cited answer**.

```mermaid
flowchart TB
    Q["Question + mode"] --> SEM{"Semantic embedding<br/>model configured?"}
    SEM -- yes --> EMB["Embed the question<br/>top-k by vector similarity"]
    SEM -- "no / no embeddings" --> KW["KeywordMatcher<br/>token + fuzzy overlap"]

    EMB --> MODE{"Mode"}
    KW --> MODE

    MODE -- Local --> L1["Top-3 seed entities"] --> L2["≤ 10 one-hop relationships<br/>highest weight first"] --> L3["≤ 5 cited Text Units"]
    MODE -- Global --> G1["Top-3 communities<br/>(title + summary)"] --> G2["≤ 2 member Text Units<br/>per community"]
    MODE -- DRIFT --> D1["≤ 3 candidate communities"] --> D2["Sub-questions"] --> D3["Local-style context<br/>per branch"] --> D4["Union, de-duplicated"]
    MODE -- "Vector (Compare)" --> V1["Top-5 chunks<br/>cosine similarity"]

    L3 & G2 & D4 & V1 --> CTX["Numbered context items<br/>each recorded as a RetrievalStep"]
    CTX --> SYN{"LLM synthesizes<br/>answers?"}
    SYN -- yes --> LLM["LlmPort.synthesizeAnswer<br/>inline [n] citations"] --> CIT["CitationResolver<br/>keep passage citations, renumber"]
    SYN -- "no (offline)" --> TPL["Deterministic templated answer"]
    CIT --> ANS["Answer + citations + trace"]
    TPL --> ANS
    LLM -. "NOT_IN_CONTEXT" .-> NA["noAnswer + reason"]
```

The **Retrieval Trace** is the ordered list of `RetrievalStep`s (`ENTITY`, `RELATIONSHIP`, `COMMUNITY`, `TEXT_UNIT`, `SUB_QUESTION_SPAWNED`, `SYNTHESIS`, `VECTOR_QUERY_EMBEDDED`, `VECTOR_CHUNK`). The browser replays it on the graph canvas, in the DRIFT tree, or, for the vector baseline, in the Compare view's similarity ranking.

### Grounding and citations

What makes an answer verifiable is that its citations are guaranteed to point at passages the search actually read:

1. The use case numbers every context item `[1]..[n]` in the order it assembled them.
2. The prompt lets the model cite **only "Source passage" items**. Entities, relationships and community summaries are background facts.
3. `CitationResolver` drops any marker that is not a passage of the given context, renumbers the kept ones `1..k` by first appearance, and returns `citations[{textUnitId, documentName, excerpt}]`. So `[i]` in the text always means `citations[i-1]`.
4. Every cited passage is a `TEXT_UNIT` step of the same trace, which Replay highlights together with the entities that cite it.
5. If the model answers that the context does not contain the answer, the result becomes `noAnswer`. It never becomes an invented answer.

### Domain model

```mermaid
classDiagram
    direction LR
    class Corpus {
        id
        name
        documents: UploadedDocument[]
    }
    class TextUnit {
        id  «corpusId::doc-i::tu-n»
        documentName
        ordinal
        text
        attributes
        locator?
    }
    class Entity {
        name
        type
        description
        sourceTextUnitIds[]
        attributes
        locator?
        normalizedIdentity()
    }
    class Relationship {
        source / sourceType
        type
        target / targetType
        description
        sourceTextUnitIds[]
        weight
        attributes
        locator?
    }
    class Community {
        id  «community-n»
        title
        summary
        attributes
    }
    class SourceLocator {
        path
        startLine
        endLine
        format() «path:start-end»
    }
    class CommunityMembership {
        communityId
        entityIdentity
    }
    class Chunk {
        id  «corpusId::chunk-n»
        documentName
        ordinal
        text
    }
    class RetrievalTrace {
        traceId
        steps: RetrievalStep[]
    }
    class RetrievalStep {
        kind
        identifier
        label
        locator?
        attributes
    }
    class Citation {
        textUnitId
        documentName
        excerpt
        locator?
        attributes
    }
    Corpus "1" --> "*" TextUnit : split into
    Corpus "1" --> "*" Chunk : chunked into
    TextUnit "1" <-- "*" Entity : cited by
    TextUnit "1" <-- "*" Relationship : cited by
    Entity "2" <-- "*" Relationship : connects
    Community "1" <-- "*" CommunityMembership
    Entity "1" <-- "0..1" CommunityMembership
    RetrievalTrace "1" --> "*" RetrievalStep
    Citation ..> TextUnit : points at
```

`attributes` (a key-sorted `Map<String, String>`, never null) and `locator` (a
`SourceLocator`, null when unknown) are optional on every element; the text
pipeline leaves them empty, an imported code graph fills them (see
[`graphrag-core/README.md`](graphrag-core/README.md#code-and-other-structured-sources)).
Entity and Relationship types are free-form strings.

---

## Persistence model (Neo4j)

Every node and relationship carries a `corpusId`, and every `MERGE` is keyed by it.

```mermaid
erDiagram
    CorpusMeta {
        string corpusId PK
        string name
        string status "BUILDING | READY | FAILED"
        datetime createdAt
    }
    TextUnit {
        string corpusId PK
        string id PK
        string documentName
        int ordinal
        string text
    }
    Entity {
        string corpusId PK
        string normalizedIdentity PK
        string name
        string type
        string description
        list embedding "vector index entity_embedding"
    }
    Community {
        string corpusId PK
        string id PK
        string title
        string summary
        list embedding "vector index community_embedding"
    }
    Chunk {
        string corpusId PK
        string id PK
        string documentName
        string text
        list embedding
    }
    ProjectionModel {
        string corpusId PK
    }
    Entity ||--o{ Entity : "RELATIONSHIP (type, weight, description)"
    Entity }o--o{ TextUnit : MENTIONED_IN
    Entity }o--o| Community : BELONGS_TO
```

- **Community detection:** projects only the corpus's entities and relationships as an undirected GDS graph weighted by `weight`, runs `gds.leiden.stream` with a fixed seed, and always drops the projection again.
- **Semantic matching:** one vector index per label (`entity_embedding`, `community_embedding`) spans all corpora and is filtered by `corpusId` with Cypher's `SEARCH … WHERE` clause.
- **Corpus registry:** corpora survive restarts. Corpora interrupted mid-build are reconciled to FAILED on startup.

---

## Live vs offline behaviour

| | With `OPENAI_API_KEY` | Offline (no key, or the Offline Demo) |
| --- | --- | --- |
| Extraction | LLM per passage, with descriptions | Regex/sentence-based stub |
| Communities | GDS Leiden + LLM title and summary | GDS Leiden (or connected components in memory) + templated summary |
| Seed matching | Embedding similarity (top-3) | `KeywordMatcher` |
| Answers | LLM-written with `[n]` citations | Deterministic templates, no citations |
| Vector baseline | LLM-written with chunk citations | Joined chunk texts |
| Compare verdict | LLM verdict (≤ 2 sentences) | Rule-based summary of the key figures |

Offline mode is fully deterministic, which is why CI and every test run with the key **unset**.

---

## Web application

The frontend is plain JavaScript modules on one page:

- **`upload.js`**: corpus upload and history, the chat with citation markers and a Sources list, and the Compare view with the vector side's similarity ranking (the top 12 chunks by cosine score, the 5 used for the answer marked above a cut-off line). The same tab holds the four-way comparison: one column per method (answer, time bar split into retrieval / embedding / LLM, traversal footprint, replay), the evidence table where a passage can be marked as the expected evidence to diagnose each method's miss, and a JSON log download.
- **`graph-canvas.js`**: the Cytoscape knowledge graph, community hulls, and a one-line legend with an "All communities" side-panel list.
- **`replay.js` / `trace-pane.js` / `drift-tree.js`**: step-by-step Retrieval Trace Replay on the graph, the Retrieval Trace pane that lists every step by phase with the reason it was taken, the branching DRIFT tree inside that pane, and, for the vector baseline, a replay on the Compare tab that lights up the similarity ranking hit by hit.
- **`help.js`**: contextual help topics (`static/help/*.html`), each with a live "in your data" view.

The main endpoints:

| Endpoint | Purpose |
| --- | --- |
| `POST /api/corpora` · `POST /api/corpora/demo` · `POST /api/corpora/demo-offline` | Create a corpus and start ingestion |
| `GET /api/corpora/{id}/progress` | SSE progress stream (never times out; keep-alive every 15 s) |
| `GET /api/corpora` · `POST /api/corpora/{id}/activate` | Corpus history and switching |
| `GET /api/corpora/{id}/graph` | Entities, relationships and communities for the canvas |
| `POST /api/corpora/{id}/query` | Ask a question (`LOCAL`, `GLOBAL`, `DRIFT`, `VECTOR`) |
| `POST /api/corpora/{id}/compare` | GraphRAG vs vector comparison |
| `POST /api/corpora/{id}/compare-all` | All four retrieval methods side by side: answers, steps, timing and the evidence table |
| `GET /api/traces/{traceId}` | A Retrieval Trace for Replay |
| `GET /api/corpora/{id}/text-units/{tuId}` · `…/chunks/{chunkId}` | Passage text for citations |

---

## Build and test

Requirements:

- **JDK 25**. The build enforces it and fails fast if it is not on `JAVA_HOME`/`PATH`.
- Apache Maven 3.9+.
- Docker, for the Testcontainers-based Neo4j tests, which also download the GDS plugin.

```bash
mvn package
```

This builds all five modules and produces the executable jar at `graphrag-web/target/graphrag-web.jar`.

Run the tests the way CI does, with the OpenAI key unset. With a key, the Spring and Playwright UI tests would call the real model and become non-deterministic.

```bash
env -u OPENAI_API_KEY mvn -B install
```

On Docker 29 you may need `-Dapi.version=1.44` so Testcontainers can find the Docker daemon.

---

## Running the app

```bash
OPENAI_API_KEY=sk-... docker compose up
```

This is the only setup step: it builds the `app` image, starts Neo4j (with the GDS plugin) alongside it, and serves the app on port 8080.

The app connects to Neo4j via `NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD` (defaults `bolt://neo4j:7687`/`neo4j`/`graphraglens`, matching the `neo4j` service's own `NEO4J_AUTH` default). It aborts startup with a clear error if Neo4j is unreachable, rather than silently falling back to any in-memory behavior.

Neo4j's data and logs are bind-mounted to `./neo4j-data/` next to `docker-compose.yml` (git-ignored). They survive container restarts and recreation, and you can back the folder up or delete it directly.

Caveat: `NEO4J_AUTH`/the Neo4j password is only applied when Neo4j's data directory is first created. If you change it after the first run, stop the stack and delete `./neo4j-data/` first. Otherwise the running database keeps its original credentials and silently diverges from `docker-compose.yml`.

Caveat: restarting the `app` container clears every captured Retrieval Trace. Traces live only in the `app` process's memory (`RetrievalTraceStore`), never on disk, matching this project's single-user/local-only scope. Corpora are unaffected: they are persisted in Neo4j (`Neo4jCorpusRegistry`) and survive an `app` restart. After a restart, re-run a query to capture a fresh trace.

---

## Definition of done

- Changing how a feature behaves (especially retrieval behaviour in the Local, Global, Drift or vector-baseline answer classes) means updating its help topic under `graphrag-web/src/main/resources/static/help/` in the same change. Topics name the classes they describe in `<code data-class="...">`. `HelpRegistryGuardTest` fails when a cited class disappears or a `?` button has no topic, but it cannot tell whether the prose is still true.
- Every new `?` button (`data-help="<topic>"`) needs a matching topic file.
- Behaviour changes to `graphrag-core` are recorded in [`graphrag-core/CHANGELOG.md`](graphrag-core/CHANGELOG.md).
