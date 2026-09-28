# Version / Reality-Check Review — ARCHITECTURE-SPINE.md

**Reviewer lens:** verify committed decisions in AD-17, AD-19, AD-20, AD-21, AD-22, AD-23, and the Neo4j graph schema mermaid diagram, against actual current web-verifiable facts (not training-data assertions). Review date: 2026-09-28.

**Verdict: PASS WITH CONCERNS.** Three of the four flagged claims check out; one (AD-17's vector-index claim) is materially imprecise in a way that could mislead an implementer, and is the kind of claim that most needed — and most rewards — a citation with an exact quote rather than a paraphrase.

---

## Claim 1 — AD-17: "Neo4j Community Edition supports native vector indexes with in-index filtering (since 2026.01)"; queryNodes/queryRelationships deprecated as of 2026.04 in favor of Cypher `SEARCH`

**Verdict: Partially inaccurate — needs correction.**

What's actually documented (neo4j.com/docs/cypher-manual/current/indexes/semantic-indexes/vector-indexes/, and neo4j.com/docs/cypher-manual/current/constraints backing pages):

- "Vector indexes are available in both Neo4j Enterprise Edition and Community Edition. **Community Edition can index embeddings stored as `LIST<INTEGER | FLOAT>` properties.**" Storing embeddings as **native `VECTOR` properties requires block format, which is available in Enterprise Edition and Aura only** (source: neo4j.com/docs/cypher-manual/current/indexes/semantic-indexes/vector-indexes/, backed by neo4j.com/docs/operations-manual/current/database-internals/store-formats/#store-format-overview).
  - So the spine's phrase **"native vector indexes"** for Community Edition is the wrong word: CE indexes are built over `LIST<FLOAT>` properties, not the native `VECTOR` type. If the adapter (or a future contributor reading this spine) assumes CE gives it the native `VECTOR` property type, that assumption is wrong and would only surface at runtime/schema-creation time. The spine's own schema diagram already hedges correctly — `CHUNK { float embedding }` uses `float`, not a `VECTOR` type — so the implementation intent is right, but the AD-17 prose contradicts it by saying "native."
- In-index filtering (the Cypher 25 `SEARCH ... WHERE` clause) was **introduced in preview in 2026.01** and reached **general availability in 2026.02** (neo4j.com/blog/genai/vector-search-with-filters-in-neo4j-v2026-01-preview/: "generally available as of v2026.02"). Community Edition's GA for this specific capability was confirmed slightly later: a Neo4j engineer (Pontus Melke) stated on the Neo4j community forum that the feature is GA "in 2026.04.0 and is no longer in preview" for Community Edition specifically, and noted list-membership predicates in `WHERE` weren't supported until ~2026.06 (community.neo4j.com/t/search-clause-with-where-in-community-edition-ga-status-and-supported-predicates-for-multi-tenant-filtering/78969).
  - So "since 2026.01" conflates the preview date with GA. The more defensible date is **2026.02 (GA generally) / 2026.04 (GA confirmed specifically for Community Edition)**, not 2026.01.
- The queryNodes/queryRelationships deprecation claim is **accurate**: the Cypher manual's procedure reference explicitly says "Deprecated in Neo4j 2026.04" and names the `SEARCH` clause as the replacement — this matches the spine exactly, and since docker-compose pins `neo4j:2026.08.1-community` (well past 2026.04), the spine's conclusion that the adapter must use `SEARCH` rather than the deprecated procedures is correct.

**Recommendation:** Amend AD-17's verified-claim sentence to: (a) drop "native" or clarify CE indexes `LIST<FLOAT>` properties rather than a native `VECTOR` type; (b) change "since 2026.01" to "GA since 2026.02 (2026.04 for Community Edition specifically)" or similar, citing the two sources above rather than relying on the single blanket citation currently given.

Sources:
- https://neo4j.com/docs/cypher-manual/current/indexes/semantic-indexes/vector-indexes/
- https://neo4j.com/blog/genai/vector-search-with-filters-in-neo4j-v2026-01-preview/
- https://community.neo4j.com/t/search-clause-with-where-in-community-edition-ga-status-and-supported-predicates-for-multi-tenant-filtering/78969
- https://neo4j.com/docs/operations-manual/current/database-internals/store-formats/#store-format-overview

---

## Claim 2 — `org.neo4j.driver:neo4j-java-driver` is the correct current Maven coordinate for the plain Java Driver

**Verdict: Confirmed accurate. No concerns.**

Maven Central / mvnrepository both list the artifact under groupId `org.neo4j.driver`, artifactId `neo4j-java-driver`, current major series 6.x (6.2.x latest seen), last release ~June 2026. No rename or restructuring to a different groupId/artifactId was found. The spine's AD-21 rule matches this exactly, and correctly distinguishes it from `spring-boot-starter-data-neo4j` (rejected per AD-2).

Sources:
- https://mvnrepository.com/artifact/org.neo4j.driver/neo4j-java-driver
- https://central.sonatype.com/artifact/org.neo4j.driver/neo4j-java-driver
- https://neo4j.com/docs/java-manual/current/install/

---

## Claim 3 — AD-20 rationale: Community Edition restricts a DBMS to one standard database

**Verdict: Confirmed accurate as of Neo4j 2026.x.**

Neo4j's own Operations Manual introduction states plainly: "Installations of Community Edition can have exactly one standard database, while installations of Enterprise Edition can have any number of standard databases." This is unchanged in current (2026.09-era) documentation. This directly supports AD-20's rationale for why per-corpus isolation must be done via a `corpusId` property rather than one Neo4j database per corpus — the spine's reasoning holds.

Sources:
- https://neo4j.com/docs/operations-manual/current/introduction/
- https://neo4j.com/docs/operations-manual/current/database-administration/

---

## Claim 4 — `CREATE CONSTRAINT ... IS UNIQUE` for composite/multi-property keys is valid in Neo4j 2026.x Community Edition

**Verdict: Confirmed accurate — correctly distinguished from the Enterprise-only "node key" constraint.**

The Cypher Manual's constraints page marks "Create key constraints" (i.e., true node-key constraints combining existence + uniqueness) explicitly as **Enterprise Edition**. By contrast, the "Create property uniqueness constraints" section — which covers composite/multi-property `IS UNIQUE` constraints without an existence requirement — carries **no edition restriction**, meaning it is available in Community Edition. AD-20's rule uses exactly the right primitive (`CREATE CONSTRAINT ... IS UNIQUE` on composite keys like `(corpusId, id)`), and never invokes the Enterprise-only node-key syntax. This is a correct, non-obvious distinction that the spine gets right.

Sources:
- https://neo4j.com/docs/cypher-manual/current/constraints/managing-constraints/

---

## Other observations (not explicitly requested, lower confidence / minor)

- **AD-19/AD-22/AD-23** make no version-sensitive technical claims beyond what's already covered by AD-2/AD-20/AD-21 (Neo4j Java Driver usage, single-DB constraint) — nothing further to reality-check there; they are internal design decisions, not externally-verifiable facts.
- The Neo4j graph schema mermaid diagram's `CHUNK { float embedding }` is consistent with the corrected claim-1 finding (CE uses `LIST<FLOAT>` properties, not native `VECTOR` type) — this is good, but the diagram's caption ("native Neo4j vector index") should be updated in lockstep with the AD-17 text fix recommended above, since both currently use the same imprecise "native" wording.
- Docker Compose version pin (`neo4j:2026.08.1-community`, confirmed by direct file read of `docker-compose.yml`) is well past both the 2026.04 procedure-deprecation date and the 2026.04 CE in-index-filtering GA date, so the spine's operative conclusion — "the adapter must use `SEARCH`, not the deprecated procedures" — remains correct regardless of the date-precision issue in claim 1.
