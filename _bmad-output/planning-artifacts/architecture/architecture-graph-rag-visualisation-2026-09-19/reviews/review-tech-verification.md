---
review: tech-verification
target: ../ARCHITECTURE-SPINE.md
memlog: ../.memlog.md
reviewer-lens: "Every committed decision must be web-researched or reality-checked, not asserted from training data — current versions, that named tech still exists/fits, live defaults of any starter."
date: 2026-09-19
---

# Tech-Verification Review — GraphRAG Lens Architecture Spine

## Verdict

Sound overall: the spine's most load-bearing architectural claims (GDS Leiden availability in Community Edition, the driver-over-SDN call, the UNDIRECTED-for-Leiden requirement, GDS-plugin-in-Docker feasibility) all independently check out against today's live docs/releases — but two of the six version pins in the Stack table (Neo4j Java Driver, LangChain4j) were already superseded by newer releases on or before the memlog's own "verified Sept 2026" date, so "verified" overstates how current those two specific numbers actually are.

## Method

Read `.memlog.md` to see which claims already carried a research citation vs. which were asserted. Then ran independent web searches (not just trusting the memlog's summary) against live sources — Neo4j/GDS docs and community posts, GitHub releases pages for `neo4j-java-driver` and `langchain4j`, Spring blog/InfoQ for Spring Boot 4.1, and Cytoscape.js's npm/GitHub release cadence — for the claims most load-bearing to the architecture. `neo4j.com` and `mvnrepository.com` were blocked by this session's egress proxy, so GDS-doc claims were corroborated via cached/indexed copies, GitHub, and community-forum threads instead of the primary docs site directly; where a claim rests solely on a source I could not load directly, it's marked below.

## Findings

### 1. CONFIRMED — GDS Leiden is available in Community Edition; no Enterprise license is required for the algorithm (highest-priority check)

This was the single most consequential claim to verify, since a wrong answer would be a licensing blocker for an open-source hobby project. Independent research confirms:

- GDS defaults to Community Edition and requires an Enterprise **license file** only to unlock Enterprise-only features; Leiden itself is not gated behind that license.
- The actual Community Edition restriction is a **hard concurrency cap of 4 CPU cores/threads** for GDS computations — a performance ceiling, not an algorithm-availability gate. Enterprise lifts that cap and adds optimized graph implementations.
- Leiden is documented as a production-quality (not alpha/beta) community-detection algorithm in current GDS docs.
- The Neo4j Community Edition Docker image can load the GDS plugin via `NEO4JLABS_PLUGINS=["graph-data-science"]` (with `NEO4J_ACCEPT_LICENSE_AGREEMENT=yes`), so AD-8's exactly-two-containers Docker Compose plan is realizable as described without an Enterprise image.

**Conclusion:** AD-4, the Stack table's "Community Edition, with GDS plugin," and the Capability Map's "GDS Leiden" are all sound. The 4-core cap is worth one sentence in the spine (it won't matter for a single-user local corpus, but it's the actual mechanism, not "no restrictions at all") — a documentation nicety, not a correctness issue.

### 2. CONFIRMED (with a nuance the memlog doesn't register) — plain Neo4j Java Driver over Spring Data Neo4j

Spring Data Neo4j is not legacy or fading: SDN 8.x is actively developed in 2026 alongside Spring Framework 7 / Spring Boot 4 (stable 8.1.1, preview 8.2.0-M1 as of the 2026 Spring Data release train). So AD-2 isn't compensating for SDN being outdated — the technical argument (OGM entity-mapping fights raw GDS procedure calls and Cypher-projection traversal) still holds and is the right call for this app.

One gap: the memlog frames this as a binary (SDN's OGM vs. the plain driver) but SDN also ships `Neo4jClient`, a lower-level query API that runs raw Cypher (including GDS calls) while staying inside Spring's transaction management — a middle option between "full OGM" and "plain driver, roll your own everything." That option isn't mentioned or ruled out anywhere in the memlog or spine. AD-2's actual rule ("Spring Data Neo4j is never used") forecloses even `Neo4jClient`, which is a defensible simplicity call (one less framework surface to reason about) but should be a stated tradeoff, not an omission — the memlog's justification is incomplete rather than wrong.

### 3. FINDING — two Stack-table version pins were already superseded on the day they were "verified"

Given the memlog stamps these as web-verified on 2026-09-19 (today), I checked each against its project's actual release history rather than trusting the pinned number:

- **Neo4j Java Driver 6.1.x** (spine Stack table; memlog: "Neo4j Java Driver 6.1.0"): 6.2.0 and 6.2.1 were already released (6.2.1 on Aug 4, 2026) — over a month before this spine's date. 6.1.x was not the current release even at authoring time.
- **LangChain4j 1.19.x** (spine Stack table; memlog: "LangChain4j 1.19.0 (verified)"): 1.19.0 released Aug 14, 2026, but 1.20.0 (Jackson 3 support, reactive AI Service support) is already out, and `main` is at `1.21.0-SNAPSHOT`. LangChain4j ships roughly every two weeks, so a version pin is stale almost as soon as it's written.

Neither gap looks functionally dangerous — nothing found in the 6.2.x or 1.20.x changelogs breaks the spine's usage (raw driver + Cypher; OpenAI-backed extraction) — but it means "verified Sept 2026" overstates currency for these two rows specifically. Recommend the spine either (a) bump both to the actual current minor at the time this is read, or (b) soften the Stack table to "6.x / 1.x, pin exact patch at implementation start" for these two fast-moving libraries, since a hard "6.1.x"/"1.19.x" reads as more authoritative than the research supports.

The memlog's Neo4j **server** claim ("2026.06.0 current stable") has the same pattern — 2026.07.1 (and references to 2026.08.0) were already out — but this doesn't leak into the spine itself, which only commits to the generic "2026.x," so no action needed there.

### 4. CONFIRMED — no issues found in the remaining pinned versions/tech choices

- **Java 25 (LTS):** correct and still the current LTS (GA Sept 16, 2025; Java 26, GA March 2026, is a non-LTS interim release). No newer LTS exists as of today.
- **Spring Boot 4.1.x / Spring Framework 7:** confirmed — 4.1.0 shipped June 10, 2026 and is the currently recommended target for new projects; Framework 7 supports JDK 25 while keeping a JDK 17 baseline, so the Java 25 pick is compatible.
- **Apache PDFBox 3.0.x:** 3.0.8 is a real, current release in the maintained 3.0.x line.
- **Cytoscape.js:** actively maintained (weekly patch / monthly feature cadence, v3.34.3 as of this month); its compound-node/style-class strengths cited in the memlog for community hulls and traversal states are still the right differentiator versus vis-network/Sigma.js for an analysis-oriented, moderate-scale graph UI — Sigma's WebGL scale advantage genuinely doesn't apply here.
- **AD-4 (UNDIRECTED projection for Leiden):** confirmed current GDS guidance — Leiden is designed for/"works best" with UNDIRECTED-oriented projections, and GDS ships a `gds.graph.relationships.toUndirected()` procedure for graphs stored directed elsewhere, matching the spine's "project as UNDIRECTED regardless of storage direction" rule exactly.

## Non-findings worth naming

- Spring Boot vs. Quarkus/Javalin (memlog decision) and Cytoscape.js vs. vis-network/Sigma.js/Neovis.js are framed as researched tradeoffs, not bare version claims, and hold up under a fitness-for-purpose check — no red flags.
- The two `[ASSUMPTION]`-tagged items (Maven multi-module build tool; Vite-based frontend build wiring) are correctly left as assumptions rather than dressed up as verified facts — nothing to flag there.

## Sources consulted

- Neo4j GDS Community/Enterprise licensing and concurrency limits — Neo4j community forum threads ("License model of GDS", "Does the community version have CPU restrictions?", "GDS licensing on Neo4j startup program") and GDS introduction/algorithms docs (indexed copies, since neo4j.com was proxy-blocked for direct fetch).
- GDS Leiden algorithm page and undirected-relationship guidance; `gds.graph.relationships.toUndirected()` procedure.
- Neo4j Docker plugin installation (`NEO4JLABS_PLUGINS`) — Neo4j community forum + `neo4j/docker-neo4j` GitHub issues.
- `neo4j/neo4j-java-driver` GitHub releases (6.1.0, 6.2.0, 6.2.1).
- `langchain4j/langchain4j` GitHub releases (1.19.0, 1.20.0) and `main` branch `langchain4j-bom/pom.xml` (1.21.0-SNAPSHOT).
- Spring Boot 4.1.0 announcement (spring.io blog), InfoQ coverage of Spring Boot 4.1 and Spring Framework 7/Boot 4.
- Java version history (Java 25 LTS GA date, Java 26 non-LTS).
- Spring Data Neo4j current release train (spring.io Spring Data 2026 release post) and SDN `Neo4jClient` capability (Spring Data Neo4j reference docs, indexed).
- Cytoscape.js npm/GitHub release cadence and 2026 comparison pieces vs. vis-network/Sigma.js.
- Apache PDFBox 3.0.8 release page.
