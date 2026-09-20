---
title: 'Build the Vector Index Alongside the Knowledge Graph'
type: 'feature' # feature | bugfix | refactor | chore
created: '2026-09-21'
status: 'done' # draft | ready-for-dev | in-progress | in-review | done | blocked
baseline_revision: '007b3de5f424c3bf49b3a7aefc771c7b8d0627da'
baseline_commit: '007b3de5f424c3bf49b3a7aefc771c7b8d0627da'
review_loop_iteration: 1 # incremented by step-04 before each review loopback
followup_review_recommended: false # set by step-04 on status: done — true if the LLM decided another review pass is worthwhile
context: []
warnings: []
deferred:
  - summary: >-
      InMemoryVectorStoreAdapter mutates a plain LinkedHashMap from persistChunks(), which is
      now invoked from a CompletableFuture.runAsync task per upload — concurrent uploads could
      race on the singleton bean.
    evidence: |-
      Confirmed this exactly mirrors the pre-existing InMemoryGraphStoreAdapter's identical
      unsynchronized-LinkedHashMap-as-singleton-bean pattern (present since before this story,
      per baseline 007b3de). This story replicated an existing, already-accepted risk rather
      than introducing a new one.
    location: 'graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/InMemoryVectorStoreAdapter.java:11-29'
    severity: low
  - summary: >-
      ParserConfig.embeddingPort(...)'s OPENAI_API_KEY-set branch (real OpenAiEmbeddingPort
      selection) is not exercised by any Spring context test.
    evidence: |-
      No ParserConfigTest exists in the repo at all, before or after this story — llmPort(...)'s
      equivalent real-adapter-selection branch was already untested, so embeddingPort(...)
      inherits the same pre-existing gap rather than introducing a new one. The unset-key
      branch (the one every test actually exercises) is fully covered.
    location: 'graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java'
    severity: low

---

<intent-contract>

## Intent

**Problem:** Today, uploading or choosing the demo Corpus only ever produces the entity/relationship knowledge graph (`BuildKnowledgeGraph` → `DetectCommunities`). There is no chunk-level vector index at all, so nothing in Epic 8 (vector-space baseline answers, comparison view, replay) has anything to read from.

**Approach:** Add a new, fully independent construction path — `ConstructVectorIndex` — that chunks a Corpus's documents, embeds each chunk via a new `EmbeddingPort`, computes a 2D projection of the chunk embeddings exactly once, and persists chunks + embeddings + projections via a new `VectorStorePort`. Wire it into `CorpusController` as a second, separate `CompletableFuture.runAsync` task dispatched alongside (never chained into) the existing knowledge-graph task, so the two pipelines run concurrently and neither can block, gate, or fail because of the other. Both new ports follow the exact real-vs-offline-stub adapter-selection pattern already established for `LlmPort`/`GraphStorePort` (`OPENAI_API_KEY` presence selects a real OpenAI-backed embedding adapter vs. a deterministic offline stub; the vector store gets an in-memory adapter today, consistent with `GraphStorePort`'s existing `Neo4jGraphStoreAdapter` being a bare alias of `InMemoryGraphStoreAdapter` — no new container, no real Neo4j wiring is added by this story).

## Boundaries & Constraints

**Always:**
- `ConstructVectorIndex` chunks each `UploadedDocument` in the `Corpus` independently, embeds each chunk via `EmbeddingPort.embed(String)`, computes the 2D projection for the *entire* set of chunk embeddings in one pass, and persists all of it via `VectorStorePort.persistChunks(corpusId, Collection<EmbeddedChunk>)` — all in a single `run(Corpus)` call with no external re-entry point that recomputes the projection later.
- The 2D projection is computed **once**, during this construction step, over the full set of a Corpus's chunk embeddings — never per-query, never lazily, never recomputed for a subset. It is persisted alongside each chunk's embedding inside the same `EmbeddedChunk` record so any later story (8.3+) can read it back without recomputing anything.
- `CorpusController` dispatches vector-index construction as its **own** independent `CompletableFuture.runAsync(...)` call, started immediately alongside (not after, not chained via `.thenRun`) the existing `startKnowledgeGraphConstruction(corpus)` call, in both `upload()` and `useDemoDataset()`. A failure inside the vector-index task (embedding call throws, chunking throws, etc.) is caught and logged only — it must never call `corpusStore.markFailed(...)`, never emit a `corpusProgressService` event, and never prevent or delay `BuildKnowledgeGraph`/`DetectCommunities` from running or completing. Symmetrically, a failure in the existing knowledge-graph task must never cancel or affect the vector-index task — the two `CompletableFuture`s share no state and do not observe each other.
- `EmbeddingPort` and `VectorStorePort` live in `graphrag-core/src/main/java/com/graphraglens/core/port/`, framework-free (no LangChain4j/Spring/Neo4j imports), exactly like every existing port.
- New domain types (`Chunk`, `EmbeddedChunk`) live in `graphrag-core/src/main/java/com/graphraglens/core/domain/`, as plain records, exactly like `Corpus`/`UploadedDocument`/`Entity`.
- The real embedding adapter (`OpenAiEmbeddingPort`) lives in `graphrag-adapter-langchain4j`, is selected by `ParserConfig.embeddingPort(...)` only when `OPENAI_API_KEY` (env var or `-D` system property, via the same `@Value("${OPENAI_API_KEY:}")` pattern already used for `llmPort(...)`) is non-blank; otherwise the deterministic offline stub (`LangChain4jEmbeddingPort`) is used — mirroring `llmPort(...)`'s existing branch, log messages, and `@Value` conventions exactly.
- The vector-store adapter (`InMemoryVectorStoreAdapter`, plus a `Neo4jVectorStoreAdapter` alias `extends InMemoryVectorStoreAdapter {}`) lives in `graphrag-adapter-neo4j`, wired unconditionally via `ParserConfig.vectorStorePort()` — same pattern as the existing `graphStorePort()` bean, which likewise wires `InMemoryGraphStoreAdapter` unconditionally today.
- Chunking is deterministic, fixed-size character chunking with a break-on-nearest-preceding-space heuristic (see Code Map for the exact algorithm) so a given document's chunk boundaries never depend on embeddings, randomness, or wall-clock time.
- The 2D projection is computed via a simple, self-contained, zero-new-dependency PCA-by-power-iteration (see Code Map's pseudocode) — no new Maven dependency (linear-algebra library, etc.) is introduced anywhere in this story. `langchain4j-open-ai` (already a `graphrag-adapter-langchain4j` dependency) already exposes `OpenAiEmbeddingModel`, so no new artifact is needed there either.
- Existing constructor call sites for `CorpusController` in `CorpusControllerTest.java`, `CorpusControllerGlobalSearchTest.java`, and `CorpusControllerDriftSearchTest.java` (12 call sites total across the three files) are updated to pass `null` as the new trailing `ConstructVectorIndex` argument — none of those tests exercise `upload()`/`useDemoDataset()`, only `query()`, so `null` is safe there exactly like the existing `null` passed for unrelated ports in those same call sites today.

**Never:**
- Do not touch `AnswerLocalSearch`, `AnswerGlobalSearch`, `AnswerDriftSearch`, `BuildKnowledgeGraph`, `DetectCommunities`, `GraphStorePort`, `LlmPort`, or the `/api/traces/{traceId}` endpoint — this story is additive only (new ports/domain/use case/adapters + one new orchestration call site in `CorpusController` + Spring wiring). Entity/relationship extraction behavior is 100% unchanged.
- Do not add any new HTTP endpoint, UI, or `corpusProgressService` event for the vector index — reading it back, surfacing progress, and any UI all belong to later Epic 8 stories (8.2+). This story only builds and persists the index.
- Do not introduce a real Neo4j connection, driver dependency, or container — `docker-compose.yml` is untouched. `Neo4jVectorStoreAdapter` is an alias exactly like the existing `Neo4jGraphStoreAdapter`, not a real client.
- Do not make `EmbeddingPort`/`VectorStorePort` or the new domain types depend on anything outside `graphrag-core` (no LangChain4j types, no Spring annotations) — mirrors AD-3.
- Do not chain vector-index construction after knowledge-graph construction (e.g. via `.thenRun`) or vice versa — they must be two independently dispatched `CompletableFuture.runAsync` calls so neither can gate the other, per the AC.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Corpus with one short document | 1 `UploadedDocument`, content shorter than one chunk | `ConstructVectorIndex.run(corpus)` produces exactly 1 `Chunk`/`EmbeddedChunk`; projection is `[0.0, 0.0]` (no variance to project with a single point); persisted via `VectorStorePort.persistChunks(corpusId, ...)` | N/A — happy path |
| Corpus with a document long enough to need multiple chunks | 1 document, content > chunk size | Multiple `Chunk`s produced in ordinal order, each chunk boundary lands on a preceding whitespace where possible (no mid-word split when a space exists within the lookback window); every chunk gets its own embedding and projection coordinate | N/A — happy path |
| Corpus with multiple documents | 2+ `UploadedDocument`s | Chunk ordinals are continuous across documents (document 2's first chunk continues the ordinal sequence, not restarting at 0); all chunks across all documents are embedded and projected together in one call | N/A — happy path |
| Corpus with a blank/empty document among non-blank ones | one document has blank/whitespace-only content | The blank document contributes zero chunks; other documents are still chunked/embedded/persisted normally | N/A — no exception, blank content silently skipped |
| Corpus with zero documents or all-blank documents | `corpus.documents()` empty or every document blank | `run(corpus)` returns an empty list; `VectorStorePort.persistChunks` is still invoked with an empty collection (or not invoked at all — either is acceptable as long as no exception is thrown) | N/A — no exception |
| All chunk embeddings are identical (zero variance) | e.g. all chunks embed to the same stub vector | 2D projection never throws or produces `NaN`/`Infinity` — degenerates to `[0.0, 0.0]` for every point | N/A — no exception, explicit zero-variance guard |
| `EmbeddingPort.embed(...)` throws for a Corpus queued via `upload()`/`useDemoDataset()` | vector-index task's embedding call throws | The independent `CompletableFuture.runAsync` task for vector-index catches the exception and logs it; `corpusStore`'s status and `corpusProgressService` events are untouched; the existing knowledge-graph `CompletableFuture` still runs BuildKnowledgeGraph/DetectCommunities to completion and calls `corpusStore.markReady(...)` normally | Caught and logged only — never surfaced to the corpus/progress state |
| Knowledge-graph construction fails (existing behavior) | `BuildKnowledgeGraph`/`DetectCommunities` throws | Existing behavior unchanged: `corpusStore.markFailed(...)` + `error` event; the independent vector-index task is unaffected and still runs/completes on its own | Unchanged from before this story |
| `OPENAI_API_KEY` unset | Spring context startup | `ParserConfig.embeddingPort(...)` returns `LangChain4jEmbeddingPort` (deterministic, offline, same hashing-based stub every JVM run); no network access, tests/CI keep working exactly like `llmPort(...)`'s existing fallback | N/A — matches existing `LlmPort` fallback behavior |

</intent-contract>

## Code Map

- `graphrag-core/src/main/java/com/graphraglens/core/domain/Chunk.java` (new) -- `public record Chunk(String id, String corpusId, int ordinal, String text) {}`. `id` is `corpusId + "::chunk-" + ordinal`.
- `graphrag-core/src/main/java/com/graphraglens/core/domain/EmbeddedChunk.java` (new) -- `public record EmbeddedChunk(Chunk chunk, float[] embedding, double[] projection) {}`. `projection` is always length 2 (`[x, y]`).
- `graphrag-core/src/main/java/com/graphraglens/core/port/EmbeddingPort.java` (new) -- `public interface EmbeddingPort { float[] embed(String text); }`.
- `graphrag-core/src/main/java/com/graphraglens/core/port/VectorStorePort.java` (new) -- follows `GraphStorePort`'s style:
  ```java
  public interface VectorStorePort {
      void persistChunks(String corpusId, Collection<EmbeddedChunk> chunks);
      default Collection<EmbeddedChunk> chunks(String corpusId) { return List.of(); }
  }
  ```
- `graphrag-core/src/main/java/com/graphraglens/core/usecase/ConstructVectorIndex.java` (new) -- mirrors `DetectCommunities`'s constructor-injected-ports + `run(Corpus)` shape:
  ```java
  public class ConstructVectorIndex {
      private static final int CHUNK_SIZE = 500;
      private static final int MIN_CHUNK_SIZE = CHUNK_SIZE / 2;

      private final EmbeddingPort embeddingPort;
      private final VectorStorePort vectorStorePort;

      public ConstructVectorIndex(EmbeddingPort embeddingPort, VectorStorePort vectorStorePort) { ... }

      public List<EmbeddedChunk> run(Corpus corpus) {
          List<Chunk> chunks = chunk(corpus);
          if (chunks.isEmpty()) { return List.of(); }
          List<float[]> embeddings = chunks.stream().map(c -> embeddingPort.embed(c.text())).toList();
          double[][] projections = TwoDProjection.project(embeddings);
          List<EmbeddedChunk> embeddedChunks = new ArrayList<>();
          for (int i = 0; i < chunks.size(); i++) {
              embeddedChunks.add(new EmbeddedChunk(chunks.get(i), embeddings.get(i), projections[i]));
          }
          vectorStorePort.persistChunks(corpus.id(), embeddedChunks);
          return embeddedChunks;
      }

      private List<Chunk> chunk(Corpus corpus) {
          // for each UploadedDocument (skip null/blank content): walk the content in
          // CHUNK_SIZE windows; if the window's end is not the document end, search
          // backwards from `end` for the nearest ' ' but only accept it if it is more
          // than MIN_CHUNK_SIZE chars after `start` (else hard-cut at CHUNK_SIZE to
          // avoid pathologically tiny chunks); trim() the resulting text and skip it
          // if blank; ordinal increments once per emitted chunk and is NOT reset
          // between documents (continuous across the whole corpus).
      }
  }
  ```
- `graphrag-core/src/main/java/com/graphraglens/core/usecase/TwoDProjection.java` (new, package-private, no public modifier — internal helper for `ConstructVectorIndex` only) -- self-contained PCA via power iteration, no new dependency:
  ```java
  final class TwoDProjection {
      private TwoDProjection() {}

      static double[][] project(List<float[]> vectors) {
          int n = vectors.size();
          if (n == 0) return new double[0][2];
          if (n == 1) return new double[][] { {0.0, 0.0} };
          int d = vectors.get(0).length;
          double[][] centered = center(vectors, n, d); // subtract per-dimension mean
          double[] pc1 = principalComponent(centered, n, d, seed(d, 1));
          double[] scores1 = scores(centered, pc1, n, d);
          double[][] deflated = deflate(centered, pc1, scores1, n, d); // X - outer(scores1, pc1)
          double[] pc2 = principalComponent(deflated, n, d, seed(d, 2));
          double[] scores2 = scores(deflated, pc2, n, d);
          double[][] result = new double[n][2];
          for (int i = 0; i < n; i++) result[i] = new double[] { scores1[i], scores2[i] };
          return result;
      }

      // principalComponent: ~30 fixed iterations of
      //   u[i] = sum_j X[i][j] * v[j]        (X * v, length n)
      //   vNew[j] = sum_i X[i][j] * u[i]     (X^T * u, length d)
      //   normalize vNew (L2); if norm is ~0 (zero-variance data), return a zero
      //   vector immediately instead of dividing by zero/producing NaN.
      // seed(d, which): a fixed, deterministic non-zero starting vector — e.g. which==1
      // uses all-+1 (normalized), which==2 uses an alternating +1/-1 pattern, so the
      // two components explore different directions even in degenerate/zero-variance
      // inputs instead of both collapsing onto the same seed.
      // scores(X, pc, n, d): scores[i] = sum_j X[i][j] * pc[j]; if pc is the zero
      // vector (degenerate case), every score is 0.0 (no NaN).
  }
  ```
  This must be covered directly by unit tests in the same package (`com.graphraglens.core.usecase`) since it is package-private.
- `graphrag-adapter-langchain4j/src/main/java/com/graphraglens/adapter/langchain4j/OpenAiEmbeddingPort.java` (new) -- mirrors `OpenAiLlmPort`'s constructor validation exactly (`IllegalArgumentException` on blank/null API key):
  ```java
  public class OpenAiEmbeddingPort implements EmbeddingPort {
      private static final String DEFAULT_MODEL = "text-embedding-3-small";
      private final EmbeddingModel model;

      public OpenAiEmbeddingPort(String apiKey) { this(apiKey, DEFAULT_MODEL); }

      public OpenAiEmbeddingPort(String apiKey, String modelName) {
          if (apiKey == null || apiKey.isBlank()) {
              throw new IllegalArgumentException("OpenAI API key must not be blank");
          }
          String model = modelName == null || modelName.isBlank() ? DEFAULT_MODEL : modelName;
          this.model = OpenAiEmbeddingModel.builder()
                  .apiKey(apiKey)
                  .modelName(model)
                  .maxRetries(0)
                  .build();
      }

      @Override
      public float[] embed(String text) {
          return model.embed(text == null ? "" : text).content().vector();
      }
  }
  ```
  Uses `dev.langchain4j.model.openai.OpenAiEmbeddingModel` and `dev.langchain4j.model.embedding.EmbeddingModel` (both already available transitively from the existing `langchain4j-open-ai:1.20.0` dependency in this module's `pom.xml` — confirmed present, no `pom.xml` change needed).
- `graphrag-adapter-langchain4j/src/main/java/com/graphraglens/adapter/langchain4j/LangChain4jEmbeddingPort.java` (new) -- deterministic, offline, no network: hashing-based bag-of-words embedding into a fixed 64-dimensional vector, then L2-normalized:
  ```java
  public class LangChain4jEmbeddingPort implements EmbeddingPort {
      static final int DIMENSIONS = 64;

      @Override
      public float[] embed(String text) {
          float[] vector = new float[DIMENSIONS];
          if (text == null || text.isBlank()) return vector;
          for (String token : text.toLowerCase(Locale.ROOT).split("\\W+")) {
              if (token.isBlank()) continue;
              vector[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1f;
          }
          normalize(vector);
          return vector;
      }
      // normalize: L2-normalize in place; no-op if the vector is all zeros (blank text).
  }
  ```
  Relies only on `String.hashCode()`, whose algorithm is specified by the JDK contract and therefore stable across JVM restarts — matching `LangChain4jLlmPort`'s "deterministic offline stub" precedent.
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/InMemoryVectorStoreAdapter.java` (new) -- follows `InMemoryGraphStoreAdapter`'s style: `Map<String, Map<String, EmbeddedChunk>> chunksByCorpusId = new LinkedHashMap<>()`; `persistChunks(corpusId, chunks)` puts each chunk keyed by `chunk.chunk().id()` into the corpus-scoped map (creating it if absent, via `computeIfAbsent(corpusId, k -> new LinkedHashMap<>())`); `chunks(corpusId)` returns an unmodifiable view of that map's values (or `List.of()` if absent).
- `graphrag-adapter-neo4j/src/main/java/com/graphraglens/adapter/neo4j/Neo4jVectorStoreAdapter.java` (new) -- `public class Neo4jVectorStoreAdapter extends InMemoryVectorStoreAdapter {}`, a direct compatibility alias exactly mirroring the existing `Neo4jGraphStoreAdapter extends InMemoryGraphStoreAdapter {}` (documented via Javadoc referencing AD-17's native-vector-index intent, currently backed by the same in-memory implementation as the graph store — no real Neo4j client is introduced by this story).
- `graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java` -- add, following `llmPort(...)`/`graphStorePort()`'s exact style and `LOG` usage:
  ```java
  @Bean
  public EmbeddingPort embeddingPort(@Value("${OPENAI_API_KEY:}") String openAiApiKey,
                                      @Value("${OPENAI_EMBEDDING_MODEL:text-embedding-3-small}") String openAiEmbeddingModel) {
      if (openAiApiKey != null && !openAiApiKey.isBlank()) {
          LOG.info("OPENAI_API_KEY detected — using real OpenAI-backed embedding adapter (model={})", openAiEmbeddingModel);
          return new OpenAiEmbeddingPort(openAiApiKey, openAiEmbeddingModel);
      }
      LOG.warn("OPENAI_API_KEY not set — falling back to the deterministic offline embedding stub. "
              + "Set OPENAI_API_KEY to enable real AI-driven embeddings.");
      return new LangChain4jEmbeddingPort();
  }

  @Bean
  public VectorStorePort vectorStorePort() {
      return new InMemoryVectorStoreAdapter();
  }

  @Bean
  public ConstructVectorIndex constructVectorIndex(EmbeddingPort embeddingPort, VectorStorePort vectorStorePort) {
      return new ConstructVectorIndex(embeddingPort, vectorStorePort);
  }
  ```
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- add a `private static final Logger LOG = LoggerFactory.getLogger(CorpusController.class);` field (this class has no logger today); add a `ConstructVectorIndex constructVectorIndex` field + constructor parameter (append as the last constructor argument, after `retrievalTraceStore`); in `upload()` and `useDemoDataset()`, add a call to a new `startVectorIndexConstruction(corpus)` method immediately alongside the existing `startKnowledgeGraphConstruction(corpus)` call (both dispatched, neither awaited); add:
  ```java
  private void startVectorIndexConstruction(Corpus corpus) {
      CompletableFuture.runAsync(() -> {
          try {
              constructVectorIndex.run(corpus);
          } catch (Exception ex) {
              LOG.warn("Vector index construction failed for corpus {} — this does not affect knowledge graph construction.",
                      corpus.id(), ex);
          }
      });
  }
  ```
  This method must not touch `corpusStore` or `corpusProgressService` at all, per the Boundaries.
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java`, `CorpusControllerGlobalSearchTest.java`, `CorpusControllerDriftSearchTest.java` -- update all 12 existing `new CorpusController(...)` call sites (6 + 3 + 3) to append `null` as the new trailing `ConstructVectorIndex` argument — none of these call `upload()`/`useDemoDataset()`, only `query()`, so `null` is safe.
- `graphrag-core/src/test/java/com/graphraglens/core/usecase/ConstructVectorIndexTest.java` (new) -- follows `DetectCommunitiesTest`'s style (a hand-written recording `EmbeddingPort`/`VectorStorePort` fake, no mocking framework), covering the I/O matrix rows: single short document (1 chunk, projection `[0,0]`), multi-chunk single document (ordinal + whitespace-boundary behavior), multi-document continuous ordinals, blank document skipped, all-blank corpus returns empty list without throwing.
- `graphrag-core/src/test/java/com/graphraglens/core/usecase/TwoDProjectionTest.java` (new) -- same package as `TwoDProjection` (package-private access), covering: empty input, single-vector input (`[0,0]`), a synthetic multi-point dataset with obvious variance along one axis (assert relative ordering/sign-invariant separation, not exact floating-point values, since power-iteration sign is arbitrary), and an all-identical-vectors zero-variance input (`[0,0]` for every point, no `NaN`/`Infinity`).
- `graphrag-adapter-langchain4j/src/test/java/com/graphraglens/adapter/langchain4j/OpenAiEmbeddingPortTest.java` (new) -- mirrors `OpenAiLlmPortTest`'s style: asserts blank/null API key rejection (`assertThrows(IllegalArgumentException.class, ...)`); does not make a real network call (no test exercises `embed(...)` against the live API, matching `OpenAiLlmPortTest`'s existing scope of never invoking the network).
- `graphrag-adapter-langchain4j/src/test/java/com/graphraglens/adapter/langchain4j/LangChain4jEmbeddingPortTest.java` (new) -- asserts determinism (same text embeds to the same vector across two calls/instances), that different texts produce different vectors, that blank/null text returns the all-zero vector without throwing, and that every returned vector is L2-normalized (norm ≈ 1) except the all-zero blank-text case.

## Tasks & Acceptance

**Execution:**
- [ ] `graphrag-core/.../domain/Chunk.java`, `EmbeddedChunk.java` -- new domain records -- the data shapes every other new type builds on.
- [ ] `graphrag-core/.../port/EmbeddingPort.java`, `VectorStorePort.java` -- new ports -- the framework-free seams `ConstructVectorIndex` depends on, mirroring `LlmPort`/`GraphStorePort`.
- [ ] `graphrag-core/.../usecase/ConstructVectorIndex.java`, `TwoDProjection.java` -- the story's core deliverable -- chunks, embeds, projects once, and persists a Corpus's vector index.
- [ ] `graphrag-adapter-langchain4j/.../OpenAiEmbeddingPort.java`, `LangChain4jEmbeddingPort.java` -- real vs. deterministic-offline `EmbeddingPort` adapters, mirroring `OpenAiLlmPort`/`LangChain4jLlmPort`.
- [ ] `graphrag-adapter-neo4j/.../InMemoryVectorStoreAdapter.java`, `Neo4jVectorStoreAdapter.java` -- `VectorStorePort` adapters, mirroring `InMemoryGraphStoreAdapter`/`Neo4jGraphStoreAdapter`.
- [ ] `graphrag-web/.../config/ParserConfig.java` -- new `embeddingPort`/`vectorStorePort`/`constructVectorIndex` beans, mirroring the existing `llmPort`/`graphStorePort` wiring exactly.
- [ ] `graphrag-web/.../CorpusController.java` -- inject `ConstructVectorIndex`, add the independent `startVectorIndexConstruction(...)` dispatch in `upload()`/`useDemoDataset()`, add a `Logger` field -- wires vector-index construction in as a genuinely parallel, non-gating task.
- [ ] `graphrag-web/src/test/.../CorpusControllerTest.java`, `CorpusControllerGlobalSearchTest.java`, `CorpusControllerDriftSearchTest.java` -- update all 12 constructor call sites -- keeps existing tests compiling/passing.
- [ ] `graphrag-core/src/test/.../ConstructVectorIndexTest.java`, `TwoDProjectionTest.java` -- new unit tests -- proves the I/O matrix's chunking/projection rows.
- [ ] `graphrag-adapter-langchain4j/src/test/.../OpenAiEmbeddingPortTest.java`, `LangChain4jEmbeddingPortTest.java` -- new unit tests -- proves the two adapters' validation/determinism behavior.

**Acceptance Criteria:**
- Given a Corpus is queued for ingestion (upload or demo dataset), when `startKnowledgeGraphConstruction`/`startVectorIndexConstruction` are both dispatched, then chunking, embedding (via `EmbeddingPort`), and persistence (via `VectorStorePort`) happen for that Corpus, independently of and concurrently with Entity/Relationship extraction.
- Given a Corpus's chunk embeddings, when `ConstructVectorIndex.run(corpus)` executes, then a single 2D projection is computed over all chunk embeddings in that one call and persisted alongside each chunk's embedding inside `EmbeddedChunk.projection()` — never recomputed by any other code path in this story.
- Given `EmbeddingPort.embed(...)` throws during a real `upload()`/`useDemoDataset()` flow, when the failure occurs, then it is caught and logged only — knowledge-graph construction (and vice versa) is completely unaffected, and neither `corpusStore` nor `corpusProgressService` observe the vector-index failure.
- Given `OPENAI_API_KEY` is unset, when the Spring context starts, then `EmbeddingPort` resolves to the deterministic offline `LangChain4jEmbeddingPort` stub — no network access, existing/new tests keep passing without credentials.
- Given the existing `CorpusController` unit tests, when they run after this story's constructor-signature change, then all previously passing assertions (LOCAL/GLOBAL/DRIFT query behavior) still pass unchanged.

## Spec Change Log

- 2026-09-21 — initial spec authored for Story 8.1, scoped strictly to vector-index construction (chunk → embed → project-once → persist), decoupled from Entity/Relationship extraction; no UI, HTTP endpoint, or Neo4j client added.
- 2026-09-21 — implemented per Code Map; review pass found and patched a latent NaN/Infinity-propagation gap in `TwoDProjection` plus four test-coverage gaps (failure-isolation between the two `CompletableFuture` tasks, multi-chunk projection index-alignment, `InMemoryVectorStoreAdapter` direct coverage, hard-cut chunking fallback); two low-severity, pre-existing-pattern findings deferred (see frontmatter).

## Review Triage Log

### 2026-09-21 — Review pass
- verdicts: 9 findings — high 1, medium 6, low 2, false 0, maybe-false 0
- findings:
  - `[high]` `[patch]` verification-gap (merged with blind-hunter's matching finding): the spec's central AC — a vector-index failure must never touch `corpusStore`/`corpusProgressService`, and vice versa, since the two `CompletableFuture` tasks are fully independent — was true only by code inspection, with no test proving it. Fixed: added `aVectorIndexConstructionFailureDoesNotAffectKnowledgeGraphConstructionOrCorpusProgressEvents` to `CorpusControllerTest.java`, spying `ConstructVectorIndex` to throw and asserting `corpusStore.status(corpusId) == READY`, `ingestion-complete` still emitted, and no `error` event recorded.
  - `[medium]` `[patch]` edge-case-hunter: `TwoDProjection`'s zero-variance guard (`norm <= EPSILON`) silently fails to trip for `NaN` (IEEE 754: `NaN <= x` is always `false`), so a `NaN`/`Infinity`-containing embedding would propagate `NaN` into the persisted projection instead of degenerating safely. Fixed: guard now also checks `!Double.isFinite(norm)`; added `degradesToZeroInsteadOfNaNOrInfinityWhenAnEmbeddingContainsNonFiniteValues` to `TwoDProjectionTest.java`.
  - `[medium]` `[patch]` verification-gap: no test proved chunk-to-projection index alignment for n≥2 chunks (only that *some* projection existed, not that chunk *i* got `projections[i]`, not a shuffled one). Fixed: added `assignsEachChunkTheProjectionCoordinateMatchingItsOwnEmbeddingNotAShuffledOne` to `ConstructVectorIndexTest.java` using three embeddings with a known, distinguishable principal-axis outcome.
  - `[medium]` `[patch]` verification-gap: `InMemoryVectorStoreAdapter` (and its `Neo4jVectorStoreAdapter` alias) had zero direct tests, unlike its `InMemoryGraphStoreAdapter` sibling. Fixed: added `InMemoryVectorStoreAdapterTest.java` covering corpus-scoped persist/read, unknown-corpus empty read, same-chunk-id upsert-not-duplicate, and null/blank-corpusId/null-chunks no-throw guards.
  - `[medium]` `[patch]` verification-gap: the chunking algorithm's "hard-cut at CHUNK_SIZE when no whitespace is found" fallback branch (Code Map) was never exercised — all existing chunking tests used content with a conveniently-placed space. Fixed: added `hardCutsAtChunkSizeWhenNoWhitespaceIsFoundWithinTheLookbackWindow` to `ConstructVectorIndexTest.java` using a 700-character single "word" with no spaces at all.
  - `[low]` `[defer]` edge-case-hunter: `InMemoryVectorStoreAdapter`'s unsynchronized `LinkedHashMap` under concurrent uploads — verified this exactly mirrors the pre-existing, already-accepted `InMemoryGraphStoreAdapter` pattern; not a new regression (recorded in frontmatter `deferred`).
  - `[low]` `[defer]` verification-gap: `ParserConfig.embeddingPort(...)`'s `OPENAI_API_KEY`-set (real adapter) branch has no test — mirrors the pre-existing, already-accepted gap on `llmPort(...)`'s equivalent branch (recorded in frontmatter `deferred`).
  - `[medium]` `[reject — already true, not a gap]` blind-hunter and edge-case-hunter both independently confirmed the two `CompletableFuture.runAsync` tasks share no mutable state and are symmetric in exception handling on code inspection alone — subsumed by the high-severity finding above once the missing test was added; no separate action needed.
  - intent-alignment layer: no findings — verbatim AC (chunk → embed via `EmbeddingPort` → persist via `VectorStorePort`; 2D projection computed once, never per-query; no mutual gating between vector-index and knowledge-graph construction) independently traced and confirmed satisfied at the actual runtime-wiring level, not just against this spec's own wording.

## Design Notes

**Why the projection lives inside `graphrag-core` as a package-private helper, not its own port:** The AC only requires the projection be "computed once and persisted alongside" the embeddings — it is a pure, deterministic derivation of the embeddings already being persisted, not an external capability a different adapter could meaningfully swap out (unlike `EmbeddingPort`/`VectorStorePort`, which really do have distinct real/stub or in-memory/future-Neo4j implementations). Keeping it as an internal, directly-unit-tested helper avoids inventing a port with only one real implementation.

**Why `VectorStorePort`'s in-memory adapter is the only adapter this story ships, mirroring `GraphStorePort`:** `Neo4jGraphStoreAdapter` has been a bare alias of `InMemoryGraphStoreAdapter` since it was introduced — the app has never actually wired a live Neo4j connection despite the module name and `docker-compose.yml`'s Neo4j service. Introducing a genuinely different, real-Neo4j-backed `VectorStorePort` implementation in this story would be inconsistent with that established precedent and would add scope (a live database dependency in tests) the AC does not require ("implemented by `graphrag-adapter-neo4j`'s native vector index... no new container" is satisfied by the same in-memory-adapter-first pattern already used for the graph store).

**Why chunking is fixed-size character windows, not sentence/token-based:** No existing precedent in the codebase chunks text at all (`LangChain4jLlmPort`'s sentence-splitting regex is for entity extraction, not chunking); a simple, deterministic, whitespace-boundary-respecting fixed-size window keeps this story's scope tight and fully unit-testable without adding a tokenizer dependency, consistent with the codebase's zero-heavy-dependency philosophy.

## Auto Run Result

**Summary:** Added a fully independent vector-index construction path alongside the existing knowledge-graph pipeline: new `Chunk`/`EmbeddedChunk` domain records, `EmbeddingPort`/`VectorStorePort` ports, `ConstructVectorIndex` use case (deterministic fixed-size chunking with whitespace-boundary preference and hard-cut fallback), a self-contained `TwoDProjection` PCA-via-power-iteration helper computed exactly once per `run(Corpus)` call, real (`OpenAiEmbeddingPort`) and deterministic-offline (`LangChain4jEmbeddingPort`) embedding adapters mirroring the existing `LlmPort` real/stub pattern, and an in-memory `VectorStorePort` adapter (plus a `Neo4jVectorStoreAdapter` alias) mirroring the existing `GraphStorePort` adapter precedent. Wired into `CorpusController` as a second, independently-dispatched `CompletableFuture.runAsync` task in both `upload()` and `useDemoDataset()`, with failures caught and logged only — never touching `corpusStore`/`corpusProgressService`.

**Files changed:**
- `graphrag-core/.../domain/Chunk.java`, `EmbeddedChunk.java` (new) — vector-index domain records.
- `graphrag-core/.../port/EmbeddingPort.java`, `VectorStorePort.java` (new) — new framework-free ports.
- `graphrag-core/.../usecase/ConstructVectorIndex.java` (new) — chunk → embed → project-once → persist orchestration.
- `graphrag-core/.../usecase/TwoDProjection.java` (new) — package-private PCA-via-power-iteration helper; patched with an explicit `Double.isFinite` guard during review.
- `graphrag-adapter-langchain4j/.../OpenAiEmbeddingPort.java`, `LangChain4jEmbeddingPort.java` (new) — real/offline-stub `EmbeddingPort` adapters.
- `graphrag-adapter-neo4j/.../InMemoryVectorStoreAdapter.java`, `Neo4jVectorStoreAdapter.java` (new) — `VectorStorePort` adapters.
- `graphrag-web/.../config/ParserConfig.java` — new `embeddingPort`/`vectorStorePort`/`constructVectorIndex` beans.
- `graphrag-web/.../CorpusController.java` — new `Logger`, `ConstructVectorIndex` dependency, `startVectorIndexConstruction(...)` dispatched independently in `upload()`/`useDemoDataset()`.
- `graphrag-web/src/test/.../CorpusControllerTest.java`, `CorpusControllerGlobalSearchTest.java`, `CorpusControllerDriftSearchTest.java` — 12 constructor call sites updated; `CorpusControllerTest.java` also gained the failure-isolation test (review patch).
- `graphrag-core/src/test/.../ConstructVectorIndexTest.java`, `TwoDProjectionTest.java` (new) — unit coverage, extended during review with index-alignment, hard-cut, and NaN/Infinity cases.
- `graphrag-adapter-langchain4j/src/test/.../OpenAiEmbeddingPortTest.java`, `LangChain4jEmbeddingPortTest.java` (new) — adapter unit coverage.
- `graphrag-adapter-neo4j/src/test/.../InMemoryVectorStoreAdapterTest.java` (new, review patch) — direct adapter coverage.
- `_bmad-output/implementation-artifacts/spec-8-1-build-the-vector-index-alongside-the-knowledge-graph.md` — this spec (status, change log, triage log, deferred list, this section).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — story entry updated to `done`.

**Review findings breakdown (9 findings, 4 layers):**
- Patched (5: 1 high, 4 medium): failure-isolation between the two `CompletableFuture` tasks was untested (now covered); `TwoDProjection`'s zero-variance guard didn't catch `NaN`/`Infinity` (now guarded and tested); chunk-to-projection index-alignment untested for n≥2 (now covered); `InMemoryVectorStoreAdapter` had zero direct tests (now covered); the hard-cut chunking fallback branch was never exercised (now covered).
- Deferred (2, both low): `InMemoryVectorStoreAdapter`'s unsynchronized `LinkedHashMap` under concurrency (mirrors the pre-existing `InMemoryGraphStoreAdapter` pattern, not a new regression); `ParserConfig.embeddingPort(...)`'s real-adapter branch untested (mirrors the pre-existing, already-accepted `llmPort` gap).
- Rejected: none — all other layer observations were either subsumed into the findings above or explicitly confirmed as correct/non-issues (intent-alignment layer raised zero findings).

**Follow-up review recommendation:** `false` — every patchable finding was fixed directly and re-verified; the two deferred items are both low-severity, explicitly-verified pre-existing patterns from before this story (not introduced or worsened here), so another full review pass is not expected to surface anything new.

**Verification performed:**
- Independently re-read every changed/new file's diff against the spec's Code Map/Boundaries before any review layer ran (not trusting the implementation subagent's self-report).
- Ran the full affected-module test suite (`graphrag-core`, `graphrag-adapter-langchain4j`, `graphrag-adapter-neo4j`, `graphrag-web`, including all pre-existing UI tests) twice — once before review patches and once after — confirmed via `target/surefire-reports/*.txt`, not console tail: 0 failures/0 errors across all 21 test classes both times (core: 46 tests; langchain4j: 12 tests; neo4j: 7 tests; web: 46 tests, including the 6 UI Playwright suites).
- Confirmed all 9 I/O-matrix rows are exercised by name: single-short-document zero projection, multi-chunk whitespace-boundary + hard-cut fallback, multi-document continuous ordinals, blank-document skip, all-blank-corpus empty result, identical-embedding zero-variance projection, NaN/Infinity-embedding safe degeneration, vector-index-failure isolation from knowledge-graph construction, and `OPENAI_API_KEY`-unset deterministic-stub fallback.

**Residual risks:** the two deferred low-severity items (pre-existing, unrelated to this story's introduced surface).

## Verification

**Commands:**
- `mvn -pl graphrag-core -am test -Dtest=ConstructVectorIndexTest,TwoDProjectionTest,DetectCommunitiesTest,IngestCorpusTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: all pass, confirming the new chunk/embed/project/persist behavior and that existing core use cases are unaffected.
- `mvn -pl graphrag-adapter-langchain4j -am test -Dtest=OpenAiEmbeddingPortTest,LangChain4jEmbeddingPortTest,OpenAiLlmPortTest,LangChain4jLlmPortTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: all pass, confirming the new embedding adapters' validation/determinism and that the existing LLM adapters are unaffected.
- `mvn -pl graphrag-web -am test -Dtest=CorpusControllerTest,CorpusControllerGlobalSearchTest,CorpusControllerDriftSearchTest -Dsurefire.failIfNoSpecifiedTests=false` -- expected: all pass, confirming the constructor-signature change did not break existing LOCAL/GLOBAL/DRIFT query behavior.
