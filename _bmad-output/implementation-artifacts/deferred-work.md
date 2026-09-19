- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-project-skeleton-module-boundaries.md`
  summary: Add a CI workflow that re-runs the project's build/verification commands (mvn validate, mvn package, the AD-1 dependency guard) on every push.
  evidence: No CI/CD is mentioned anywhere in the Epic 1 context, PRD, or architecture spine, so standing one up is new, unscoped infrastructure (provider config, JDK 25 setup) rather than a trivial fix within Story 1.1. Currently nothing re-runs these checks automatically outside a developer's own machine.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-one-command-local-environment.md`
  summary: RESOLVED — confirmed end-to-end during the Epic 1 walkthrough (human ran `OPENAI_API_KEY=... docker compose up` on a machine with normal internet access, 2026-09-19). Neo4j pulled the pinned image, installed the GDS plugin automatically, became healthy; `app` built and started on port 8080 gated on that health check, exactly as designed.
  evidence: This sandbox's egress policy blocks Docker Hub's image-layer CDN, so this could only be verified by a human outside the sandbox — see spec-1-2's Implementation Notes for the sandbox-side verification that was already done (Dockerfile correctness, `mvn package`, `docker compose config`). Kept here as a record rather than deleted.
