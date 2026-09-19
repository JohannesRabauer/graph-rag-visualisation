# Addendum: GraphRAG Lens

Supporting depth behind the product brief — landscape research and options considered. For downstream use by PRD/architecture work, not required reading to understand the brief itself.

## Landscape Research Digest

### GraphRAG state of the art (2025–2026)
- Core idea: an LLM extracts entities/relationships from text into a knowledge graph; retrieval then traverses/aggregates graph structure instead of, or alongside, nearest-neighbor vector search.
- Originating work: Microsoft Research, "From Local to Global: A Graph RAG Approach to Query-Focused Summarization" (arXiv 2404.16130; microsoft.github.io/graphrag). Builds an entity graph, runs Leiden community detection into hierarchical levels, summarizes each community.
- **Local search**: answers entity-specific questions via graph-neighborhood traversal. **Global search**: answers corpus-wide "what are the themes" questions by map-reducing over community summaries (root-level summaries reportedly use ~97% fewer tokens than raw text; 72–83% comprehensiveness win-rate vs. vector RAG in LLM-judged comparisons).
- 2025–2026 evolution: LazyGraphRAG (defers/reduces expensive community summarization; now in Microsoft Discovery/Azure Local preview). Hybrid vector+graph retrieval is now the mainstream framing, not graph-only. Many 2025 arXiv variants (HybridRAG, HybGRAG, KET-RAG, HetaRAG) — an active, unsettled research area.

### Neo4j's own tooling — Python-first
- `neo4j-graphrag-python` (github.com/neo4j/neo4j-graphrag-python): official first-party SDK, `SimpleKGPipeline` for text/PDF→KG, retrievers, GraphAcademy course. Python only — no first-party Java GraphRAG SDK exists.
- LLM Knowledge Graph Builder (neo4j-labs/llm-graph-builder): no-code web app, built on the `llm-graph-transformer` module Neo4j contributed to LangChain (Python).
- Neo4j Bloom: commercial visual graph exploration, not GraphRAG-specific.
- NeoDash: low-code Cypher dashboard builder, now unmaintained (Neo4j points to a newer "Neo4j Dashboards" tool).

### Java ecosystem — confirmed real gap
- LangChain4j has Neo4j vector-store integration (`Neo4jEmbeddingStoreIngestor`) and blog-documented GraphRAG-pattern ingestors, but Neo4j's own blog frames deeper graph-aware patterns as future work — still immature.
- Spring AI's `Neo4jVectorStore` is vector-only; no built-in graph-traversal/community-summarization layer.
- One concrete prior-art repo found: `extrawest/movies-ai-search-demo` (Java 21 + Spring Boot + LangChain4j + Neo4j, natural-language movie search with interactive graph visualizations) — narrow, single-domain, not a general GraphRAG teaching tool.
- GitHub's `graphrag`/`graph-rag` topic pages are overwhelmingly Python/TypeScript; only ~2 Java-tagged repos found. The gap is real and verifiable, not just unfamiliarity — this supports the brief's stretch goal of seeding a Java GraphRAG library.

### Visualization prior art
- `noworneverev/graphrag-visualizer`: ingests Microsoft GraphRAG's parquet indexing artifacts, renders entities/communities/relationships — closest existing analog, but post-hoc, not live/interactive, and not Java/Neo4j-based.
- Generic rendering substrates (no GraphRAG-specific concepts baked in): Neovis.js (Neo4j-native, Cypher→vis-network), Cytoscape.js, vis-network.
- No widely-known tool visualizes GraphRAG's *live* retrieval mechanics (which nodes/communities get traversed for a given query).

### Comparable educational RAG explainers (bar-setters, none GraphRAG/Neo4j/Java)
- RAG Playground (ragplay.vercel.app), zackproser.com/demos/rag-visualized — step-through chunking→embedding→vector-search animations.
- RAG Explainer (jay.tools/projects/rag-explainer), Cognee's "A Picture of RAG" — visual pipeline walkthroughs.
- RAGViz (ACL 2024 demo paper) — academic, visualizes token/document attention during retrieval; vector-RAG-focused, sets a rigor bar.
- Takeaway: a strong "visual RAG pipeline explainer" genre exists for vanilla vector RAG; essentially none tackle graph traversal or community structure, and none run on Neo4j in Java.

## Options Considered: Demo Dataset

Note: this section predates the decision (made during PRD discovery) to support arbitrary user-provided files as a real v1 feature. It's preserved as the rationale for *which dataset ships as the built-in demo* — a separate question from what the ingestion pipeline itself supports.

Three options were considered.

1. **Public-domain fiction (chosen)** — e.g., Sherlock Holmes stories. Recurring characters, locations, and relationships across stories produce a rich, legible entity graph. No licensing/API cost concerns. Familiar enough that a stream audience can follow without domain expertise.
2. **Small multi-article news corpus around one event** — strongest candidate for showcasing *global search* specifically, since differing outlet angles naturally form communities. Considered a good v2/stretch dataset once the core demo works, rather than the v1 choice.
3. **The creator's own content** (blog posts, stream transcripts) — strong narrative hook for streaming, but weaker for demonstrating rich relational structure unless there's a large, densely interlinked back catalog.

## Notes for Architecture

- File ingestion (plain text and PDF for v1) needs a real parsing/extraction boundary, not a hardcoded loader — PDF text extraction in particular should be isolated behind an interface so it doesn't leak into the graph-construction logic.
- Provider abstraction (LangChain4j, OpenAI initially) exists specifically to make swapping LLM providers cheap later — this should be reflected as an explicit architectural boundary, not an incidental library choice.
- The "library-in-mind" ambition affects module boundaries (e.g., separating core GraphRAG/graph logic from the demo web app) more than it affects any v1 feature — no v1 scope item should exist purely to serve the future-library goal.
