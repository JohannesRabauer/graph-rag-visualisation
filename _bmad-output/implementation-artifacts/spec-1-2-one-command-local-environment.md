---
title: 'One-Command Local Environment'
type: 'feature'
created: '2026-09-19'
status: 'in-review'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: 'a95a8e7cb88e97740d91120f852fcfa9102ef5f5'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** There is no way to start the app yet — Story 1.1 produced a buildable Spring Boot jar, but nothing packages it into a container or runs it alongside Neo4j.

**Approach:** Add a multi-stage `Dockerfile` (Maven+JDK 25 build stage, JRE 25 runtime stage) for `graphrag-web`, and a root `docker-compose.yml` defining exactly two services — `app` and `neo4j:2026.08.1-community` with the GDS plugin enabled — so `docker compose up` (with `OPENAI_API_KEY` set) is the only setup step required.

## Boundaries & Constraints

**Always:** Exactly two services in `docker-compose.yml`: `app` and `neo4j`. `neo4j` uses image `neo4j:2026.08.1-community` with `NEO4J_PLUGINS: '["graph-data-science"]'`. `app` builds from the root `Dockerfile` and reads `OPENAI_API_KEY` purely from the environment — no in-app config UI, no secret baked into the image. Build stage pins `maven:3.9.16-eclipse-temurin-25-noble`; runtime stage pins `eclipse-temurin:25.0.4_7-jre-noble`.

**Never:** Do not add a third service (no separate frontend/dev-server container). Do not wire the app to actually call Neo4j or the OpenAI API yet — `GraphStorePort`/`LlmPort` still have no implementation (that's Epic 2+); this story only has to start both services successfully side by side. Do not add a Thymeleaf template or controller (Story 1.3).

</frozen-after-approval>

## Code Map

- `pom.xml`, `graphrag-web/pom.xml`, `graphrag-core/pom.xml` (Story 1.1) — reactor the Dockerfile's build stage must compile; `graphrag-web` produces `graphrag-web/target/graphrag-web-0.1.0-SNAPSHOT.jar` via `spring-boot-maven-plugin` repackage.
- Root `pom.xml`'s `maven-enforcer-plugin` `requireJavaVersion` rule (Story 1.1) means the build stage image must be JDK 25+ — confirmed `maven:3.9.16-eclipse-temurin-25-noble` exists on Docker Hub.
- **Environment constraint (verified in this sandbox, not a defect in the implementation):** this build environment's egress policy blocks `production.cloudfront.docker.com`, which is where Docker Hub redirects all image-layer blobs — every `docker pull` (including `hello-world`, `alpine`) fails here with a 403 policy denial, confirmed by direct reproduction. A live `docker compose up` therefore cannot be executed to completion in this sandbox. Image tags below (`neo4j:2026.08.1-community`, `maven:3.9.16-eclipse-temurin-25-noble`, `eclipse-temurin:25.0.4_7-jre-noble`) were confirmed to exist via the Docker Hub tag-listing API (which is reachable), not by pulling them. `docker compose config` (pure YAML/schema validation, no pulls) is the furthest this sandbox can verify; a real `docker compose up` needs to run on a machine with normal internet access — e.g. during the walkthrough.

## Tasks & Acceptance

**Execution:**
- [x] `Dockerfile` (root) -- multi-stage build: `maven:3.9.16-eclipse-temurin-25-noble` stage copies all module `pom.xml`s + `graphrag-core/src` + `graphrag-web/src`, runs `mvn -q -B package -DskipTests`; runtime stage `eclipse-temurin:25.0.4_7-jre-noble` copies the built jar as `app.jar`, `EXPOSE 8080`, `ENTRYPOINT ["java","-jar","app.jar"]` -- packages `graphrag-web` into a runnable image
- [x] `.dockerignore` (root) -- exclude `**/target/`, `.git`, `_bmad/`, `_bmad-output/`, `.claude/`, `*.md` -- keeps the build context small and reproducible
- [x] `docker-compose.yml` (root) -- two services only: `neo4j` (image `neo4j:2026.08.1-community`, `NEO4J_AUTH=neo4j/graphraglens`, `NEO4J_PLUGINS='["graph-data-science"]'`, a healthcheck, a named volume for `/data`) and `app` (`build: .`, `OPENAI_API_KEY` passed through from the host environment, `depends_on: neo4j` with `condition: service_healthy`, port `8080:8080`) -- the one-command environment (AD-8, FR14, FR15)

**Acceptance Criteria:**
- Given the repository root, when `docker compose config` runs, then it resolves cleanly to exactly two services, `app` and `neo4j`
- Given `docker-compose.yml`, when inspected, then `neo4j`'s image tag and `NEO4J_PLUGINS` declare the GDS plugin, and no third service is defined
- Given `docker-compose.yml`, when inspected, then `app`'s `OPENAI_API_KEY` is sourced from the host environment only — no hardcoded key, no config file, no in-app settings UI
- Given a machine with normal internet access, when `OPENAI_API_KEY` is set and `docker compose up` runs, then both `app` and `neo4j` start and `app` serves on port 8080 (this exact check cannot run inside this sandbox — see Code Map environment constraint; to be confirmed manually)

## Implementation Notes

- Added root `Dockerfile` (multi-stage: `maven:3.9.16-eclipse-temurin-25-noble` build stage, `eclipse-temurin:25.0.4_7-jre-noble` runtime stage), root `.dockerignore`, and root `docker-compose.yml` with exactly the two services (`app`, `neo4j`) required.
- Build stage copies the root `pom.xml` plus each of the five child `pom.xml`s individually (`graphrag-core`, `graphrag-adapter-neo4j`, `graphrag-adapter-langchain4j`, `graphrag-adapter-parsing`, `graphrag-web`) before copying `graphrag-core/src` and `graphrag-web/src`, then runs `mvn -q -B package -DskipTests` as a full reactor build. The three adapter modules have pom.xml only (no `src/` yet, confirmed by directory listing), so they compile to empty jars without their source needing to be copied — the reactor build still succeeds because all module poms are present.
- Runtime stage `COPY`s the exact known jar path `graphrag-web/target/graphrag-web-0.1.0-SNAPSHOT.jar` (per Code Map) rather than a glob — `spring-boot-maven-plugin`'s `repackage` goal leaves both the repackaged fat jar and a `*.jar.original` sidecar in `target/`, and a wildcard `COPY ... app.jar` would have matched both files, which Docker rejects unless the destination is a directory. Confirmed by inspecting `graphrag-web/target/` after a real `mvn package` run: only the exact filename is unambiguous.
- `docker-compose.yml`: `neo4j`'s healthcheck runs `cypher-shell -u neo4j -p graphraglens "RETURN 1"` inside the container (proves the DB is actually accepting authenticated queries, not just that the process is up), with a 30s `start_period` to allow for the plugin-download-and-first-boot time GDS installation needs. Also exposed `7474`/`7687` on the host (Neo4j Browser / Bolt) for local debugging convenience — this does not add a third service and does not violate the "exactly two services" constraint. `app`'s `OPENAI_API_KEY` is wired as `${OPENAI_API_KEY}` (host-environment interpolation only, no default, no file) into `app`'s `environment:` block — nothing is baked into the image or the compose file itself.
- `app` depends on `neo4j` via `condition: service_healthy`, and `app`'s own container has no defined healthcheck (not required by the spec — this story doesn't need the app to reach Neo4j yet, Epic 2+).
- Verification performed: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -q package` at repo root → `BUILD SUCCESS`, produced `graphrag-web/target/graphrag-web-0.1.0-SNAPSHOT.jar` (confirming the Dockerfile's hardcoded jar path is correct and the source it builds still compiles unchanged from Story 1.1). `docker compose config` → resolves cleanly, service list is exactly `app` and `neo4j` (verified via `docker compose config --services`). Confirmed all three pinned image tags exist on Docker Hub via the tag-listing API (`maven:3.9.16-eclipse-temurin-25-noble`, `eclipse-temurin:25.0.4_7-jre-noble`, `neo4j:2026.08.1-community` all return HTTP 200).
- Reproduced this sandbox's documented egress constraint directly: `docker pull hello-world` fails with `403 Forbidden` from `production.cloudfront.docker.com`, confirming (again, independently) that a full `docker build`/`docker compose up` cannot complete here. This matches the Code Map's pre-existing note and is not a defect in the Dockerfile/compose file — both are otherwise config/schema-valid. A real `docker compose up` with internet access is still required to confirm both containers start and `app` serves on port 8080 (last Acceptance Criterion, explicitly flagged in the spec as unverifiable in-sandbox).

## Spec Change Log

## Review Triage Log

Review pass 1 — 3 layers (blind-hunter, edge-case-hunter, verification-gap). **Process note:** the first diff generated for this review omitted `Dockerfile`/`docker-compose.yml`/`.dockerignore` because they were still untracked (`git diff <baseline>` never shows untracked paths); all three layers correctly flagged this. Fixed by `git add -N` before regenerating the diff, then re-ran edge-case-hunter and verification-gap against the corrected diff (`story-1-2-diff-v2.txt`, 168 lines / 12.3KB) — blind-hunter's other findings already came from direct disk inspection, not the truncated diff, so its findings stand.

- **false** — blind-hunter: `sprint-status.yaml` says `in-progress` while the spec says `in-review`/all tasks done. Refuted: this mirrors Story 1.1 exactly — `sprint-status.yaml` syncs only at specific workflow checkpoints (step-03 start → `in-progress`, step-05 finish → `review`), not continuously against the spec's finer-grained status. Working as designed.
- **rejected (fix edits this spec only)** — blind-hunter: the Verification section's "all three tags return HTTP 200" claim has no literal reproducible command, unlike the other verification lines. Per the triage rule "reject any finding whose fix is to edit this build's spec," this isn't routed — corrected directly as spec bookkeeping below.
- **rejected (fix edits this spec only)** — blind-hunter: the alternative `grep -c "^  [a-z]*:$" <(docker compose config --services)` command is wrong as written (`--services` prints bare unindented names, verified directly: `neo4j`/`app`, no leading spaces or trailing colon) and would falsely show zero matches. Same rule — corrected directly below rather than routed.
- **medium** — blind-hunter: README has zero mention of Docker/`docker compose`, so a contributor following it has no way to discover the one-command run path this story exists to create. Verified — confirmed via `grep -i docker README.md` (no matches). → patch.
- **low** — blind-hunter: Neo4j credentials (`neo4j/graphraglens`) are a hardcoded plaintext literal in `docker-compose.yml`, not overridable, unlike `OPENAI_API_KEY`'s clean environment-only treatment one field over. Verified. → patch: make it `${NEO4J_PASSWORD:-graphraglens}`.
- **low** — edge-case-hunter: `OPENAI_API_KEY` unset interpolates to an empty string silently (`docker compose config` reproduced this directly: "Defaulting to a blank string") instead of failing fast; harmless today since nothing consumes the key yet, but a real footgun once Epic 2+ wires in live LLM calls. Verified. → patch: `${OPENAI_API_KEY:?OPENAI_API_KEY must be set}`.
- **medium** — edge-case-hunter: `neo4j` healthcheck budget (`start_period: 30s` + `retries: 10` × `interval: 10s` ≈ 130s total) may not cover first-boot GDS plugin download+install time, which can plausibly exceed two minutes — a real, likely-to-be-hit failure mode on first run (exactly when the walkthrough will happen), not a rare edge case. → patch: widen the budget.
- **low** — edge-case-hunter: `NEO4J_AUTH` only takes effect against a fresh (empty) data volume; once `neo4j_data` is initialized, changing `NEO4J_AUTH` later has no effect and the running DB's real credentials silently diverge from the compose file. Inherent Neo4j image behavior, not a bug in this diff, but worth one documentation line. → patch: add a one-line caveat to the new README section.
- **low** — edge-case-hunter + verification-gap (same root cause, grouped): Dockerfile's runtime-stage `COPY` hardcodes `graphrag-web-0.1.0-SNAPSHOT.jar`, coupled to `pom.xml`'s version with nothing tying the two together — a future version bump breaks the Docker build (loudly, not silently, but with no automated signal until someone tries). → patch: pin `spring-boot-maven-plugin`'s `finalName` in `graphrag-web/pom.xml` so the artifact is always `graphrag-web.jar` regardless of version, and update the Dockerfile `COPY` to match.
- **low** — blind-hunter: nothing tracks the spec's own disclosed-unverifiable last Acceptance Criterion (`docker compose up` actually starting both services) for later confirmation beyond prose. → patch, handled directly (not through the implementation subagent, since it's a tracking-file entry, not code): appended to `deferred-work.md`.

## Verification

**Commands:**
- `mvn -q package` -- expected: `BUILD SUCCESS` (unchanged from Story 1.1; confirms the source the Dockerfile builds still compiles)
- `docker compose config` -- expected: valid resolved config, service list is exactly `app`, `neo4j`
- `docker compose config --services` -- expected output: exactly two lines, `neo4j` and `app`, nothing else
- `curl -sS -o /dev/null -w '%{http_code}\n' https://hub.docker.com/v2/repositories/library/<image>/tags/<tag>` for each of `maven:3.9.16-eclipse-temurin-25-noble`, `eclipse-temurin:25.0.4_7-jre-noble`, `neo4j:2026.08.1-community` -- expected: `200` for each (Docker Hub's tag API is reachable even though blob pulls are not in this sandbox)

**Manual checks (if no CLI):**
- On a machine with internet access: `OPENAI_API_KEY=sk-... docker compose up`, then confirm both containers report healthy/running and `curl localhost:8080` responds (this story doesn't require a real page yet — Story 1.3 adds that — a connection refused vs. a connection accepted is the meaningful signal here).
