- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-project-skeleton-module-boundaries.md`
  summary: Add a CI workflow that re-runs the project's build/verification commands (mvn validate, mvn package, the AD-1 dependency guard) on every push.
  evidence: No CI/CD is mentioned anywhere in the Epic 1 context, PRD, or architecture spine, so standing one up is new, unscoped infrastructure (provider config, JDK 25 setup) rather than a trivial fix within Story 1.1. Currently nothing re-runs these checks automatically outside a developer's own machine.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-2-one-command-local-environment.md`
  summary: RESOLVED — confirmed end-to-end during the Epic 1 walkthrough (human ran `OPENAI_API_KEY=... docker compose up` on a machine with normal internet access, 2026-09-19). Neo4j pulled the pinned image, installed the GDS plugin automatically, became healthy; `app` built and started on port 8080 gated on that health check, exactly as designed.
  evidence: This sandbox's egress policy blocks Docker Hub's image-layer CDN, so this could only be verified by a human outside the sandbox — see spec-1-2's Implementation Notes for the sandbox-side verification that was already done (Dockerfile correctness, `mvn package`, `docker compose config`). Kept here as a record rather than deleted.

- source_spec: `_bmad-output/implementation-artifacts/spec-2-1-upload-a-plain-text-corpus.md`
  summary: Validate that uploaded `.txt` content is actually text (not a binary file renamed to `.txt`) before it reaches extraction — e.g. a UTF-8 decodability check or a lightweight content-sniff, with a clear rejection error if it fails.
  evidence: Extension-only validation (`DocumentParserPort.supports(filename)`) is this story's explicit, by-design scope (AD-9 is filename-based dispatch), and nothing consumes the stored content yet — no actual harm occurs until Story 2.4's extraction tries to feed garbled content to an LLM. Best addressed alongside that story's actual parsing work rather than bolted on here.

- source_spec: `_bmad-output/implementation-artifacts/spec-2-1-upload-a-plain-text-corpus.md`
  summary: Define Corpus lifecycle/eviction semantics — `CorpusStore` currently only grows (no `remove`, no "replace the active corpus" concept), and it's undecided whether a session can have more than one active Corpus at a time.
  evidence: Low urgency for a single-user local dev tool restarted frequently, and no current AC or architecture doc settles the multi-corpus question. Best decided alongside Story 2.3 (Demo Dataset) or 2.4 (extraction), once there's an actual second code path that needs to know "the current Corpus."
