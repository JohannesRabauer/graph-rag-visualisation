---
title: 'Upload a Plain Text Corpus'
type: 'feature'
created: '2026-09-19'
status: 'in-progress'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '3a5d514d5efc798c5bcf4ecb12351dde62e19d40'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 1.3 built a purely static resting canvas with no way to actually bring a Corpus into the app — the empty state has no upload control, and there's no backend concept of a Corpus at all yet.

**Approach:** Add a `POST /api/corpora` endpoint accepting one or more `.txt` files, a core `IngestCorpus` use case that validates each file's extension via `DocumentParserPort` and creates a `Corpus`, an in-memory `CorpusStore` (Corpus identity lives in `graphrag-web`, not Neo4j, per the architecture spine), and the empty-state upload control + Corpus chip + error banner UI to drive it.

## Boundaries & Constraints

**Always:** Extension validation goes through `DocumentParserPort.supports(filename)` — never a raw string check in `graphrag-web` (AD-9: file-type knowledge must not leak into the web layer). `graphrag-core` and `graphrag-adapter-parsing` stay framework-free (AD-1) — `graphrag-web` wires the parser adapter as a Spring `@Bean` in a `@Configuration` class, not via `@Component`/`@Qualifier` in the adapter module. Uploaded file content is held in memory only (no disk/DB persistence — matches NFR3's local-only, no-restart-durability scope). The Corpus chip and error banner use the exact `DESIGN.md` component tokens already defined in `instrument.css` (`--active`, `--active-soft`, corpus-chip radius/typography) — no new colors invented.

**Never:** Do not implement actual LLM extraction, Neo4j writes, or the SSE progress stream yet (Story 2.4/2.5) — "queued for Knowledge Graph construction" means the `Corpus` exists in `CorpusStore`, ready for a future extraction step to pick up; no job queue or worker infrastructure is built now. Do not add PDF support (Story 2.2) or the Demo Dataset button (Story 2.3) — the empty state shows only the upload control for now. Do not add file-size or file-count limits (not specified anywhere in the PRD/architecture — inventing one would be scope creep).

</frozen-after-approval>

## Code Map

- `graphrag-core/src/main/java/com/graphraglens/core/port/DocumentParserPort.java` (Story 1.1) — currently an empty marker interface; this story gives it its first real method (`supports`), used for dispatch/validation only — actual text extraction is Story 2.4, so no `parse(...)` method is added yet (would be unused).
- `graphrag-web/src/main/resources/templates/index.html`, `.../static/css/instrument.css` (Story 1.3) — the idle-state canvas and app-bar this story extends; app-bar currently shows only the brand mark (no corpus-chip slot yet, per Story 1.3's explicit scope). `instrument.css` already defines every color/typography token this story's new CSS needs (`--active`, `--active-soft`, `--ink-900`, `--radius-full`, `--font-mono`) — reuse, don't invent.
- `graphrag-web/src/main/resources/static/js/.gitkeep` (Story 1.1) — placeholder; this story adds the project's first real JS file here.
- **Corpus identity, per the architecture spine:** a Corpus is *not* a Neo4j node — its identity/bookkeeping lives in `graphrag-web` (an in-memory store), while Entities/Relationships extracted from it (Story 2.4+) become the Neo4j source of truth. This story's `CorpusStore` is that in-memory registry.
- **Wiring pattern:** `graphrag-web` declares a `@Configuration` class with a `@Bean` method constructing `PlainTextDocumentParserAdapter`; Spring auto-collects it into a `List<DocumentParserPort>` injected into `IngestCorpus`. This avoids adding a Spring dependency to `graphrag-adapter-parsing` and avoids `@Qualifier`-based type dispatch in `graphrag-web`, satisfying AD-9's explicit "Prevents" clause.

## Tasks & Acceptance

**Execution:**
- [ ] `graphrag-core/src/main/java/com/graphraglens/core/port/DocumentParserPort.java` -- add `boolean supports(String filename)` -- the dispatch contract adapters implement
- [ ] `graphrag-core/src/main/java/com/graphraglens/core/domain/UploadedDocument.java` -- new record `(String filename, String content)` -- framework-free input to the use case
- [ ] `graphrag-core/src/main/java/com/graphraglens/core/domain/Corpus.java` -- new record `(String id, List<UploadedDocument> documents)` with `documentNames()`/`documentCount()` helpers -- the domain object the chip and later stories read
- [ ] `graphrag-core/src/main/java/com/graphraglens/core/domain/UnsupportedFileTypeException.java` -- new unchecked exception carrying the rejected filename -- signals an unsupported upload without any HTTP concept leaking into core
- [ ] `graphrag-core/src/main/java/com/graphraglens/core/usecase/IngestCorpus.java` -- new use case: constructor takes `List<DocumentParserPort>`; validates every document's filename against all parsers (`supports`), throws `UnsupportedFileTypeException` on the first unsupported one, else returns a new `Corpus` with a generated UUID id -- the one place upload validation logic lives
- [ ] `graphrag-adapter-parsing/src/main/java/com/graphraglens/adapter/parsing/PlainTextDocumentParserAdapter.java` -- new class implementing `DocumentParserPort`; `supports` returns true iff the filename ends with `.txt` (case-insensitive) -- first real adapter for this port
- [ ] `graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java` -- new `@Configuration` with a `@Bean` method returning `new PlainTextDocumentParserAdapter()` -- wires the adapter without adding Spring to `graphrag-adapter-parsing`
- [ ] `graphrag-web/src/main/java/com/graphraglens/web/CorpusStore.java` -- new `@Component`, `ConcurrentHashMap<String, Corpus>`-backed, `put`/`get` -- the in-memory Corpus registry (identity lives here, not Neo4j)
- [ ] `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- new `@RestController`, `POST /api/corpora` accepting `List<MultipartFile> files`; maps each to `UploadedDocument` (UTF-8 decoded content), calls `IngestCorpus`, stores the result in `CorpusStore`, returns `201` with `{"corpusId", "documentNames", "documentCount"}`; an `@ExceptionHandler` for `UnsupportedFileTypeException` returns `400` with `{"error": "<plain-language message naming the rejected file>"}` -- the upload entry point
- [ ] `graphrag-web/src/main/resources/static/js/upload.js` -- new plain JS: wires the file input's `change` event, `fetch`-POSTs to `/api/corpora` as `multipart/form-data`, on success renders the corpus-chip into the app-bar, on 400 renders the error banner with the response's `error` message -- the only client-side script in the project so far
- [ ] `graphrag-web/src/main/resources/templates/index.html` -- replace the idle-state copy with an upload control (`<input type="file" multiple accept=".txt">` + a plain-language prompt), an empty corpus-chip slot in the app-bar (hidden until populated), an empty error-banner slot in the canvas (hidden until populated), and a `<script>` tag loading `upload.js` -- the actual UI this story delivers
- [ ] `graphrag-web/src/main/resources/static/css/instrument.css` -- add `.corpus-chip` and `.error-banner` rules using the existing `DESIGN.md` component tokens (no new colors) -- styles for the two new UI pieces

**Acceptance Criteria:**
- Given the app's empty state, when one or more `.txt` files are selected and uploaded, then `POST /api/corpora` returns `201` with a `corpusId`, and the Corpus chip appears in the app bar naming the uploaded file(s)
- Given the app's empty state, when a file with an unsupported extension is uploaded, then `POST /api/corpora` returns `400` with a plain-language `error` message naming the problem, and the UI shows the Error banner rather than silently ignoring the file
- Given a successful upload, when the server-side `CorpusStore` is inspected, then the created `Corpus` is present, keyed by its `corpusId`, with all uploaded document names and content preserved
- Given `graphrag-core/pom.xml` and `graphrag-adapter-parsing/pom.xml`, when inspected, then neither declares a Spring dependency (AD-1's enforcer rule from Story 1.1 already fails the build if `graphrag-core` violates this; `graphrag-adapter-parsing` is checked manually here since it has no such rule yet)

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `mvn -q package` -- expected: `BUILD SUCCESS`, including the new `IngestCorpus`/`CorpusController` code
- `mvn -pl graphrag-web spring-boot:run` then `curl -F "files=@/tmp/test.txt" http://localhost:8080/api/corpora` -- expected: `201`, JSON body with `corpusId` and `documentNames: ["test.txt"]`
- `curl -F "files=@/tmp/test.pdf" http://localhost:8080/api/corpora` (any non-`.txt` file) -- expected: `400`, JSON body `{"error": "..."}` naming the rejected file
- `grep -riE "spring" graphrag-adapter-parsing/pom.xml` -- expected: no matches

**Manual checks (if no CLI):**
- Open `http://localhost:8080/`, upload a `.txt` file via the browser control, confirm the corpus chip appears with the filename; then try a `.jpg` and confirm the error banner appears with a clear message.
