---
title: 'One-Command Local Environment'
type: 'feature'
created: '2026-09-19'
status: 'in-progress'
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
- [ ] `Dockerfile` (root) -- multi-stage build: `maven:3.9.16-eclipse-temurin-25-noble` stage copies all module `pom.xml`s + `graphrag-core/src` + `graphrag-web/src`, runs `mvn -q -B package -DskipTests`; runtime stage `eclipse-temurin:25.0.4_7-jre-noble` copies the built jar as `app.jar`, `EXPOSE 8080`, `ENTRYPOINT ["java","-jar","app.jar"]` -- packages `graphrag-web` into a runnable image
- [ ] `.dockerignore` (root) -- exclude `**/target/`, `.git`, `_bmad/`, `_bmad-output/`, `.claude/`, `*.md` -- keeps the build context small and reproducible
- [ ] `docker-compose.yml` (root) -- two services only: `neo4j` (image `neo4j:2026.08.1-community`, `NEO4J_AUTH=neo4j/graphraglens`, `NEO4J_PLUGINS='["graph-data-science"]'`, a healthcheck, a named volume for `/data`) and `app` (`build: .`, `OPENAI_API_KEY` passed through from the host environment, `depends_on: neo4j` with `condition: service_healthy`, port `8080:8080`) -- the one-command environment (AD-8, FR14, FR15)

**Acceptance Criteria:**
- Given the repository root, when `docker compose config` runs, then it resolves cleanly to exactly two services, `app` and `neo4j`
- Given `docker-compose.yml`, when inspected, then `neo4j`'s image tag and `NEO4J_PLUGINS` declare the GDS plugin, and no third service is defined
- Given `docker-compose.yml`, when inspected, then `app`'s `OPENAI_API_KEY` is sourced from the host environment only — no hardcoded key, no config file, no in-app settings UI
- Given a machine with normal internet access, when `OPENAI_API_KEY` is set and `docker compose up` runs, then both `app` and `neo4j` start and `app` serves on port 8080 (this exact check cannot run inside this sandbox — see Code Map environment constraint; to be confirmed manually)

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `mvn -q package` -- expected: `BUILD SUCCESS` (unchanged from Story 1.1; confirms the source the Dockerfile builds still compiles)
- `docker compose config` -- expected: valid resolved config, service list is exactly `app`, `neo4j`
- `grep -c "^  [a-z]*:$" <(docker compose config --services)` or simply `docker compose config --services` -- expected output: exactly `app` and `neo4j`, nothing else

**Manual checks (if no CLI):**
- On a machine with internet access: `OPENAI_API_KEY=sk-... docker compose up`, then confirm both containers report healthy/running and `curl localhost:8080` responds (this story doesn't require a real page yet — Story 1.3 adds that — a connection refused vs. a connection accepted is the meaningful signal here).
