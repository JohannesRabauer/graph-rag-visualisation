---
title: 'Upload a PDF Corpus'
type: 'feature'
created: '2026-09-19'
status: 'ready-for-dev'
route: 'dispatch'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The app currently accepts only `.txt` uploads, so users cannot ingest PDFs and cannot use the same corpus workflow with common source documents. This blocks Epic 2 Story 2.2 and leaves the empty-state upload path incomplete.

**Approach:** Extend the existing parser-port dispatch pipeline with a PDF parser adapter backed by PDFBox, update upload affordances to accept PDFs alongside text files, and return visible plain-language errors when a PDF has no extractable text while keeping all validation and error-shape conventions from Story 2.1.

## Boundaries & Constraints

**Always:** Keep file-type knowledge in `DocumentParserPort` adapters and dispatch through `IngestCorpus` (no extension checks in web/UI code). Keep `graphrag-core` framework-free and keep Spring wiring only in `graphrag-web` configuration. Support mixed `.txt` + `.pdf` uploads in one request using the same `/api/corpora` endpoint and response shape. Use Apache PDFBox 3.x in `graphrag-adapter-parsing` for PDF text extraction checks. If a PDF yields no extractable text, return a clear visible error message naming the file, using the existing `{"error":"..."}` API contract and existing error-banner UI behavior.

**Never:** Do not add OCR, retry, fallback, or silent acceptance of non-extractable PDFs. Do not add Neo4j writes, LLM extraction, or SSE progress behavior (Story 2.4/2.5 scope). Do not add Spring dependencies to `graphrag-adapter-parsing` or `graphrag-core`. Do not replace existing TXT behavior or split upload flows into separate endpoints.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| PDF upload happy path | `POST /api/corpora` with one or more valid `.pdf` files containing extractable text | `201` with `corpusId`, `documentNames`, `documentCount`; `CorpusStore` contains the corpus; corpus chip updates as in Story 2.1 | N/A |
| Mixed TXT+PDF happy path | `POST /api/corpora` with supported `.txt` and `.pdf` files | Same successful single-corpus ingestion path; all names preserved | N/A |
| Unsupported extension | Upload includes unsupported file (e.g. `.docx`) | `400` with plain-language `error` naming rejected file | Existing unsupported-type handling via `IngestCorpus` + exception handler |
| PDF with no extractable text | Upload includes a PDF that extracts to blank/whitespace | Request rejected; user sees visible error banner and no corpus is registered | `400` with plain-language `error` naming file and explaining no extractable text |
| Missing files | No `files` provided | Rejected as in Story 2.1 | `400` with existing no-files message |

</frozen-after-approval>

## Code Map

- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/main/java/com/graphraglens/core/port/DocumentParserPort.java` -- parser dispatch contract (`supports(filename)`); keep as the extension-routing boundary.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/main/java/com/graphraglens/core/usecase/IngestCorpus.java` -- validates each uploaded document against all registered parsers; reuse unchanged for multi-parser OR behavior.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/main/java/com/graphraglens/core/domain/UnsupportedFileTypeException.java` -- current user-facing unsupported-extension message; update supported-types wording to include PDFs.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-adapter-parsing/src/main/java/com/graphraglens/adapter/parsing/PlainTextDocumentParserAdapter.java` -- existing `.txt` adapter pattern to mirror for PDF support.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-adapter-parsing/pom.xml` -- add PDFBox dependency in parsing adapter module only.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java` -- Spring-side parser bean wiring; add PDF parser bean while preserving list-based injection into `IngestCorpus`.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- upload endpoint and exception handlers; extend mapping for PDF no-text errors while preserving response shape and no-files behavior.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/templates/index.html` -- update upload affordance copy and `accept` list to advertise `.txt` and `.pdf`.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/static/js/upload.js` -- existing success/error rendering; reuse unchanged unless backend message handling requires minimal resilience updates.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/test/java/com/graphraglens/core/usecase/IngestCorpusTest.java` -- existing parser dispatch tests; extend for mixed txt/pdf parser sets.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-adapter-parsing/src/test/java/com/graphraglens/adapter/parsing/PlainTextDocumentParserAdapterTest.java` -- style reference for parser adapter tests; add parallel PDF adapter tests.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` -- endpoint acceptance tests to extend for PDF success, mixed uploads, and non-extractable PDF rejection.
- `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/_bmad-output/implementation-artifacts/spec-2-1-upload-a-plain-text-corpus.md` -- continuity source: keep Story 2.1 dispatch architecture, error envelope, and in-memory corpus registration behavior intact.

## Tasks & Acceptance

**Execution:**
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-adapter-parsing/pom.xml` -- add Apache PDFBox 3.x dependency -- enable PDF text extraction capability in parsing adapter module.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-adapter-parsing/src/main/java/com/graphraglens/adapter/parsing/PdfDocumentParserAdapter.java` -- add new `DocumentParserPort` adapter for `.pdf` support and extractable-text validation -- introduce PDF path without leaking parser logic into web/core.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/main/java/com/graphraglens/core/domain/PdfExtractionException.java` -- add domain-level exception carrying filename for non-extractable PDFs -- support clear user-visible error mapping without HTTP leakage into adapters.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/main/java/com/graphraglens/core/domain/UnsupportedFileTypeException.java` -- update supported-types wording to mention `.txt` and `.pdf` -- keep messages accurate after PDF support.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java` -- register `PdfDocumentParserAdapter` bean -- include PDF parser in existing list-based dispatch.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- add exception handler for PDF extraction failures returning `400` with `{"error":"..."}` -- keep failure surfacing visible and consistent.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/main/resources/templates/index.html` -- update upload copy and file-input `accept` attribute to include PDFs -- align UI affordance with supported formats.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-adapter-parsing/src/test/java/com/graphraglens/adapter/parsing/PdfDocumentParserAdapterTest.java` -- add adapter tests for extension support, text extraction success, and no-text failure -- prove parser behavior and edge handling.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-web/src/test/java/com/graphraglens/web/CorpusControllerTest.java` -- add endpoint tests for PDF success, mixed txt/pdf success, and non-extractable PDF rejection with no partial corpus registration -- cover story-level API and state outcomes.
- [ ] `/home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/src/test/java/com/graphraglens/core/usecase/IngestCorpusTest.java` -- add/adjust parser-combination tests as needed for txt+pdf dispatch -- protect core dispatch invariants.

**Acceptance Criteria:**
- Given the empty-state screen, when one or more extractable `.pdf` files are uploaded, then `POST /api/corpora` returns `201` with `corpusId`, `documentNames`, and `documentCount`, and the corpus chip reflects the uploaded PDF names.
- Given the empty-state screen, when a request includes supported `.txt` and `.pdf` files together, then they are accepted through the same ingestion path and stored in one created corpus.
- Given a PDF that yields no extractable text, when uploaded, then `POST /api/corpora` returns `400` with a plain-language `error` message naming the file, the UI displays the error banner, and no corpus is registered.
- Given an unsupported extension in an upload request, when processed, then the request still fails with a plain-language `400` error naming the unsupported file (existing Story 2.1 behavior preserved).
- Given module boundaries, when project dependencies are inspected, then PDFBox appears only in `graphrag-adapter-parsing` and no Spring dependency is introduced in `graphrag-core` or `graphrag-adapter-parsing`.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn test` -- expected: all module tests pass including new PDF parser and controller scenarios.
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn package` -- expected: reactor `BUILD SUCCESS`.
- `grep -riE "spring" /home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-core/pom.xml /home/runner/work/graph-rag-visualisation/graph-rag-visualisation/graphrag-adapter-parsing/pom.xml` -- expected: no Spring dependency declarations are introduced.
