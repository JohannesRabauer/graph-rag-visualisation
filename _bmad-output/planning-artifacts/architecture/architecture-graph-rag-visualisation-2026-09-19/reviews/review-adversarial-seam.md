---
name: 'Adversarial Seam Review — AD-19..AD-23 (Neo4j Corpus Persistence)'
type: review
target: ARCHITECTURE-SPINE.md (AD-19 through AD-23, amendments to AD-16 and AD-17)
source_spec: _bmad-output/specs/spec-neo4j-corpus-persistence/SPEC.md
reviewer_stance: adversarial seam reviewer — construct concrete pairs of units that each obey every AD to the letter yet build incompatibly
date: 2026-09-28
verdict: PASS WITH CONCERNS
---

# Adversarial Seam Review

## Method

For each new/amended AD (AD-16, AD-17, AD-19–AD-23), I looked for a concrete pair of implementers — each
provably compliant with the letter of every binding AD — whose independent, compliant choices still
produce a runtime inconsistency: a race, a double-owned entity, a wire-contract ambiguity, or a silent
data-integrity violation. I verified each candidate against the actual source where it exists
(`GraphStorePort.java`, `CorpusStore.java`, `CorpusController.java`, `InMemoryGraphStoreAdapter.java`)
rather than relying on the spine's prose alone, since a "hole" that the current code already closes isn't
one worth an AD.

---

## Finding 1 — `GraphStorePort`'s non-scoped abstract method is a corpus-isolation trap for AD-20 (HIGH)

**The pair:** Developer A implements `Neo4jGraphStoreAdapter` per AD-19/AD-20 by overriding the
`corpusId`-scoped methods (`persistEntities(String, Collection)`, `persistRelationships(String,
Collection)`, `persistCommunities(String, Collection)`, `persistCommunityMemberships(String, Collection)`)
with proper `corpusId`-keyed `MERGE` Cypher, exactly as AD-20 prescribes. Developer B is anyone who later
adds a call site, a test double, or a second port-caller against the *same interface* — fully entitled to
call `graphStorePort.persistEntities(entities)` (no `corpusId` argument), because
`GraphStorePort.persistEntities(Collection<Entity>)` (line 23) is not a default method — it's the single
**abstract** method every implementer is compiler-forced to provide, with no `corpusId` parameter at all.

**Verified in code** (`graphrag-core/src/main/java/io/graphrag/core/port/GraphStorePort.java`):
```java
void persistEntities(Collection<Entity> entities);                       // abstract — must be implemented
...
default void persistEntities(String corpusId, Collection<Entity> entities) {
    persistEntities(entities);                                            // corpusId silently dropped
}
```
The scoped overload's *default* body throws away `corpusId` and delegates to the plain method. Today's
`InMemoryGraphStoreAdapter` avoids the trap only because its author happened to `@Override` **both**
overloads (verified: lines 31 and 44 both carry `@Override`, and the scoped one does its own per-`corpusId`
partitioning rather than delegating to the unscoped one). But nothing in AD-19 or AD-20 tells the *next*
adapter author this is required — the interface only forces them to implement the one method with no
`corpusId`. A `Neo4jGraphStoreAdapter` author who does the minimum to compile (implement the abstract
method, leave the scoped overloads on their inherited defaults) produces an adapter that satisfies AD-20's
prose ("every node written by these adapters carries an explicit `corpusId` property") for nothing,
because the only method actually reachable at runtime for entities has no `corpusId` to write. This isn't
hypothetical carelessness — it's the literal default behavior of the interface as it exists on disk today,
and AD-20 amends AD-10/AD-11's Cypher key shape without ever touching or even mentioning this interface
shape.

**Consequence:** silently unscoped nodes (no `corpusId` property, or a null one), which corrupts
Community-detection/exploration for every corpus sharing that untagged data, and defeats the composite
`MERGE` constraints AD-20 declares — with zero compile error and zero test failure unless a test happens
to call the scoped overload specifically.

**Recommendation:** Tighten AD-20 (or add a short AD-24) to state explicitly: (a) the unscoped
`persistEntities`/`persistRelationships`/`persistCommunities`/`persistCommunityMemberships`/`entities()`/
etc. overloads on `GraphStorePort` are deprecated for any implementation backed by durable, multi-corpus
storage — `Neo4jGraphStoreAdapter` must `@Override` every `corpusId`-scoped overload directly with real
Cypher, never inherit the default delegation; and (b) ideally, make the unscoped abstract method throw
`UnsupportedOperationException` in `Neo4jGraphStoreAdapter` (or delete it from the port surface entirely
now that every real call site already passes `corpusId` — confirmed: `ExtractEntitiesAndRelationships`,
`DetectCommunities`, `AnswerLocalSearch`, `AnswerDriftSearch`, `AnswerGlobalSearch`, `AnswerVectorBaseline`
all call only the `corpusId`-taking overloads already) so a future accidental call fails loudly instead of
writing silently-unscoped data.

---

## Finding 2 — No single-writer/lease invariant, so AD-23's blind `BUILDING → FAILED` sweep can race a
still-legitimately-ingesting instance during any overlap-window redeploy (MEDIUM)

**The pair:** Ops-minded Developer A performs redeploys the common low-downtime way — start the new `app`
container before stopping the old one (`docker-compose up -d --no-deps --scale app=2` momentarily, or any
manual blue/green swap) — which does not violate AD-8's letter ("`docker-compose.yml` defines exactly two
*services*"; AD-8 constrains the compose file's service count, not the number of simultaneously-running
containers of the `app` service during a manual redeploy). Developer B implements AD-23 exactly as
written: an `ApplicationReadyEvent` listener that sweeps every `CorpusMeta` still `BUILDING` to `FAILED`,
unconditionally, "before the app accepts any HTTP traffic." Both are individually AD-compliant.

**Race:** old instance is mid-ingestion of corpus X (each write already committed in its own transaction
per AD-14, but overall status still `BUILDING` until the ingestion pipeline finishes). New instance starts,
passes AD-21's connectivity check, and its `ApplicationReadyEvent` listener flips X to `FAILED` — while the
old instance is still actively, legitimately writing to it. Two further real problems fall out of this,
neither addressed by any AD:
1. AD-16's query gate now blocks X forever (`FAILED` is terminal per this spec's non-goals: no
   rebuild/retry path exists), even though the old instance's ingestion completes successfully moments
   later.
2. The old instance's own completion path (`corpusStore.markReady`/`markFailed`, confirmed at
   `CorpusController.java` lines 367/371 inside the `CompletableFuture.runAsync` block) will then write
   `READY` right back over the new instance's `FAILED`, or vice versa depending on timing — an
   unacknowledged last-write-wins collision on the exact same `CorpusMeta.status` field AD-19 designates as
   the single source of truth, with no version/CAS field anywhere in AD-19/AD-20's `CorpusMeta` shape to
   detect or resolve it.

Given NFR3/AD-8's single-user, single-process framing, an operator is never told "never run two `app`
processes against the same Neo4j, even transiently" — the invariant this whole scheme (AD-16, AD-19,
AD-22, AD-23) implicitly depends on is never written down anywhere in the spine.

**Recommendation:** Add a one-line invariant (to AD-8 or a new AD) stating at most one `app` process may be
connected to a given Neo4j instance at a time — no overlap-window redeploys — and/or harden AD-23 to only
transition a `BUILDING` corpus whose `createdAt`/last-write is older than some staleness threshold, rather
than sweeping unconditionally on every boot.

---

## Finding 3 — AD-22 never states when `lastActivatedAt` starts, so a freshly-ingested (never explicitly
"activated") corpus can lose the auto-restore race to an older one (MEDIUM)

**The pair:** Developer A builds the ingestion/upload flow per this spec's actual UX intent — a freshly
uploaded corpus becomes what the current tab is looking at immediately, with no separate "please also
click activate" step (AD-22's own rule lists the *only* two triggers for `POST /activate` as "the switcher
selects a corpus" and "page load auto-restore" — ingestion completion is not one of the two). Developer B
builds `Neo4jCorpusRegistry`/`GET /api/corpora` per AD-22's letter: order by `lastActivatedAt` descending,
frontend auto-restores the first entry. Neither AD states what `lastActivatedAt` is at `CorpusMeta`
creation time (null? epoch? `createdAt`?). If it starts null (the natural default for "never yet
activated," and consistent with AD-22's own framing that activation is a distinct, explicit act from
creation), a brand-new corpus a user just finished ingesting — and is actively looking at in their only open
tab — sorts *behind* every previously-activated corpus in `GET /api/corpora`. On the very next restart
(the exact scenario CAP-5's success signal is built around), the app auto-restores the wrong, older corpus,
silently, with no error — a direct regression of CAP-5 despite both developers being fully AD-22-compliant.

**Recommendation:** AD-22 should say explicitly whether `CorpusMeta` creation implicitly sets
`lastActivatedAt = createdAt` (recommended — cheapest fix, keeps "no server-side singleton" intact) or
whether ingestion completion must also trigger the same `POST /activate` call the switcher uses.

---

## Finding 4 — Timestamp clock-source is unspecified for `createdAt`/`lastActivatedAt`, risking
inconsistent ordering between independently-built read and write paths (LOW)

**The pair:** Developer A implements `POST /activate` using Cypher `datetime()`/`timestamp()` (Neo4j
server clock, inside the same transaction as the `MERGE`). Developer B implements `CorpusMeta` creation
(at ingestion start) using Java `Instant.now()` passed as a bound parameter (app-server clock). Both
satisfy AD-19/AD-20/AD-22's letter — none of them names a canonical clock source. Since `app` and `neo4j`
are separate containers with separate clocks (Docker containers can and do drift, however slightly, absent
NTP sync configuration this project never specifies), `ORDER BY lastActivatedAt DESC` comparisons across
rows written from different clock sources are not guaranteed monotonic with real-world activation order,
particularly for near-simultaneous events. Low severity for a single-developer local demo (clock drift
between two containers on one Docker host is typically negligible), but worth a one-line pin since AD-20
already goes to the trouble of specifying exact composite `MERGE` keys — the timestamp source used inside
those same writes is left implicit.

**Recommendation:** State in AD-19 or the Consistency Conventions table: all `CorpusMeta` timestamps are
set app-side via `Instant.now()` (or all server-side via Cypher `datetime()`) — pick one, consistently,
across creation and activation.

---

## Probe-by-probe verdicts

1. **Concurrent ingestion + startup reconciliation race (AD-23):** Not possible in the literal form asked
   ("an async `CompletableFuture` from the old JVM instance somehow still tries to write" after restart) —
   `CompletableFuture.runAsync` (confirmed at `CorpusController.java` line 357/383) runs on in-process
   thread pools; a process restart (crash or `docker restart`) unconditionally kills those threads, there
   is no cross-JVM persistence of in-flight futures, and normal sequential container restart never has two
   instances live at once. However, the *adjacent* real hole is genuine and is **Finding 2** above: an
   overlap-window redeploy (two `app` containers briefly alive against one Neo4j, which no AD forbids)
   reintroduces materially the same race the probe describes, just via a different mechanism (a second live
   process, not a resurrected async task).
2. **Two-tab `activate` race (AD-22):** Last-write-wins on `lastActivatedAt` for two *different* corpora is
   benign under the single-user assumption (NFR3) — it's simply "whichever the user did most recently,"
   which is the correct semantics for a single operator across two tabs. The real, adjacent hole is
   **Finding 3**: the untested edge of *when* a corpus first acquires a `lastActivatedAt` at all.
3. **AD-20's composite keys vs. `GraphStorePort`'s non-scoped default overloads:** Confirmed, real, and the
   most severe finding — see **Finding 1**. The spine's Structural Seed even lists `GraphStorePort` as
   unchanged/pre-existing ("`graphrag-core/.../port/` ... `GraphStorePort`") and AD-20 amends only AD-10/
   AD-11's Cypher key text, never the port's Java shape that the new adapter must satisfy.
4. **AD-19's `Neo4jCorpusRegistry` as a plain class risking a reversed hexagonal dependency:** Checked
   `CorpusStore.java` directly — `CorpusWorkflowStatus` is a `public enum` nested inside
   `CorpusStore.java` in `graphrag-web` (confirmed at lines 21–24). AD-16's amendment says
   `CorpusController` now reads status from `Neo4jCorpusRegistry` in `graphrag-adapter-neo4j`, but does not
   say where the `CorpusWorkflowStatus` enum type itself now lives. If `Neo4jCorpusRegistry.status(...)`
   returns this same enum type, and the enum stays nested in `graphrag-web`'s `CorpusStore` (which AD-19
   says is "deleted outright"), then either (a) the enum has nowhere to live once `CorpusStore` is deleted,
   or (b) it moves into `graphrag-adapter-neo4j`, meaning `graphrag-web` must import a type from an adapter
   module — fine under hexagonal rules since `graphrag-web` already depends on adapters (AD-19 says
   "`graphrag-web` calls it directly, the same way it calls other adapter-side Spring beans") — this
   direction is *not* actually inverted (web → adapter is an allowed, existing dependency direction; only
   adapter → web, or core → adapter, would invert AD-1/AD-3's hexagon). So this specific probe's feared
   inversion does not materialize structurally. What *is* missing: neither AD-19 nor AD-16's amendment
   states where `CorpusWorkflowStatus` relocates to once `CorpusStore` is deleted — a small, concrete gap
   worth a one-line fix (state it moves to `graphrag-adapter-neo4j` alongside `Neo4jCorpusRegistry`, or
   better, promote it to `graphrag-core`'s domain model since "workflow status" is arguably a `Corpus`
   domain concept the core's use cases already reason about, not an adapter-only concern) — but not the
   severity of a genuine dependency-direction violation.

## Overall

The five new/amended ADs (AD-19–AD-23) are internally consistent and correctly reasoned for the scenarios
they explicitly name. The genuine holes are all at unstated boundaries the ADs' prose doesn't reach:
the pre-existing `GraphStorePort` interface shape (Finding 1, the sharpest one — real code, real trap,
zero compile-time signal), an unstated single-writer assumption for the app tier (Finding 2), and two small
unstated defaults (initial `lastActivatedAt`, canonical clock source — Findings 3–4). None of these are
fatal to the spec as scoped for a single-developer local demo, but Finding 1 in particular should be closed
before `Neo4jGraphStoreAdapter` is implemented, since it is silent-data-corruption-shaped and has no test
that would catch it by accident.
