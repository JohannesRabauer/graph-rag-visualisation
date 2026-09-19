- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-project-skeleton-module-boundaries.md`
  summary: Add a CI workflow that re-runs the project's build/verification commands (mvn validate, mvn package, the AD-1 dependency guard) on every push.
  evidence: No CI/CD is mentioned anywhere in the Epic 1 context, PRD, or architecture spine, so standing one up is new, unscoped infrastructure (provider config, JDK 25 setup) rather than a trivial fix within Story 1.1. Currently nothing re-runs these checks automatically outside a developer's own machine.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-one-command-local-environment.md`
  summary: Confirm end-to-end that `OPENAI_API_KEY=... docker compose up` actually starts both `app` and `neo4j` and that `app` serves on port 8080, on a machine with normal internet access.
  evidence: This sandbox's egress policy blocks Docker Hub's image-layer CDN (`production.cloudfront.docker.com`), confirmed by reproducing a 403 on `docker pull hello-world`/`alpine`/`neo4j`, so a real `docker compose up` cannot be executed here. Everything short of that (Dockerfile correctness by inspection, `mvn package`, `docker compose config` schema validation) was verified; only the live run is outstanding.
