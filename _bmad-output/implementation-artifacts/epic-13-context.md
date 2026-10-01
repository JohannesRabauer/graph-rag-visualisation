# Epic 13 Context: Knowledge Graph Quality — Per-Passage Extraction with Descriptions & Provenance

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Today the OpenAI adapter extracts the whole Corpus in one prompt and gets back bare `{name, type}` Entities. On a long document (e.g. `demo-corpora/history-of-java.txt`, ~9,000 words) that yields a few dozen Entities, mostly from the start of the text. It also risks truncated JSON, makes the graph appear all at once, and leaves no link from an Entity back to its source text. This epic rebuilds extraction the way reference GraphRAG implementations do. Extraction runs passage by passage (Text Units) against a fixed type list. Every Entity and Relationship gets an LLM-written description and its source passages. Duplicates across passages are resolved before they reach Neo4j, and the detail panel shows descriptions and source passages. Epics 14 (Leiden communities, content-based summaries) and 15 (cited, LLM-written answers) build on this graph, so every search mode depends on its quality.

## Stories

- Story 13.1: Extract the Knowledge Graph Passage by Passage
- Story 13.2: Give Every Entity and Relationship a Description and Its Source Passages
- Story 13.3: Resolve Duplicate Entities Before They Reach Neo4j
- Story 13.4: Show Descriptions and Source Passages in the Entity Detail Panel

## Requirements & Constraints

- **Per-passage extraction:** the Corpus is split into overlapping Text Units, with one LLM extraction call per unit. Progress reports each unit as it completes, and the canvas grows passage by passage. Entities found late in a long document must be extracted as reliably as early ones.
- **Fixed Entity types:** Person, Organization, Product, Technology, Version, Event, Location, Concept. Any other type the LLM returns maps to Concept.
- **Descriptions and provenance:** every Entity and Relationship carries a short LLM-written description (one or two sentences) and the ids of its source Text Units. Descriptions from several units merge into one; a later unit never overwrites an earlier one.
- **Duplicate resolution:** mentions that differ only in letter case, surrounding punctuation, whitespace, or extracted type resolve to one Entity. A name extracted with two types takes the type with the most mentions. Relationships to any variant point to the resolved Entity.
- **Visible failure, no retries:** if any Text Unit fails, returns invalid JSON, or is cut off by the output-token limit, the whole ingestion stops and the corpus is marked `FAILED`. The existing error state shows. Never keep a partial "best effort" graph. The server log names the document and passage number.
- **Offline/demo mode** must stay deterministic and network-free. The Demo Dataset, offline mode, and all existing UI tests must keep passing.
- **Backward compatibility:** corpora persisted before this epic still load. A missing description reads as empty, never as an error.
- **Out of scope:** fuzzy or semantic merging (e.g. "Java 8" ≡ "Java SE 8"), gleaning (repeated extraction passes over one unit), and hierarchical communities.

## Technical Decisions

- **Core stays framework-free:** `TextUnitSplitter`, `EntityResolver`, the Entity-type constant, and the domain records live in `graphrag-core`, which imports no Spring, Neo4j driver, LangChain4j, or PDFBox types. Only `graphrag-adapter-langchain4j` touches LangChain4j or OpenAI types, and everything else goes through `LlmPort`.
- **TextUnit:** `id`, `corpusId`, `documentName`, `ordinal`, `text`. Units are ~6,000 characters with ~600 characters of overlap, cut on paragraph or sentence boundaries where possible, and have stable ids. They are separate from the Vector Baseline's 500-character `Chunk`s. Do not merge the two splitters.
- **LlmPort:** the new call is `extract(TextUnit, List<String> entityTypes)`. It runs once per unit, sequentially. The old `extract(Corpus)` stays as a default method that loops over Text Units. The OpenAI adapter uses JSON mode and an explicit max-output-token limit, and treats finish reason `length` as a failure.
- **Per-unit commit:** each unit's result, with resolution already applied, is persisted in its own transactions before the next unit starts. Reads see whatever is committed, so the graph grows live. There is no locking and no Corpus-wide transaction.
- **Domain records:** `Entity` gains `description` and `sourceTextUnitIds`. `Relationship` gains `description`, `sourceTextUnitIds`, and an integer `weight` (the number of source Text Units). The existing two- and five-argument constructors stay as overloads so v1 call sites compile.
- **Neo4j schema:** new node `(:TextUnit {corpusId, id, documentName, ordinal, text})`, MERGE-keyed on `(corpusId, id)`. Each source unit gets one `(:Entity)-[:MENTIONED_IN]->(:TextUnit)`. Descriptions, source ids, and weight are properties on the existing Entity nodes and relationships. Every node and constraint includes `corpusId`. Every `corpusId`-scoped `GraphStorePort` method must be overridden with real scoped Cypher, and unscoped overloads in the Neo4j adapter throw `UnsupportedOperationException`. The in-memory adapter keeps the new fields in memory.
- **Resolution (core, before MERGE):** compare names after Unicode normalization, case folding, whitespace collapsing, and stripping surrounding punctuation. Resolve each mention against the Corpus's already-known Entities. For type conflicts, the most mentions wins and ties keep the earlier type. Rewrite Relationship endpoints to the resolved identities. To merge descriptions, append distinct sentences, capped at ~1,000 characters. Union the source ids. A Relationship extracted again increments `weight`. The persistence key stays MERGE on lowercased `name::type`, scoped by `corpusId`; resolution only decides which key a mention maps to.
- **SSE:** everything stays on the single multiplexed stream `GET /api/corpora/{corpusId}/progress`, using the `{"type": ..., "data": {...}}` envelope. The new `text-unit-extracted` event carries `{index, total, documentName}` and is emitted before that unit's `entity-extracted` and `relationship-extracted` events. Those two payloads gain `description`.
- **New endpoint:** `GET /api/corpora/{corpusId}/text-units/{textUnitId}` returns the passage text, or 404. Errors use the `{"error": "<plain-language message>"}` shape.

## UX & Interaction Patterns

- **Ingestion:** the workflow status line reads "Extracting passage {index} of {total} — {documentName}". Nodes and edges appear unit by unit on the main-screen canvas. An extraction failure uses the existing error-banner pattern and names what failed.
- **Entity detail panel:**
  - It is the existing right-hand slide-in panel. Clicking a node opens it, clicking the same node again closes it, and clicking another node swaps its contents. It coexists with the Replay scrubber.
  - The existing Description section shows the description and is hidden when empty.
  - A new "Source passages" section lists each unit as `{documentName} · passage {ordinal}`. Expanding one fetches and shows its text; on 404 it shows "Passage not available".
  - Relationship rows show their description as a tooltip.
- **Help and tests:** update the `entity-detail` help article to mention descriptions and source passages.

## Cross-Story Dependencies

- 13.1 introduces Text Units, per-unit `extract`, the type list, `TextUnit` persistence, and the progress events.
- 13.2 adds descriptions, provenance, and weight on top of 13.1's per-unit pipeline.
- 13.3 resolves duplicates using 13.2's description and source-id fields, inside 13.1's per-unit loop.
- 13.4 needs 13.2–13.3's data and the persisted Text Units.
- Downstream: Epic 14 uses `weight` for GDS Leiden and descriptions for Community summaries. Epic 15 uses descriptions and Text Units for semantic matching and citations.
