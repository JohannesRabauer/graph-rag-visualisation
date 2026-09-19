- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-project-skeleton-module-boundaries.md`
  summary: Add a CI workflow that re-runs the project's build/verification commands (mvn validate, mvn package, the AD-1 dependency guard) on every push.
  evidence: No CI/CD is mentioned anywhere in the Epic 1 context, PRD, or architecture spine, so standing one up is new, unscoped infrastructure (provider config, JDK 25 setup) rather than a trivial fix within Story 1.1. Currently nothing re-runs these checks automatically outside a developer's own machine.
