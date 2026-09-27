---
title: 'graphrag-core: generic package identity, LICENSE, and publish-ready POM'
type: 'refactor'
created: '09-27-2026'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: [multiple-goals, oversized]
deferred: []
baseline_revision: 'd74f49736c7d67f6ecaa6906aacdf5e09c37b92b'
---

<intent-contract>

## Intent

**Problem:** Following the reusability audit (GitHub #24, `_bmad-output/implementation-artifacts/audit-graphrag-core-reusability.md`) and the maintainer's answers to its two open questions, `graphrag-core` is not actually reusable today: its Maven `groupId`/package (`com.graphraglens`) ties it to this specific app rather than a generic library identity, there is no `LICENSE`, no released version, and no publishing metadata (GitHub #29, part 1).

**Approach:** Rename `graphrag-core`'s own Maven coordinates and Java package from `com.graphraglens`/`com.graphraglens.core.*` to a generic identity (`io.graphrag` / `io.graphrag.core.*` — the exact identity the audit itself proposed as an example), updating every in-repo reference (the other four modules' code, `pom.xml` files, and tests) so the reactor still builds. Add an Apache License 2.0 `LICENSE` file. Cut `graphrag-core`'s first real, non-`SNAPSHOT` release version with a `CHANGELOG.md`. Add publish-ready POM metadata (`<licenses>`/`<scm>`/`<developers>`/`<url>`, `<distributionManagement>`, source-jar and javadoc-jar plugins) so the module is ready for the maintainer to actually run the publish step themselves.

## Boundaries & Constraints

**Always:** After the rename, `mvn clean install` must pass across the full reactor with zero references to `com.graphraglens.core` remaining anywhere (grep-verified). Only `graphrag-core`'s own groupId/package changes — `graphrag-web`/`graphrag-adapter-*`'s own `com.graphraglens.web`/`com.graphraglens.adapter.*` packages and the root reactor's own `groupId` stay exactly as they are; only their *references to* `graphrag-core`'s package/coordinates change. `graphrag-core/pom.xml` must declare its own `<groupId>io.graphrag</groupId>` explicitly (not inherited from the parent), since it is now deliberately decoupled from the app's own `com.graphraglens` identity. Cut the release version on `graphrag-core` itself (and only propagate a version bump to other modules if the reactor's shared-version convention requires it to keep building — check `pom.xml`'s current version-inheritance setup first).

**Never:** Do not actually run `mvn deploy`/publish anything to a registry, and do not create real signing keys/credentials — that step needs the maintainer's own registry account and is explicitly left to them (per their own answer). Do not touch README/Javadoc/`package-info.java` content — that is a separate follow-up spec. Do not change any behavior — this is a rename plus metadata/config change; every existing test must still pass unmodified in what it asserts (test *code* may need import-path updates, but no test's assertions should change).

</intent-contract>

## Code Map

- `pom.xml` (root) — keep `groupId com.graphraglens` as-is (this is the app's own identity, not `graphrag-core`'s). Confirm whether module versions are independently settable or inherited/aggregated, since `graphrag-core` needs its own release version distinct from the other still-`SNAPSHOT` modules.
- `graphrag-core/pom.xml` — add explicit `<groupId>io.graphrag</groupId>` (decoupling from parent inheritance), bump `<version>` off `SNAPSHOT` to a first real release (e.g. `1.0.0`), add `<name>`/`<description>` if not already adequate, add `<licenses>` (Apache-2.0), `<scm>`, `<developers>`, `<url>` (best-effort — use this repository's own GitHub URL), `<distributionManagement>` (a placeholder/staging-appropriate config the maintainer can point at their chosen registry — GitHub Packages is the lowest-setup-cost default given no Maven Central account is confirmed to exist yet), and `maven-source-plugin`/`maven-javadoc-plugin` executions bound to the `package` phase.
- `graphrag-core/src/main/java/com/graphraglens/core/**` → move to `graphrag-core/src/main/java/io/graphrag/core/**` (same three sub-packages: `domain`, `port`, `usecase`), updating every file's `package` declaration.
- `graphrag-core/src/test/java/com/graphraglens/core/**` → move to `graphrag-core/src/test/java/io/graphrag/core/**` likewise.
- Every other module's Java source that imports `com.graphraglens.core.*` (67 files repo-wide per a pre-implementation grep, spanning `graphrag-web`, `graphrag-adapter-neo4j`, `graphrag-adapter-langchain4j`, `graphrag-adapter-parsing`, and their own tests) — update the import statements to `io.graphrag.core.*`. These modules' *own* packages (`com.graphraglens.web.*`, `com.graphraglens.adapter.*`) are untouched.
- The four dependent modules' `pom.xml` files (`graphrag-adapter-neo4j`, `graphrag-adapter-langchain4j`, `graphrag-adapter-parsing`, `graphrag-web`) — each currently depends on `graphrag-core` via the shared parent groupId/version; update each's `<dependency>` on `graphrag-core` to the new `groupId io.graphrag` and its new release version.
- Repository root — add `LICENSE` (Apache License 2.0, full text) and `graphrag-core/CHANGELOG.md` (a first entry for the initial release, noting the identity change itself as a breaking change from any prior internal-only usage).

## Tasks & Acceptance

**Execution:**
- `LICENSE` (new, repo root) -- add the full Apache License 2.0 text -- the audit's #1 blocking finding; nothing else matters legally without it
- `graphrag-core/src/main/java/com/graphraglens/core/**` -- move to `io/graphrag/core/**`, update every `package` declaration -- the actual identity rename
- `graphrag-core/src/test/java/com/graphraglens/core/**` -- move to `io/graphrag/core/**` likewise -- keep tests colocated with the renamed source
- All 67 dependent files across `graphrag-web`/`graphrag-adapter-*` (main + test) -- update `import com.graphraglens.core.*` to `import io.graphrag.core.*` -- nothing else in these files changes
- `graphrag-core/pom.xml` -- explicit `groupId io.graphrag`, version off SNAPSHOT, `<licenses>`/`<scm>`/`<developers>`/`<url>`, `<distributionManagement>`, source-jar + javadoc-jar plugins -- publish-ready metadata, decoupled from the parent's own app identity
- The four dependent modules' `pom.xml` -- update their `<dependency>` on `graphrag-core` to `groupId io.graphrag` + the new version -- keeps the reactor resolvable
- `graphrag-core/CHANGELOG.md` (new) -- first entry documenting the initial real release and the identity-change context -- gives future consumers something to diff against

**Acceptance Criteria:**
- Given the rename is complete, when `grep -r "com.graphraglens.core" --include="*.java" --include="pom.xml" .` runs (excluding `target/`), then it returns zero matches
- Given the rename is complete, when `mvn clean install` runs across the full reactor, then every module builds and every existing test passes unmodified in its own assertions
- Given `graphrag-core/pom.xml`, when inspected, then it declares its own `io.graphrag` groupId, a non-SNAPSHOT version, and all of `<licenses>`/`<scm>`/`<developers>`/`<url>`/`<distributionManagement>`/source-jar/javadoc-jar plugin wiring
- Given the repository root, when inspected, then `LICENSE` exists with full Apache-2.0 text, and `graphrag-core/CHANGELOG.md` exists with a first release entry

## Spec Change Log

## Review Triage Log

### 09-27-2026 — Review pass
- verdicts: 15 findings — high 0, medium 4, low 2, false 3, maybe-false 0, reject 6
- findings:
  - `[medium]` `[patch]` Blind Hunter + Edge Case Hunter + Intent-alignment-auditor (grouped, same defect, 3 rows sharing this route): the four dependent modules now hardcode `graphrag-core`'s version as the literal string `1.0.0` in four separate `pom.xml` files instead of one shared property — a future version bump requires four synchronized edits, and missing one silently breaks dependency resolution. Patch: define `<graphrag-core.version>1.0.0</graphrag-core.version>` once (root `pom.xml` `<properties>`) and reference `${graphrag-core.version}` in all four dependent `<dependency>` blocks.
  - `[medium]` `[patch]` Blind Hunter: `<distributionManagement>` targets GitHub Packages, which requires an authenticated GitHub token even to *download* a public package — a real friction point that partly undercuts the "make graphrag-core easier to reuse" goal, and isn't flagged anywhere for the maintainer to weigh before they commit to it. Grouped with the same reviewer's separate finding that no `<snapshotRepository>` is declared alongside the release `<repository>`. Patch: add a code comment on `<distributionManagement>` naming this tradeoff explicitly (so it's a documented, revisitable choice, not a silent default), and add a `<snapshotRepository>` entry so a future `-SNAPSHOT` version has somewhere to go.
  - `[low]` `[patch]` Blind Hunter: `graphrag-core/CHANGELOG.md` links to a git tag (`graphrag-core-1.0.0`) that this diff never creates — a dead link until/unless that tag is pushed separately. Patch: reword to describe the tag as something that will be created at actual release time, not a live link.
  - `[medium]` `[patch]` Blind Hunter (deepened by Intent-alignment-auditor's Reading C): `maven-source-plugin`/`maven-javadoc-plugin` are bound unconditionally to the `package` phase rather than gated behind a release profile — discovered mid-review that `.github/workflows/ci.yml` runs `mvn -B clean install` (no `-DskipTests`) on every push/PR to `main`, so every routine CI build for *any* change to *any* module now also runs Javadoc generation on `graphrag-core`, which can fail the whole build on a malformed tag once real Javadoc content lands (per the separate follow-up spec). Patch: move both plugin executions into a Maven profile (e.g. `release`) that only activates on request, so routine `install`/CI builds don't depend on Javadoc succeeding.
  - `[false]` `[reject]` Edge Case Hunter (claims-check): the spec's Intent/Tasks sections claim "67 files repo-wide" needed import updates — verified: 67 was the *total* pre-implementation grep count (spanning `graphrag-core`'s own ~41 moved files plus ~26 dependent-module files), not specifically the dependent-file count as the prose implies; the actual dependent-file touch count is ~26 (22 `.java` + 4 `pom.xml`). The acceptance criterion itself (zero remaining references) is independently verified satisfied regardless. Fix would edit this build's spec's existing prose; excluded per triage rules.
  - `[false]` `[reject]` Blind Hunter: claimed the verification grep's `--include="*.java" --include="pom.xml"` scope can't back the acceptance criterion's "anywhere" wording, since YAML/properties/other file types could slip through undetected — refuted empirically: an unrestricted `grep -rln "com.graphraglens.core" .` (no `--include` filter) across the whole repository returns only historical `_bmad-output/*.md` spec/audit files (deliberately unedited historical records) and the new `CHANGELOG.md`'s own prose describing the change — no YAML, properties, or other live config file references the old package anywhere.
  - `[reject]` Blind Hunter: `<licenses>` metadata was added only to `graphrag-core/pom.xml`, not the other four modules or the root aggregator — out of scope: the audit and this spec's own intent are specifically about `graphrag-core`'s reusability as a published library; the other modules are the application itself, never intended to be published/depended-upon, so POM-level `<licenses>` metadata (which matters for a published artifact's registry listing) doesn't apply to them the same way. The root-level `LICENSE` file already covers the whole repository's legal terms regardless.
  - `[false]` `[reject]` Blind Hunter: `LICENSE`'s copyright line uses a single year ("2026") rather than a range, implying the project predates that year — refuted: every commit/date observed throughout this session's work on this repository is dated 2026; a single year is accurate, not an omission.
  - `[reject]` Blind Hunter: no `NOTICE` file was added alongside `LICENSE` — not required: Apache-2.0's NOTICE mechanism exists to carry forward *other* projects' attribution requirements, and `graphrag-core` has zero runtime dependencies (verified repeatedly across this and the prior audit) — there is nothing to attribute.
  - `[reject]` Blind Hunter: no comment/evidence in the diff that the spec's own called-for check ("confirm whether module versions are independently settable") was actually performed — the check's result is evidenced by the correct outcome itself (the dependent modules' own versions were correctly left untouched/inherited while only their `graphrag-core` dependency coordinate changed), not by a missing narration comment.
  - `[reject]` Blind Hunter: no README yet documents the new `io.graphrag` coordinates for a would-be consumer — explicitly out of scope per this spec's own Design Notes, which split README/Javadoc/`package-info.java` content into a separate follow-up spec on purpose (different review shape: prose quality, not build correctness).
  - `[false]` Verification-gap: (no gaps found — traced the rename through the actual CI build path (`.github/workflows/ci.yml`'s `mvn clean install`), ran the equivalent locally, confirmed source/javadoc jars are genuinely produced, not just declared).
  - `[medium]` `[patch]` Intent-alignment-auditor: the `<distributionManagement>`/source-jar/javadoc-jar claims are asserted in POM text and prose but nothing in the diff demonstrates the jars are actually produced — same underlying concern as the CI-coupling finding above; grouped there for the fix, but recording as its own row since it names a distinct angle (verification, not build-hygiene). Resolved by adding an explicit `mvn package -pl graphrag-core` check (confirming sources/javadoc jars exist) to this spec's own Verification section, so it's part of the record going forward.
  - `[false]` `[reject]` Intent-alignment-auditor: argued the diff should demonstrate an actual publish or registry-facing proof, not just prepare the ground for one — already deliberately excluded by this spec's own `Never` boundary (no `mvn deploy`, no real credentials — left to the maintainer per their own stated answer); not a gap, a disclosed and intentional scope boundary.
- patch outcomes: pending — 4 patch-routed findings (version-property indirection, GitHub-Packages-auth caveat + snapshotRepository, dead CHANGELOG tag link, source/javadoc plugins moved to a release profile) sent back to the implementation subagent; this pass's log will be updated with outcomes once verified.

## Design Notes

Scope split from GitHub #29's full punch list: this spec covers the two **Blocking** items (LICENSE, generic identity, real version) plus the **Publishing setup** items that are pure POM/config (no registry credentials needed) — `<distributionManagement>` etc. are declared but never invoked. The **Adoption friction** and **Polish** items (README, port Javadoc, `package-info.java`, remaining class Javadoc) are a separate follow-up spec, since they're a documentation-content change with a very different review shape (prose quality, not build correctness) from this mechanical rename.

`io.graphrag` was chosen because the audit document itself proposed it as an example generic identity when framing the branding question — reusing an already-considered, non-arbitrary suggestion rather than inventing a new one. Apache-2.0 was chosen as the most common, permissive, patent-safe default for a Java library with no stated licensing preference from the maintainer; GitHub Packages was chosen as the `<distributionManagement>` target over Maven Central for the same reason (lowest setup cost, and this repository already lives on GitHub) — both are easily changed later since neither commits to an actual publish in this pass.

## Verification

**Commands:**
- `grep -rn "com.graphraglens.core" --include="*.java" --include="pom.xml" . | grep -v /target/` -- expected: zero output
- `mvn -q -B clean install` -- expected: full reactor green, every module, every existing test
- `mvn -q -B package -pl graphrag-core -am -Prelease` -- expected: `graphrag-core-1.0.0.jar`, `-sources.jar`, and `-javadoc.jar` all produced in `graphrag-core/target/` (added post-review, once the source/javadoc plugins moved behind the `release` profile — confirms they still work, not just that they parse)

**Manual checks (if no CLI):**
- Open `graphrag-core/pom.xml` and confirm the new groupId/version/publishing metadata are present and well-formed.

## Auto Run Result

- Status: **done**
- Summary: Renamed `graphrag-core`'s own Maven coordinates and Java package from `com.graphraglens`/`com.graphraglens.core.*` to a generic identity (`io.graphrag`/`io.graphrag.core.*`), added an Apache-2.0 `LICENSE`, cut `graphrag-core`'s first real release (`1.0.0`) with a `CHANGELOG.md`, and added publish-ready POM metadata (`<licenses>`/`<scm>`/`<developers>`/`<url>`/`<distributionManagement>`, source-jar/javadoc-jar plugins gated behind an opt-in `release` profile). No `mvn deploy` was run and no registry credentials were created — actual publishing is left to the maintainer, per their own answer to the audit's open question. README/Javadoc/`package-info.java` content is a separate follow-up spec.
- Files changed:
  - `LICENSE` (new, repo root) — Apache License 2.0.
  - `graphrag-core/src/main/java/com/graphraglens/core/**` → `io/graphrag/core/**` (30 files, `git mv`), `graphrag-core/src/test/java/...` likewise (11 files).
  - 26 dependent files across `graphrag-web`/`graphrag-adapter-{neo4j,langchain4j,parsing}` (main + test) — import statements updated to `io.graphrag.core.*`; their own `com.graphraglens.{web,adapter}.*` packages untouched.
  - `graphrag-core/pom.xml` — explicit `io.graphrag` groupId, version `1.0.0`, full publish metadata, source/javadoc plugins moved to an opt-in `release` profile.
  - Root `pom.xml` and the four dependent modules' `pom.xml` — added a shared `<graphrag-core.version>` property, referenced instead of a hardcoded literal.
  - `graphrag-core/CHANGELOG.md` (new) — first `1.0.0` entry.
- Review findings breakdown:
  - Patched (4; 3 medium, 1 low): a hardcoded version literal duplicated across 4 POMs (now a shared property); an undocumented GitHub-Packages-auth-for-download tradeoff plus a missing `<snapshotRepository>`; a dead CHANGELOG tag link; source/javadoc plugins unconditionally bound to `package` (now gated behind a `release` profile, discovered mid-review that `.github/workflows/ci.yml` runs `mvn clean install` on every push, which made this a real CI-coupling risk). All fixed and reverified, including confirming the `release` profile still produces all three jars.
  - Rejected (6) / false (3): a spec-prose file-count inaccuracy (67 total vs. ~26 dependent files — fix would edit the spec); a claimed verification-grep scope gap (refuted by an unrestricted repo-wide grep finding nothing else); `<licenses>` metadata requested for the non-library modules (out of scope); a LICENSE copyright-year nitpick (refuted — every commit in this repo is dated 2026); a missing `NOTICE` file (not required — zero runtime dependencies); no README yet (explicitly deferred to the follow-up spec); a request for actual publish/registry proof (explicitly excluded by this spec's own `Never` boundary).
- Follow-up review recommendation: **true** — 3 medium-severity patches were applied on this first pass. Named residual risk: this was a large, cross-cutting mechanical rename (26 dependent files plus 5 POMs); while `mvn clean install` passing across the full reactor is strong evidence of correctness, a rename of this size touching every module benefits from a second look, especially around the newly-added `release` profile's interaction with the actual CI workflow on a real push (not just local verification).
- Verification performed: `mvn -q -B clean install` (full reactor, 95/95 tests, 0 failures/errors, both before and after patching) plus `mvn package -pl graphrag-core -am -Prelease` (confirms all 3 artifacts still produced with the profile gating in place) and a repo-wide unrestricted grep confirming zero remaining `com.graphraglens.core` references anywhere outside historical/unedited spec and audit markdown files.
- Residual risks: the named rename-size risk above; the GitHub-Packages-vs-Maven-Central tradeoff is now documented but still unresolved — the maintainer should revisit it before ever running an actual publish.
