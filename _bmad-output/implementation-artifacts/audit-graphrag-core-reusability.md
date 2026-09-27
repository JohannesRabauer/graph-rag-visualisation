# Audit: Is `graphrag-core` Actually a Reusable Standalone Library?

**Related:** GitHub #24 — "Audit: is graphrag-core actually a well-documented,
reusable standalone library?"

**Scope:** This is a findings-and-punch-list document only. No code, `pom.xml`,
or documentation file inside `graphrag-core/` was changed to produce it. No
follow-up issue has been filed; that is deliberately deferred until the two
Open Questions below have been answered (see the end of this document), so
that a follow-up isn't scoped against an unanswered branding/publishing
decision.

**Findings gathered against commit `2bd8224` (2026-09-27).** Every count below
(Javadoc coverage, test coverage, etc.) reflects the source tree at that
commit and will go stale as the code changes — re-run the checks in the
Evidence Index below to refresh any of them.

**This audit addresses all five of GitHub #24's own numbered check areas:**
(1) architectural isolation, including cross-module imports and enforcer-rule
bypass; (2) documentation gaps; (3) naming/branding; (4) actual reusability,
including publishing and use-case API-sequencing coherence; (5) test coverage
as documentation. The mapping from each area to the findings below is in the
Evidence Index's "GitHub #24's own five check areas" row.

## Executive Summary

`graphrag-core`'s hexagonal separation is real and tool-enforced: it has zero
runtime dependencies beyond JUnit, a `maven-enforcer-plugin` rule that bans
Spring/Neo4j-driver/LangChain4j at build time, and — verified directly for
this audit, not assumed — no class anywhere under `graphrag-core/src/main/java`
imports from `graphrag-adapter-*` or `graphrag-web`, and the enforcer rule has
not been bypassed via `<exclusions>` anywhere in the reactor. Two-thirds of its
use cases (10 of 15) have direct, behavior-driven tests.

But "cleanly separable" is not the same as "actually reusable by another
project today," and on that count `graphrag-core` currently fails in ways
that block reuse outright, not just make it inconvenient: there is no
`LICENSE` anywhere in this repository's history, no version has ever been
released (it has only ever been `0.1.0-SNAPSHOT`), and there is no publishing
mechanism (`distributionManagement`, source/javadoc jar plugins) of any kind.
Below that hard blocker sits a real adoption-friction layer: `graphrag-core`
has no module-local `README.md`, no `package-info.java` anywhere in its three
packages, and Javadoc coverage that is decent on the domain/use-case layers
but genuinely thin on the port interfaces — the exact surface a new consumer
would need to implement against. Two of the five use-case sequencing
dependencies GitHub #24 asked about are real and currently undocumented
outside the source code itself.

Two judgment calls this audit cannot make unilaterally — whether the
`com.graphraglens` branding should become generic, and whether "reusable"
requires actually publishing the artifact somewhere — are named explicitly at
the end, with tradeoffs, not decided here.

## Findings

### Confirmed Solid

1. **Framework-free core, enforced by tooling, not just convention.**
   `graphrag-core/pom.xml` declares a `maven-enforcer-plugin` execution
   (`ban-framework-dependencies`, phase `validate`) with a `bannedDependencies`
   rule excluding `org.springframework*:*`, `org.neo4j.driver:*`, and
   `dev.langchain4j:*`. Its only declared dependencies are the JUnit BOM and
   `junit-jupiter` (test scope). A violation fails the build at `validate`,
   before compilation — this is a hard gate, not a lint warning.

2. **No cross-module imports — verified directly, not assumed.**
   `grep -rn "com.graphraglens.adapter\|graphrag-adapter\|graphrag.adapter" graphrag-core/src/main/java`
   and the equivalent alternation for `graphrag-web`/`com.graphraglens.web`
   under the same tree both return zero real imports. The only hits for the
   web-module search are four Javadoc *prose* mentions of `graphrag-web` (in
   `IngestCorpus`, `UnsupportedFileTypeException`, `RetrievalTrace`, `Corpus`)
   explaining a boundary decision in comments — not code that imports
   anything. `graphrag-core`'s own module dependency graph also does not
   declare any adapter or web module as a dependency, so this is enforced at
   compile time in addition to being confirmed empty by inspection.

3. **The enforcer rule has not been quietly bypassed.**
   `grep -rn "<exclusions>" --include="pom.xml" .` across the entire reactor
   (`pom.xml`, `graphrag-core/pom.xml`, `graphrag-adapter-neo4j/pom.xml`,
   `graphrag-adapter-langchain4j/pom.xml`, `graphrag-adapter-parsing/pom.xml`,
   `graphrag-web/pom.xml`) returns zero matches. No dependency anywhere in
   this repository excludes a transitive dependency to sneak a banned group
   back in.

4. **Class-level Javadoc is present on most of the domain and use-case
   layers.** **13 of 15** classes in `usecase/` have class-level Javadoc
   (missing only on `ConstructVectorIndex` and `TwoDProjection`), and
   **12 of 14** classes in `domain/` have it (missing only on `Chunk` and
   `EmbeddedChunk`, both terse, self-explanatory record types).

5. **Real, substantive test coverage — a genuine strength, not a checkbox.**
   11 test files exist under `graphrag-core/src/test/java`, exercising **10
   of the 15 non-DTO use-case classes directly** by name
   (`AnswerDriftSearchTest`, `AnswerGlobalSearchTest`, `AnswerLocalSearchTest`,
   `AnswerVectorBaselineTest`, `ConstructVectorIndexTest`,
   `DetectCommunitiesTest`, `ExtractEntitiesAndRelationshipsTest`,
   `IngestCorpusTest`, `KeywordMatcherTest`, `TwoDProjectionTest`), plus one
   domain test (`RetrievalTraceTest`). Test names are behavior-driven
   (e.g. covering the "no communities yet" / "no chunks yet" no-answer
   paths), which does real work as living documentation of expected
   behavior. `BuildKnowledgeGraph` has no test of its own, but it is a thin
   subclass of the already-tested `ExtractEntitiesAndRelationships`.

### Actually Missing

1. **No `LICENSE` file, anywhere, ever.** `find . -iname "LICENSE*"`
   (excluding `target/`) returns nothing, and `git log --all -- LICENSE`
   returns no commits across the entire history. Without a license, no
   outside party has any legal basis to reuse this code at all — this is a
   hard blocker, not friction, regardless of how the branding/publishing
   questions below get answered.

2. **No released version — `0.1.0-SNAPSHOT` only.** Both `pom.xml` (root,
   `groupId com.graphraglens`) and `graphrag-core/pom.xml` (inherited) are
   pinned at `0.1.0-SNAPSHOT`. A SNAPSHOT version is, by Maven convention,
   not a stable, citable dependency coordinate — anyone depending on it today
   is depending on a moving target with no changelog to tell them what moved.
   Relatedly, there is no `CHANGELOG.md`/release-notes mechanism anywhere in
   the repository, so even after a real version is cut, a consumer upgrading
   between versions would have nothing to check for breaking changes.

3. **No publishing setup of any kind.** Neither `pom.xml` nor
   `graphrag-core/pom.xml` declares `<distributionManagement>`, a source-jar
   plugin, or a javadoc-jar plugin. The only way another project can depend
   on `graphrag-core` today is cloning this repository and running
   `mvn install` into their own local `~/.m2` — there is no Maven Central,
   GitHub Packages, or even a versioned GitHub Release with a jar attached.

4. **No module-local documentation of any kind.** There is no `README.md`
   inside `graphrag-core/` (confirmed by directory listing). The repository
   root's own `README.md` (2421 bytes) documents the *application* — Docker
   Compose run instructions, a module table describing all five modules at a
   glance — not `graphrag-core` as a standalone dependency; it has no "add
   this as a Maven dependency" instructions and no library-usage code
   example. By this audit's own standard for what counts as module-local
   documentation, the root README mentioning the module does not substitute
   for `graphrag-core` having its own.

   One specific, concrete fact this future README should state explicitly:
   **the module requires JDK 25.** The root README does say this today, but
   only for the application as a whole — a consumer evaluating `graphrag-core`
   in isolation (e.g. via Maven Central metadata, or browsing just this
   module) would not see it, and JDK 25 is recent enough that most consuming
   projects will not yet be on it. This is a real adoption consideration,
   not a formality, and belongs in `graphrag-core`'s own README once one
   exists — folded into that punch-list item below rather than filed as a
   separate one.

5. **No `package-info.java` anywhere.**
   `find graphrag-core/src/main/java -name "package-info.java"` returns
   nothing. None of the three packages (`domain`, `port`, `usecase`) has a
   package-level Javadoc summary of its role.

6. **Port-interface Javadoc is genuinely thin — the exact surface a new
   consumer implements against.** Of the 5 port interfaces
   (`DocumentParserPort`, `EmbeddingPort`, `GraphStorePort`, `LlmPort`,
   `VectorStorePort`), only 3 (`DocumentParserPort`, `GraphStorePort`,
   `LlmPort`) have class-level Javadoc; `EmbeddingPort` and `VectorStorePort`
   have none.

   More significant: of the 6 *abstract* (must-implement) methods across all
   5 interfaces — `DocumentParserPort#supports`, `EmbeddingPort#embed`,
   `GraphStorePort#persistEntities`, `GraphStorePort#persistRelationships`,
   `LlmPort#extract`, `VectorStorePort#persistChunks` — only **1 of 6**, in
   only **1 of the 5 interfaces**, has `@param`/`@return` Javadoc
   (`DocumentParserPort#supports(String)`). The other 5 abstract methods a
   new adapter author must implement have no parameter, return, nullability,
   or contract documentation at all — no explanation of what a "scoped" read
   like `persistEntities(String corpusId, ...)` means, whether `null` is an
   acceptable argument or return, or what happens on a duplicate `persist`
   call. (Several *default* methods on these same interfaces — e.g.
   `LlmPort#synthesizeFromChunks`, `DocumentParserPort#extract` — do have
   full `@param`/`@return` Javadoc; it is specifically the must-implement
   abstract contract that is under-documented.)

7. **No generated/published Javadoc site.** There is no `javadoc:javadoc`
   or `maven-javadoc-plugin` wiring anywhere in the reactor, so even the
   Javadoc that does exist in source is not browsable anywhere without
   cloning the repository and generating it manually.

8. **Undocumented implicit use-case ordering — confirmed real, not
   hypothetical.** GitHub #24 explicitly asked whether use cases have
   implicit sequencing that isn't written down anywhere a new consumer would
   find it. Direct inspection confirms this dependency chain is real:
   - `DetectCommunities.detect(Corpus)` calls
     `graphStorePort.entities(corpus.id())` and
     `graphStorePort.relationships(corpus.id())` — it reads graph data that
     only exists once `ExtractEntitiesAndRelationships`/`BuildKnowledgeGraph`
     has already run and persisted it. If nothing was persisted yet, it
     degrades gracefully (returns `List.of()`) rather than throwing, so the
     dependency is silent, not enforced.
   - `AnswerGlobalSearch.answer(...)` and `AnswerDriftSearch` both read
     already-persisted Community summaries via
     `GraphStorePort#communities(String)` and explicitly document (in Javadoc
     prose, on `AnswerGlobalSearch` itself) that they never trigger
     `DetectCommunities` themselves — meaning a consumer who calls
     `AnswerGlobalSearch` before ever running `DetectCommunities` gets a
     defined "no communities yet" answer, not an error, but only if they
     already know to expect that outcome.
   - `AnswerVectorBaseline` similarly reads already-persisted
     `EmbeddedChunk`s via `VectorStorePort`, meaning it depends on
     `ConstructVectorIndex` having already run for that corpus.

   Each individual dependency *is* mentioned in that use case's own Javadoc
   (a genuine strength — this is not undocumented in the sense of "unknown to
   anyone"), but there is no single place — no README, no package-info, no
   diagram — that lays out the overall pipeline order
   (`IngestCorpus` → `ExtractEntitiesAndRelationships`/`BuildKnowledgeGraph` →
   `ConstructVectorIndex` and/or `DetectCommunities` → the `Answer*` use
   cases) for a new consumer to find without reading every use-case class's
   Javadoc individually and reconstructing the graph themselves. That
   reconstruction burden is the actual gap.

9. **Test coverage is real, but not a substitute for worked usage
   examples.** The 11 test files are genuine, thorough tests of behavior —
   but they are white-box tests written against this codebase's own
   internal fixtures and helper builders, not black-box, copy-pasteable
   "here is how an external project wires an `EmbeddingPort` implementation
   and calls `ConstructVectorIndex`" examples. A new consumer evaluating
   whether to depend on `graphrag-core` would still need to read test
   internals to reverse-engineer a usage pattern; this does not close the
   "no usage examples" gap GitHub #24 raised, it only softens it. One test,
   `IngestCorpusTest` (69 lines, the shortest of the 11), is simple and
   self-contained enough — one inline `DocumentParserPort` lambda, two
   `UploadedDocument`s, one `ingest(...)` call — to point early adopters at
   as a zero-effort stopgap example before a real README exists.

10. **`com.graphraglens` branding runs through every package and the Maven
    coordinates themselves** (see Open Questions below) — not a defect by
    itself, but relevant context for the punch list: any README, package-info,
    or publishing work below will need to either embrace this branding or be
    written knowing it may change. Relatedly, `graphrag-core`'s own Javadoc
    prose names `graphrag-web` by name in four places (Finding 2) — a minor
    portability smell of its own: a standalone library's source comments
    referencing one specific consuming application by name is a small tell
    that it wasn't originally written with an external consumer in mind.

## Prioritized Punch List

Ordered by how much each item blocks actual reuse — not by effort.

1. **(Blocking) Add a `LICENSE` file.** Without one, no external project has
   any legal basis to depend on this code, regardless of how well documented
   it is. This is the single highest-priority item and is independent of
   both open questions below.

2. **(Blocking) Cut a real, non-`SNAPSHOT` release version**, and put a
   `CHANGELOG.md`/release-notes mechanism in place alongside it so future
   version bumps are diffable by a consumer. Depends on the publish-or-not
   Open Question only in *how* the version is distributed, not on whether a
   stable version number should exist at all — `0.1.0-SNAPSHOT` blocks
   dependable reuse either way.

3. **(Adoption friction) Add `graphrag-core/README.md`.** Should cover: what
   the module is, the JDK 25 requirement stated explicitly at the
   module level (not only inherited from the root README), how to wire each
   of the 5 ports, and at least one minimal end-to-end usage example that
   lays out the use-case pipeline order identified in Finding 8
   (ingest → extract/build graph → detect communities / build vector index →
   answer) so a new consumer doesn't have to reconstruct it from individual
   class Javadoc.

4. **(Adoption friction) Fill in missing port-interface Javadoc**, especially
   `@param`/`@return`/nullability/contract documentation on the 5
   currently-undocumented abstract methods (Finding 6) and class-level
   summaries for `EmbeddingPort` and `VectorStorePort`. This is the literal
   SDK surface a new adapter author implements against.

5. **(Adoption friction) Wire up `maven-javadoc-plugin`** (source-jar and
   javadoc-jar generation) so the Javadoc that exists — and the Javadoc added
   in item 4 — is actually browsable/publishable, not just readable in the
   source tree. This item is naturally sequenced after item 4, and is also a
   prerequisite for a real Maven Central/GitHub Packages publish if the
   publish-or-not Open Question resolves that way.

6. **(Polish) Add `package-info.java` to `domain`, `port`, and `usecase`.**
   Lower priority than method-level Javadoc because a consumer reads
   individual classes far more often than package summaries, but still a
   real, currently-total gap (0 of 3 packages).

7. **(Polish) `Chunk` and `EmbeddedChunk` class-level Javadoc**, and the two
   remaining use-case classes without it (`ConstructVectorIndex`,
   `TwoDProjection`) — the last two gaps in an otherwise well-covered layer.

## Open Questions for the User

These two decisions are the maintainer's to make, not this audit's. Both are
named here as questions with tradeoffs; neither is answered or defaulted.

### 1. Branding: keep `com.graphraglens`, or adopt a generic library identity?

`graphrag-core`'s Maven `groupId` (`com.graphraglens`) and every Java package
under it (`com.graphraglens.core.*`) are named after this specific
application, "GraphRAG Lens," not after a generic library identity.

- **Keep `com.graphraglens`:** No renaming work, no package-move churn for
  any existing code or tests, and it's honest about the module's origin as
  this app's own core rather than implying a broader, independently-governed
  project. Tradeoff: a from-scratch external consumer sees `graphraglens` in
  every import and may reasonably assume this is app-specific rather than a
  general-purpose library, which could suppress adoption regardless of how
  good the documentation is.
- **Adopt a generic identity** (e.g. a new `groupId`/package root not tied to
  the "Lens" product name): Signals "this is meant to be depended on
  independently" more clearly to a new consumer, and decouples the library's
  identity from this specific application's branding if the app's name ever
  changes. Tradeoff: real rename churn across every source file, the Maven
  coordinates, and any already-published artifact (compounding with Open
  Question 2 below — a rename after a real release is a breaking change for
  anyone already depending on the old coordinates).

### 2. Publishing: does "reusable" require actually publishing, or is local/source-only reuse sufficient?

Today the only way to depend on `graphrag-core` is cloning this repository
and running `mvn install` into a local `~/.m2`. There is no
`distributionManagement`, no Maven Central/GitHub Packages presence, and no
versioned GitHub Release with a jar attached.

- **Local/source-only reuse is sufficient for now:** No publishing
  infrastructure to build or maintain (credentials, signing, a release
  pipeline), and the module is already usable today by anyone willing to
  clone-and-install. Tradeoff: this is a meaningfully higher-friction
  onboarding step than `implementation("com.example:graphrag-core:1.0.0")`,
  and it means every consumer is pinned to whatever commit they happened to
  clone rather than a citable, immutable version — closer to "the code is
  cleanly separable" than to "reusable" in the everyday sense most
  developers mean by that word.
- **"Reusable" requires actually publishing somewhere** (Maven Central,
  GitHub Packages, or at minimum a versioned GitHub Release with the jar
  attached): Matches what most developers actually mean when they call a
  module "a reusable library" and unblocks dependency-manager-based
  consumption. Tradeoff: real ongoing maintenance surface — release
  versioning discipline, a publishing credential/pipeline to keep working,
  and (per Open Question 1) a much higher cost to renaming or restructuring
  packages after the first real release, since external consumers would then
  depend on those exact coordinates.

## Evidence Index

| Claim | Command / inspection used |
| --- | --- |
| Enforcer bans Spring/Neo4j-driver/LangChain4j at `validate` | Read `graphrag-core/pom.xml`, `<execution id="ban-framework-dependencies">` |
| No cross-module imports from `graphrag-core` into adapters/web | `grep -rn "com.graphraglens.adapter\|graphrag-adapter\|graphrag.adapter" graphrag-core/src/main/java` (0 hits); `grep -rn "graphrag.web\|com\.graphraglens\.web" graphrag-core/src/main/java` (4 hits, all Javadoc prose, no `import`) |
| No `<exclusions>` bypass of the enforcer rule anywhere in the reactor | `grep -rn "<exclusions>" --include="pom.xml" .` across all 6 reactor `pom.xml` files (0 hits) |
| No `LICENSE` file, ever | `find . -iname "LICENSE*"` (excl. `target/`, 0 hits); `git log --all -- LICENSE` (0 commits) |
| `0.1.0-SNAPSHOT` only, no `<distributionManagement>`/source-jar/javadoc-jar plugin | Read root `pom.xml` and `graphrag-core/pom.xml` in full; `grep -n "distributionManagement\|licenses\|<scm>\|<developers>\|<url>\|source-jar\|javadoc" pom.xml graphrag-core/pom.xml` (0 hits) |
| No module-local README | `ls graphrag-core/` (no `README.md`); read root `README.md` in full (2421 bytes, app-level Docker/module-table content, no library-usage instructions) |
| No `package-info.java` | `find graphrag-core/src/main/java -name "package-info.java"` (0 hits) |
| 13 of 15 `usecase/` classes have class-level Javadoc | Read all 15 files in `graphrag-core/src/main/java/com/graphraglens/core/usecase/`; missing only on `ConstructVectorIndex`, `TwoDProjection` |
| 12 of 14 `domain/` classes have class-level Javadoc | Read all 14 files in `graphrag-core/src/main/java/com/graphraglens/core/domain/`; missing only on `Chunk`, `EmbeddedChunk` |
| 3 of 5 port interfaces have class-level Javadoc | Read all 5 files in `graphrag-core/src/main/java/com/graphraglens/core/port/`; `DocumentParserPort`, `GraphStorePort`, `LlmPort` have it, `EmbeddingPort`, `VectorStorePort` do not |
| 1 of 6 abstract port methods (1 of 5 interfaces) has `@param`/`@return` Javadoc | Same 5 files, read in full: abstract methods are `DocumentParserPort#supports` (documented), `EmbeddingPort#embed`, `GraphStorePort#persistEntities`, `GraphStorePort#persistRelationships`, `LlmPort#extract`, `VectorStorePort#persistChunks` (all undocumented) |
| 10 of 15 use-case classes have a direct test | `find graphrag-core/src/test -name "*.java"` (11 files) cross-checked by name against the 15 `usecase/` classes |
| `DetectCommunities`/`AnswerGlobalSearch`/`AnswerVectorBaseline` implicit ordering | Read `DetectCommunities.java` (calls `graphStorePort.entities/relationships(corpusId)`), `AnswerGlobalSearch.java` (reads `graphStorePort.communities(corpusId)`, Javadoc states it never triggers `DetectCommunities`), `AnswerVectorBaseline.java` (reads persisted `EmbeddedChunk`s via `VectorStorePort`) |
| GitHub #24's own five check areas — verified read directly, not via the epic's condensed retelling | `issue_read` on `JohannesRabauer/graph-rag-visualisation#24`: (1) architectural isolation/no cross-module imports → Confirmed Solid 1–3; (2) documentation gaps → Actually Missing 4–7; (3) naming/branding → Confirmed Solid 10, Open Question 1; (4) actual reusability incl. publishing and API-sequencing → Actually Missing 1–3, 8, Open Question 2; (5) test coverage as documentation → Confirmed Solid 5, Actually Missing 9 |
| JDK 25 requirement stated only at root, not module level | Read root `README.md`'s "Requirements" section (module-level, mentions JDK 25); confirmed `graphrag-core/` has no README of its own to state it independently |
