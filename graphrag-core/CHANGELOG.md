# Changelog

All notable changes to `graphrag-core` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `GraphStorePort.detectCommunities(String corpusId)`: returns the corpus's
  Entities grouped into Communities, as lists of member identities
  (`Entity.normalizedIdentity()` format). The default implementation is the
  connected-components grouping `DetectCommunities` used before, so existing
  implementations need no change. A graph store may override it with a native
  algorithm (the Neo4j adapter uses GDS Leiden). Additive, minor change.

### Changed

- `DetectCommunities` now gets its grouping from
  `GraphStorePort.detectCommunities(...)` instead of running its own BFS.
  Community ids stay `community-1..n`, assigned in the order of each group's
  first member in `entities(corpusId)`, with members in that order, whatever
  order the port returns; an Entity the port leaves out becomes a
  single-member Community. With the default port, Community ids and member
  sets are unchanged, but members are now listed in `entities(corpusId)`
  order instead of BFS discovery order; this also changes the order of the
  `onCommunityDetected` member lists, of the persisted memberships, and of the
  names in the fallback (no-LLM) summary.

## [1.0.0] - 2026-09-27

### Changed

- **Breaking:** renamed the module's Maven coordinates and Java package
  identity from `com.graphraglens` / `com.graphraglens.core.*` to a generic,
  reusable identity: `groupId io.graphrag`, packages `io.graphrag.core.domain`,
  `io.graphrag.core.port`, and `io.graphrag.core.usecase`. Any prior
  internal-only usage under the old `com.graphraglens.core` package must
  update its imports and `graphrag-core` dependency coordinates.
- `graphrag-core` now declares its own `groupId` explicitly rather than
  inheriting it from the `graphrag-parent` reactor POM, decoupling the
  module's identity from the host application's.

### Added

- First real, non-`SNAPSHOT` release version (`1.0.0`).
- Publish-ready POM metadata: `<licenses>` (Apache License 2.0), `<scm>`,
  `<developers>`, `<url>`, and a `<distributionManagement>` entry targeting
  GitHub Packages as a low-setup-cost default registry.
- `maven-source-plugin` and `maven-javadoc-plugin` executions bound to the
  `package` phase, producing sources and javadoc JARs alongside the main
  artifact.
- Repository root `LICENSE` file (Apache License 2.0, full text).

### Notes

- No behavior changed in this release: it is a coordinate/package rename
  plus publishing metadata only. Every existing test's assertions are
  unchanged; only import paths were updated.
- No artifact was actually published to any registry as part of this
  release — `<distributionManagement>` is declared but unused pending the
  maintainer running the publish step themselves against their own
  registry account.

<!--
  The `graphrag-core-1.0.0` tag referenced below does not exist yet: it is
  the tag this release is meant to be cut under once actually released, not
  a live link. Create it (and update this link if needed) at release time.
-->
[1.0.0]: https://github.com/JohannesRabauer/graph-rag-visualisation/tree/graphrag-core-1.0.0
