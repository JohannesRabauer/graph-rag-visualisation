# GraphRAG Lens

A GraphRAG-based exploration tool, scaffolded as a Java 25 Maven multi-module
project along Hexagonal Architecture boundaries.

## Requirements

- **JDK 25** (enforced at build time via `maven-enforcer-plugin`; the build
  fails fast with a clear message if it is not on `JAVA_HOME`/`PATH`)
- Apache Maven 3.9+

## Module layout

| Module | Purpose |
| --- | --- |
| [`graphrag-core`](graphrag-core/README.md) | Framework-free hexagonal core. Domain model and port interfaces (`GraphStorePort`, `LlmPort`, `DocumentParserPort`). No dependency on Spring, the Neo4j Java Driver, or LangChain4j. See its own [README](graphrag-core/README.md) for the port contracts and a standalone usage example. |
| `graphrag-adapter-neo4j` | Real Neo4j-backed implementation of `GraphStorePort`/`VectorStorePort`, using the plain Neo4j Java Driver (no Spring Data Neo4j). |
| `graphrag-adapter-langchain4j` | Adapter stub for the future LangChain4j implementation of `LlmPort`. |
| `graphrag-adapter-parsing` | Adapter stub for the future PDF/text parsing implementation of `DocumentParserPort`. |
| `graphrag-web` | Spring Boot + Thymeleaf web application; the executable entry point. No Node/npm tooling anywhere in this module or the repository. |

## Build

```
mvn package
```

This builds all five modules and produces the executable jar at
`graphrag-web/target/graphrag-web.jar`.

## Running the app

```
OPENAI_API_KEY=sk-... docker compose up
```

This is the only setup step: it builds the `app` image, starts Neo4j
(with the GDS plugin) alongside it, and serves the app on port 8080.

The app connects to Neo4j via `NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD`
(defaults `bolt://neo4j:7687`/`neo4j`/`graphraglens`, matching the `neo4j`
service's own `NEO4J_AUTH` default) and aborts startup with a clear error if
Neo4j is unreachable, rather than silently falling back to any in-memory
behavior.

Caveat: `NEO4J_AUTH`/the Neo4j password is only applied when Neo4j's data
volume is first created. If you change it after the first run, also run
`docker compose down -v` first — otherwise the running database keeps its
original credentials and silently diverges from `docker-compose.yml`.

Caveat: restarting the `app` container clears every captured Retrieval
Trace — it lives only in the `app` process's own memory
(`RetrievalTraceStore`), never on disk, matching this project's
single-user/local-only scope (NFR3). Corpus bookkeeping is unaffected: it's
persisted in Neo4j (`Neo4jCorpusRegistry`) and survives an `app` restart
just like Neo4j's own data volume does. So after restarting, both the
underlying graph data and the app's Corpus/workflow-status records are
still there — only any trace to replay is gone. Re-run a query to capture a
fresh trace if you need one. If you're mid-demo, restarting only loses the
Retrieval Trace history, not your Corpora.

## Definition of done

- Changing how a feature behaves (especially retrieval behaviour in the
  Local, Global, Drift or vector-baseline answer classes) means updating its
  help topic under `graphrag-web/src/main/resources/static/help/` in the same
  change. Topics name the classes they describe in `<code data-class="...">`;
  `HelpRegistryGuardTest` fails when a cited class disappears or a `?` button
  has no topic, but it cannot tell whether the prose is still true.
- Every new `?` button (`data-help="<topic>"`) needs a matching topic file.
