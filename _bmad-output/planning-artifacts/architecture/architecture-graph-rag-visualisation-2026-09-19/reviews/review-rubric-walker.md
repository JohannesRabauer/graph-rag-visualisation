# Rubric-Walker Review — ARCHITECTURE-SPINE.md (spec-neo4j-corpus-persistence delta)

**Scope:** AD-16 (amended), AD-17 (amended), AD-19 through AD-23 (new), plus the parts of the
Capability → Architecture Map / Structural Seed / Deferred sections touched by this update.

**Verdict: PASS WITH CONCERNS**

The new/amended ADs correctly diagnose the brownfield state, cover all 8 SPEC capabilities, and are
internally consistent with `docker-compose.yml`. The concerns below are real but narrow: one enforceability
gap in how AD-19/AD-20 interact with the existing `GraphStorePort`/`VectorStorePort` Java surface, one
wording ambiguity in AD-19 about what happens to the `CorpusStore` class itself, and a couple of
under-specified edges around the CorpusMeta model that a careless implementer could get wrong without
technically violating any Rule as literally written.

---

## 1. Divergence-point coverage (does it fix what needs fixing?)

Verified against actual code, not just prose:

- `Neo4jGraphStoreAdapter`/`Neo4jVectorStoreAdapter` are indeed empty aliases over the in-memory adapters
  (`graphrag-adapter-neo4j/.../Neo4jGraphStoreAdapter.java`, `Neo4jVectorStoreAdapter.java`) — AD-21's
  framing of this as "today's actual bug" is accurate, not exaggerated.
- `ParserConfig.java` wires `InMemoryGraphStoreAdapter`/`InMemoryVectorStoreAdapter` directly as `@Bean`s —
  confirms brownfield.md's claim and matches AD-21's fail-fast/no-silent-fallback rule (though the spine
  never explicitly says `ParserConfig`'s bean methods must change to select the real adapter and call
  `verifyConnectivity()` — this is left implicit; low severity, since it's the obvious mechanical
  consequence of AD-21's rule and no plausible alternative wiring exists).
- `GraphStorePort`/`VectorStorePort` already define `corpusId`-scoped methods, and **every actual call
  site** (`AnswerLocalSearch`, `AnswerGlobalSearch`, `AnswerDriftSearch`, `DetectCommunities`,
  `AnswerVectorBaseline`, `CorpusController.vectorSpace()`) consistently calls the scoped overloads, never
  the unscoped ones — brownfield.md's claim that "multi-corpus support at the port level already exists"
  is accurate.
- `docker-compose.yml` pins `neo4j:2026.08.1-community`, matching AD-17's citation exactly, and today only
  passes `OPENAI_API_KEY` to `app` — confirming AD-21's claim that three Neo4j env vars are missing.
- `CorpusStore.java` and `CorpusController.java` match AD-19's description precisely (`ConcurrentHashMap`
  registry; no `GET /api/corpora`; `corpusStore.get()/put()/status()/markReady()/markFailed()/isOffline()/
  markOffline()` are the only bookkeeping surface).

No divergence point relevant to this feature slice was found unaddressed by an AD.

## 2. Finding: AD-20 doesn't flag the port's default-method fallback trap (Low-Medium)

`GraphStorePort`'s **abstract** members are the unscoped `persistEntities(Collection)`,
`persistRelationships(Collection)`, `entities()`, `relationships()`, etc.; the `corpusId`-taking overloads
are `default` methods that, unless overridden, silently delegate to the unscoped/global ones. A Neo4j
adapter implementation only needs to satisfy the abstract unscoped methods to compile — nothing forces it
to override the scoped defaults. In practice this is de-risked today because every real call site already
uses the scoped overloads exclusively (verified above), but AD-20's rule text talks entirely in terms of
Cypher `MERGE` keys and constraints, never mentioning that the new adapter must **actively override** the
six-plus scoped default methods rather than relying on inherited ones — an implementer who reads AD-20
alone and reasonably assumes "implement the interface, use `corpusId` in my Cypher" could satisfy the
letter of the Rule for the methods they touch first and still leave some scoped read/write path silently
falling back to global (unscoped, in-memory-only) behavior via an un-overridden default. Recommend AD-20 (or
AD-19) add one sentence: "every default `corpusId`-scoped method on `GraphStorePort`/`VectorStorePort` must
be explicitly overridden by the Neo4j adapters; none may be left to fall back to the unscoped default."

## 3. Finding: AD-19's "deleted outright" vs. the offline-corpus carve-out is ambiguous (Low)

AD-19's Rule states *"`CorpusStore`'s in-memory maps are deleted outright, not kept as a cache layer"*
and then carves out an exception: offline/demo corpora (`markOffline`, Story 9.1) "stay a transient,
process-lifetime-only construct, **exactly as `CorpusStore` held them today**." Read literally, this is
self-tensioned: if `CorpusStore` is deleted outright, there is no stated home for the offline-flag
bookkeeping (`markOffline`/`isOffline`) that today lives inside that same class. Two implementers could
diverge here in a way that's easy to miss in review: one keeps a slimmed-down `CorpusStore` class (renamed
or not) purely for the offline set; another introduces a brand-new class (e.g. `OfflineCorpusRegistry`).
Both satisfy the Rule's *behavior*, but the class-naming/placement split is exactly the kind of thing this
spine's Consistency Conventions table is meant to pin down elsewhere, and isn't pinned down here. Low
severity because it's a naming/organization question, not a wire-contract or data-model one — but worth a
one-line clarification (e.g., "the offline set survives as `CorpusStore`, stripped to only `markOffline`/
`isOffline`; everything else it held moves to `Neo4jCorpusRegistry`").

## 4. Finding: AD-16's amendment undersells the actual `CorpusController` rewrite (Low)

AD-16's amendment note says only "`CorpusController` now reads status from `Neo4jCorpusRegistry`... The
gate's behavior is unchanged — only where the status is read from." In the actual code, `CorpusController`
uses `corpusStore` for far more than status reads on the query path: `put()` (register on upload),
`markReady()`/`markFailed()` (from the async ingestion callback), `get()` (existence check), `isOffline()`/
`markOffline()`. AD-19 does separately cover this migration correctly and completely — so there's no actual
architectural gap — but a reader relying on AD-16's amendment note alone would underestimate the blast
radius of this change to `CorpusController`. Cosmetic; doesn't affect buildability since AD-19 is the
authoritative source for the full picture.

## 5. Capability coverage (SPEC CAP-1..CAP-8)

All eight are addressed, cross-checked against both the AD text and the Capability → Architecture Map
table:

| CAP | Covered by |
| --- | --- |
| CAP-1 (real `GraphStorePort`) | AD-2, AD-10, AD-11, AD-20, AD-21 |
| CAP-2 (real `VectorStorePort`, chunks + projection) | AD-17 (amended), AD-20 |
| CAP-3 (fail-fast connectivity) | AD-21 |
| CAP-4 (`CorpusMeta` bookkeeping via Neo4j) | AD-19, AD-20 |
| CAP-5 (auto-restore active corpus) | AD-22 |
| CAP-6 (`GET /api/corpora`) | AD-22 |
| CAP-7 (frontend switcher + `activate`) | AD-22 |
| CAP-8 (startup reconcile `BUILDING`→`FAILED`) | AD-23 |

No capability is silently unaddressed.

## 6. Deferred section — nothing load-bearing slipped in

The Deferred list is unchanged by this update and remains non-load-bearing for this feature (auth,
multi-tenancy, other graph DBs, benchmarking, observability, trace-store implementation choice, build
tool, Cytoscape loading mechanism, library packaging). None of these interact with the new persistence/
registry surface in a way that could cause incompatible builds. Nothing new introduced by this spec
(e.g., exact `Neo4jCorpusRegistry` class placement, offline-corpus bookkeeping's new home per Finding 3)
was added to Deferred even though arguably the naming ambiguity in Finding 3 belongs there as an explicit
low-stakes deferred item rather than left implicit.

## 7. Operational/environmental envelope vs. `docker-compose.yml`

AD-21 fully and correctly covers the compose delta: three new `app` env vars (`NEO4J_URI`,
`NEO4J_USERNAME`, `NEO4J_PASSWORD`), sourced the same way `OPENAI_API_KEY` already is, with defaults
matching the existing `neo4j` service's `NEO4J_AUTH`/`NEO4J_PASSWORD` default. Cross-checked against the
actual file: today `app` only receives `OPENAI_API_KEY`, and `neo4j` already exposes `NEO4J_PASSWORD` with
default `graphraglens` — AD-21's described gap and fix are both accurate. Durability itself needs no compose
change (the `neo4j_data` named volume already persists across restarts, and `app`'s existing
`depends_on: condition: service_healthy` already covers ordering) — this is correctly left unmentioned
rather than being a gap. No environmental dimension was found silently uncovered.

## 8. Tech/version consistency

Neo4j `2026.08.1-community` (docker-compose.yml) vs. spine's "Neo4j 2026.x, Community Edition, with GDS" —
consistent. AD-17's amendment citing the Cypher `SEARCH` clause replacing deprecated
`db.index.vector.queryNodes` as of 2026.04, with the version pin at 2026.08.1, is internally consistent
(the pin postdates the deprecation, so the adapter is correctly directed at the non-deprecated path).
`neo4j-java-driver` "latest ≥6.2.x, don't hard-pin" (Stack table) is consistent with AD-21 not naming an
exact driver version either.

---

## Summary of findings by severity

1. **Low-Medium** — AD-20 doesn't warn that `GraphStorePort`/`VectorStorePort`'s `corpusId`-scoped methods
   are `default`s falling back to unscoped globals; an implementer could satisfy the Rule's literal Cypher
   guidance while still leaving one scoped method un-overridden. Recommend one added sentence to AD-19 or
   AD-20.
2. **Low** — AD-19's "deleted outright" vs. "exactly as `CorpusStore` held them today" for offline corpora
   is a self-tensioned pair of phrases; doesn't block a correct build but invites a naming/placement
   divergence for the offline-flag bookkeeping.
3. **Low** — AD-16's amendment note ("only where status is read from") undersells the actual scope of the
   `CorpusController` rewrite that AD-19 correctly and fully specifies elsewhere; cosmetic inconsistency
   between the two ADs' framing, not a gap in coverage.
4. **Informational** — `ParserConfig`'s bean-wiring change (selecting the real Neo4j adapter, calling
   `verifyConnectivity()`) is the obvious mechanical consequence of AD-21 but isn't spelled out; harmless
   omission given there's no plausible alternative implementation.

None of these findings represent a genuine two-units-diverge risk at the level this rubric is built to
catch at high severity — they're wording/emphasis gaps around otherwise-correct rules, not missing or
unenforceable rules.
