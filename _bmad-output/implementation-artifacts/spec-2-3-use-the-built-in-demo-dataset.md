---
title: 'Use the Built-in Demo Dataset'
type: 'feature'
created: '2026-09-19'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context: []
baseline_commit: '841de9189b68032d4fb2ec63edb7d9b792e108dd'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The app already accepts user-uploaded text and PDF corpora, but it still has no one-click built-in dataset path. The empty-state UI offers only upload controls, so the demo dataset cannot be selected without custom manual setup or an ad hoc workaround.

**Approach:** Add a second primary path in the empty state that triggers a built-in Sherlock Holmes demo corpus, return it through the same `/api/corpora` style response contract, and persist it in the existing in-memory `CorpusStore` so the corpus chip and rest of the app can treat it exactly like an uploaded corpus. Keep the dataset bundled inside the app and add the minimum UI/button wiring needed to reach it without changing later Epic 3+ behavior.

## Boundaries & Constraints

**Always:** The demo dataset feeds the same `Corpus` model and `CorpusStore` conventions as uploaded corpora; the app should not special-case it in a divergent path or create a second storage mechanism. The built-in dataset is a convenience path into the same ingestion pipeline and should appear as a peer to upload rather than a fallback or default. `graphrag-core` remains framework-free, and the web layer remains the place where the demo pipeline is assembled.

**Never:** Do not add a database, filesystem-based corpus registry, or a second Neo4j path. Do not implement LLM extraction, graph writing, or the SSE progress stream yet; this story only establishes the corpus object and UI affordance. Do not replace the upload flow or hide the upload option behind the demo dataset.

</frozen-after-approval>

## Implementation Notes

- `graphrag-core/src/main/java/com/graphraglens/core/domain/Corpus.java` — add a `name` field while preserving the existing constructor used by the upload path, so older call sites remain valid and the demo dataset can set a friendly label such as `Sherlock Holmes — Demo Dataset`.
- `graphrag-web/src/main/java/com/graphraglens/web/DemoDatasetService.java` — new Spring component that creates the built-in Sherlock Holmes corpus with several sample documents and a corpus name matching the design language for the app bar chip.
- `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` — add `POST /api/corpora/demo` to persist the demo corpus in `CorpusStore` and return the same `corpusId`, `documentNames`, `documentCount`, and `name` payload shape as uploads.
- `graphrag-web/src/main/resources/templates/index.html` — add a second empty-state action next to the upload control: a button labeled `Use the built-in Sherlock Holmes Demo Dataset`.
- `graphrag-web/src/main/resources/static/js/upload.js` — handle the demo button, POST to `/api/corpora/demo`, and render the corpus chip with the same success/error UI already used for uploaded files.
- `graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` — add coverage for the demo-data endpoint, verifying it returns `201`, stores the corpus, and exposes the expected name/document list.
- `graphrag-web/src/test/java/com/graphraglens/web/MainControllerTest.java` — assert the demo button is rendered in the empty state.

### Resume notes (2026-09-19)

Found this story already implemented on disk (all files above present, `POST /api/corpora/demo` wired, demo button in `index.html`, `upload.js` handling, and the two test assertions in place) as part of a prior commit (`3c17d55`) that landed several backlog stories together. Verified rather than re-implemented:

- `Corpus` has a `name` field with the existing constructor preserved (a second constructor defaults the name for old call sites).
- `DemoDatasetService.createSherlockCorpus()` returns a 3-document Sherlock Holmes corpus named `Sherlock Holmes — Demo Dataset`.
- `POST /api/corpora/demo` stores the corpus in `CorpusStore` and returns the same `corpusId`/`name`/`documentNames`/`documentCount` shape as the upload endpoint.
- Full `mvn test` run (12 tests in `graphrag-web`, plus core/adapter modules) is green under JDK 25.

**Boundary discrepancy (noted, not re-litigated):** this story's `Never` clause says not to implement LLM extraction, graph writing, or the SSE progress stream yet. The same commit that finished this story also implemented stories 2-4/2-5/2-6/4-1/4-2 (already `done` in sprint-status.yaml), so `CorpusController.useDemoDataset()` now also kicks off `BuildKnowledgeGraph` + `DetectCommunities` and emits SSE progress, same as the upload path. That work is in scope for those later stories, not this one; recorded here only so the boundary text isn't misread as still true.

## Review Triage Log

Blind Hunter review (N = min(floor(sqrt(39kB) + 1), 10) = 7; 9 findings returned) against the demo-dataset files:

- **patch** — `MainControllerTest` asserted the demo button's visible text but not its `id`, so a copy-only id rename would pass tests while silently breaking `upload.js`'s `getElementById('demo-dataset-button')` wiring. Added an `id="demo-dataset-button"` assertion.
- **patch** — `CorpusControllerTest`'s demo-dataset test asserted only names/count, not document content, so a regression that truncated or emptied the three hardcoded Sherlock passages would go undetected. Added content assertions.
- **patch** — The demo button gave no loading feedback beyond `disabled`, so a slow response looked inert. Added `aria-busy` and a "Loading demo dataset…" label swap, restored on completion.
- **false** — `Corpus`'s canonical constructor doesn't null-check `documents`, so a caller passing `null` would later NPE in `documentNames()`/`documentCount()`. Disproof: both current callers (`CorpusController.upload` via `files.stream()...toList()`, `DemoDatasetService.createSherlockCorpus()` via `List.of(...)`) always pass a non-null list; no reachable path triggers this today.
- **defer** — No retry/reset affordance in the empty-state shell after a corpus loads (upload or demo); a failed background build leaves the user stuck until a page reload. Shared pre-existing shell behavior from earlier stories, not introduced here; needs a UX decision, not a one-line patch. Logged in `deferred-work.md`.
- **defer** — `connectProgressStream` never listens for the `ingestion-complete` SSE event, so the UI gives no positive signal when a knowledge graph build finishes. Pre-existing gap from Story 2.5, not caused by this resume session. Logged in `deferred-work.md`.
- **defer** — `Corpus.defaultName()` joins every uploaded filename with no truncation, so multi-file uploads can produce an unreadable corpus-chip label. Pre-existing from Story 2.1's upload path. Logged in `deferred-work.md`.
- **rejected (low)** — Demo content is inlined as Java string literals rather than bundled `.txt` resources. A real idea for easier review/diffing, but cosmetic (dev-only, no user-facing failure) and the fix would mean restructuring how the demo dataset is loaded, more than a simple correction.
