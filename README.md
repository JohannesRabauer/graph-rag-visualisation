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
| `graphrag-core` | Framework-free hexagonal core. Domain model and port interfaces (`GraphStorePort`, `LlmPort`, `DocumentParserPort`). No dependency on Spring, the Neo4j Java Driver, or LangChain4j. |
| `graphrag-adapter-neo4j` | Adapter stub for the future Neo4j graph-store implementation of `GraphStorePort`. |
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

Caveat: `NEO4J_AUTH`/the Neo4j password is only applied when Neo4j's data
volume is first created. If you change it after the first run, also run
`docker compose down -v` first — otherwise the running database keeps its
original credentials and silently diverges from `docker-compose.yml`.
