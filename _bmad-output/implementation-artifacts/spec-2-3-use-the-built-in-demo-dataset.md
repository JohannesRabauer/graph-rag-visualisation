---
title: 'Use the Built-in Demo Dataset'
type: 'feature'
created: '2026-09-19'
status: 'in-progress'
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
