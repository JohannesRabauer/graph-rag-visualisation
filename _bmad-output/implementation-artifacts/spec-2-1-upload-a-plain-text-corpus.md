---
title: 'Upload a Plain Text Corpus'
type: 'feature'
created: '2026-09-19'
status: 'done'
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
- [x] `graphrag-core/src/main/java/com/graphraglens/core/port/DocumentParserPort.java` -- add `boolean supports(String filename)` -- the dispatch contract adapters implement
- [x] `graphrag-core/src/main/java/com/graphraglens/core/domain/UploadedDocument.java` -- new record `(String filename, String content)` -- framework-free input to the use case
- [x] `graphrag-core/src/main/java/com/graphraglens/core/domain/Corpus.java` -- new record `(String id, List<UploadedDocument> documents)` with `documentNames()`/`documentCount()` helpers -- the domain object the chip and later stories read
- [x] `graphrag-core/src/main/java/com/graphraglens/core/domain/UnsupportedFileTypeException.java` -- new unchecked exception carrying the rejected filename -- signals an unsupported upload without any HTTP concept leaking into core
- [x] `graphrag-core/src/main/java/com/graphraglens/core/usecase/IngestCorpus.java` -- new use case: constructor takes `List<DocumentParserPort>`; validates every document's filename against all parsers (`supports`), throws `UnsupportedFileTypeException` on the first unsupported one, else returns a new `Corpus` with a generated UUID id -- the one place upload validation logic lives
- [x] `graphrag-adapter-parsing/src/main/java/com/graphraglens/adapter/parsing/PlainTextDocumentParserAdapter.java` -- new class implementing `DocumentParserPort`; `supports` returns true iff the filename ends with `.txt` (case-insensitive) -- first real adapter for this port
- [x] `graphrag-web/src/main/java/com/graphraglens/web/config/ParserConfig.java` -- new `@Configuration` with a `@Bean` method returning `new PlainTextDocumentParserAdapter()` -- wires the adapter without adding Spring to `graphrag-adapter-parsing`
- [x] `graphrag-web/src/main/java/com/graphraglens/web/CorpusStore.java` -- new `@Component`, `ConcurrentHashMap<String, Corpus>`-backed, `put`/`get` -- the in-memory Corpus registry (identity lives here, not Neo4j)
- [x] `graphrag-web/src/main/java/com/graphraglens/web/CorpusController.java` -- new `@RestController`, `POST /api/corpora` accepting `List<MultipartFile> files`; maps each to `UploadedDocument` (UTF-8 decoded content), calls `IngestCorpus`, stores the result in `CorpusStore`, returns `201` with `{"corpusId", "documentNames", "documentCount"}`; an `@ExceptionHandler` for `UnsupportedFileTypeException` returns `400` with `{"error": "<plain-language message naming the rejected file>"}` -- the upload entry point
- [x] `graphrag-web/src/main/resources/static/js/upload.js` -- new plain JS: wires the file input's `change` event, `fetch`-POSTs to `/api/corpora` as `multipart/form-data`, on success renders the corpus-chip into the app-bar, on 400 renders the error banner with the response's `error` message -- the only client-side script in the project so far
- [x] `graphrag-web/src/main/resources/templates/index.html` -- replace the idle-state copy with an upload control (`<input type="file" multiple accept=".txt">` + a plain-language prompt), an empty corpus-chip slot in the app-bar (hidden until populated), an empty error-banner slot in the canvas (hidden until populated), and a `<script>` tag loading `upload.js` -- the actual UI this story delivers
- [x] `graphrag-web/src/main/resources/static/css/instrument.css` -- add `.corpus-chip` and `.error-banner` rules using the existing `DESIGN.md` component tokens (no new colors) -- styles for the two new UI pieces

**Acceptance Criteria:**
- Given the app's empty state, when one or more `.txt` files are selected and uploaded, then `POST /api/corpora` returns `201` with a `corpusId`, and the Corpus chip appears in the app bar naming the uploaded file(s)
- Given the app's empty state, when a file with an unsupported extension is uploaded, then `POST /api/corpora` returns `400` with a plain-language `error` message naming the problem, and the UI shows the Error banner rather than silently ignoring the file
- Given a successful upload, when the server-side `CorpusStore` is inspected, then the created `Corpus` is present, keyed by its `corpusId`, with all uploaded document names and content preserved
- Given `graphrag-core/pom.xml` and `graphrag-adapter-parsing/pom.xml`, when inspected, then neither declares a Spring dependency (AD-1's enforcer rule from Story 1.1 already fails the build if `graphrag-core` violates this; `graphrag-adapter-parsing` is checked manually here since it has no such rule yet)

## Implementation Notes

- Core additions exactly as specified: `DocumentParserPort.supports(String)`, the `UploadedDocument`/`Corpus`/`UnsupportedFileTypeException` domain types, and `IngestCorpus` (validates every document against every registered parser, throws on the first unsupported filename, else returns a `Corpus` with a generated `UUID` id). `Corpus.documentNames()`/`documentCount()` are stream-derived, not stored fields.
- `PlainTextDocumentParserAdapter` (new `graphrag-adapter-parsing` module code — the module previously had no `src/`) does a case-insensitive `.endsWith(".txt")` check, null-safe.
- `graphrag-adapter-parsing/pom.xml` gained a `junit-jupiter` test-scope dependency (with its own `junit-bom` import, mirroring `graphrag-core/pom.xml`'s pattern) so `PlainTextDocumentParserAdapterTest` could be added; still no Spring dependency anywhere in the module.
- `graphrag-web/pom.xml` gained a dependency on `graphrag-adapter-parsing` (needed for `ParserConfig` to construct the adapter). `ParserConfig` (`@Configuration`) declares two `@Bean` methods: one for the `DocumentParserPort` (`new PlainTextDocumentParserAdapter()`), and one for `IngestCorpus` itself, taking the auto-collected `List<DocumentParserPort>` — this was the one small addition beyond the spec's literal Code Map (the Code Map's wiring-pattern note describes the parser bean but `IngestCorpus` also needs a Spring-side factory method to be injectable into `CorpusController`; keeping it in the same `ParserConfig` avoided an extra file for a single `@Bean` method).
- `CorpusController.upload` reads each `MultipartFile` fully into memory (`file.getBytes()`, UTF-8-decoded) — no disk/temp-file usage, matching the in-memory-only constraint. An `IOException` while reading bytes is wrapped in `UncheckedIOException` (not spec'd, but needed for the method reference in the `.map(this::toUploadedDocument)` stream to compile without a checked-exception leak).
- `index.html`: the idle state keeps its exact Story 1.3 eyebrow/subtitle copy and adds an `<input type="file" id="corpus-file-input" multiple accept=".txt">` plus a plain-language `<label>`, a `#corpus-chip` slot in the `.app-bar` (`hidden` until populated), and a `#error-banner` slot (`role="alert"`, `hidden`) at the top of `.canvas`. `upload.js` is loaded via a trailing `<script>` tag (both a Thymeleaf `th:src` and a static `src` fallback, matching the existing `th:href`/`href` pattern already used for `instrument.css`).
- `upload.js`: on the file input's `change` event, builds a `FormData` with every selected file under the `files` field (matching `@RequestParam("files")`), `fetch`-POSTs to `/api/corpora`, and on a 2xx response renders the chip (`<filenames joined by ", "> · <N> document(s)`, plus a `.status-dot` span) and hides any prior error banner; on a non-2xx response it shows the banner with the response body's `error` text (or a generic fallback if the body is unparseable/network fails). The file input is reset after each attempt so re-selecting the same file re-fires `change`.
- `instrument.css`: added `.corpus-chip`/`.corpus-chip .status-dot` (background `--paper`, border `--line`, radius `--radius-full`, `--font-mono`/`--font-mono-size`, per `DESIGN.md`'s `components.corpus-chip`) and `.error-banner` (background `--active-soft`, border `--active`, per `components.error-banner`) — no new color tokens. `.app-bar` gained `justify-content: space-between` to push the chip to the right; `.canvas` gained `flex-direction: column` so the error banner stacks above the idle content instead of sitting beside it. Removed the now-stale `static/js/.gitkeep` placeholder since `upload.js` is the project's first real JS file.
- Updated `MainControllerTest` (Story 1.3's test): its `doesNotContain("upload")` / `doesNotContain("<script")` assertions were the *old* Story 1.3 boundary, which this story explicitly supersedes per its own Boundaries ("replace the idle-state copy with an upload control... and a `<script>` tag loading `upload.js`"). Removed those two now-incorrect assertions, kept the rest (exact eyebrow/subtitle copy, `instrument.css` link, still no chat/composer/Cytoscape), and added a second test asserting the new upload control, corpus-chip slot, error-banner slot, and `upload.js` script tag are present.
- Added automated test coverage beyond the spec's literal file list, to back the Acceptance Criteria with something that reruns rather than only prose/manual `curl`: `IngestCorpusTest` (core, stub `DocumentParserPort` lambdas — supported/unsupported/first-unsupported-wins/multi-parser-OR), `PlainTextDocumentParserAdapterTest` (case-insensitivity, null-safety, rejection), and `CorpusControllerTest` (`@SpringBootTest` + `@AutoConfigureMockMvc` + `MockMultipartFile`, covering the 201/400 responses and asserting the `CorpusStore` actually holds the created `Corpus` with its document names and content — AC3).

## Spec Change Log

## Review Triage Log

Review pass 1 — 3 layers (blind-hunter, edge-case-hunter, verification-gap), diff at commit 8693eb0 vs baseline 3a5d514 (56.7KB, all 20 changed files correctly included this time).

- **medium** — blind-hunter + edge-case-hunter (same root cause, grouped): `POST /api/corpora` has no guard for a zero/missing `files` parameter — an empty or absent `files` field either creates a 0-document `Corpus` (201) or falls through to Spring's default `MissingServletRequestParameterException` response, neither matching AC1's "one or more files" framing or the project's `{"error": ...}` shape. Verified by reading `CorpusController.upload` directly — no `isEmpty()` check exists. → patch.
- **medium** — verification-gap (pre-verified, filed disposition weighed) + blind-hunter + edge-case-hunter (same root cause, grouped): `toUploadedDocument`'s `IOException` path (`file.getBytes()` failing) throws `UncheckedIOException`, uncaught by any handler — falls through to Spring Boot's default error body, breaking the documented single `{"error": "<message>"}` response contract (`epic-2-context.md:41`) for exactly the failure mode the epic's own top-line goal calls out ("visible, honest errors... never a silent hang or crash"). Verified: confirmed via direct code read — only `UnsupportedFileTypeException` has an `@ExceptionHandler`; no test exercises a read failure. → patch.
- **low** — blind-hunter + edge-case-hunter (same root cause, grouped): `MultipartFile.getOriginalFilename()` isn't null-guarded — a null filename produces the confusing message `Unsupported file type: "null". Only .txt files are supported.` instead of a genuinely plain-language message. Verified. → patch.
- **low** — blind-hunter: the corpus-chip's success update has no `aria-live`/`role="status"`, while the error banner has `role="alert"` — an accessibility parity gap (upload success isn't announced to assistive tech, only failure is). Verified via direct inspection of `index.html`/`upload.js`. → patch.
- **low** — blind-hunter: no integration test exercises a mixed batch (one valid `.txt` + one invalid file in the same request) confirming a 400 *and* that no partial `Corpus` gets registered in `CorpusStore`. The underlying behavior is already correct by construction (`IngestCorpus` validates every document before creating the `Corpus`, so no partial registration can occur), but nothing proves it. → patch: add the test.
- **low** — edge-case-hunter: reselecting files while a previous `/api/corpora` `fetch` is still pending can let out-of-order responses render a stale/mismatched corpus chip. Verified plausible by reading `upload.js` — no in-flight guard exists. → patch: disable the file input while a request is in flight, matching edge-case-hunter's own suggested guard.
- **defer** — blind-hunter: uploaded content is decoded as UTF-8 unconditionally with no check that the bytes are actually text, so a binary file renamed to `.txt` is silently accepted and stored as garbled content. Real, but out of scope for this story by design: the Boundaries explicitly scope validation to `DocumentParserPort.supports(filename)` (extension-based, per AD-9's own filename-dispatch wording), and nothing consumes the stored content yet (extraction is Story 2.4) — no actual harm occurs until then. Deferred to be addressed alongside Story 2.4's actual parsing/extraction work.
- **defer** — blind-hunter: `CorpusStore` only ever grows (`put`, no `remove`/eviction), and there's no defined "replace the current corpus" or multi-corpus semantics — every upload leaves its `Corpus` resident in memory for the life of the process. Real but low-urgency for a single-user local dev tool restarted frequently, and the multi-corpus question (can a session have more than one active Corpus?) isn't settled by any current AC or architecture doc — deferred pending that design decision, likely alongside Story 2.3 (Demo Dataset) or 2.4.
- **false** — blind-hunter: `Corpus` has no `name` field, so a future Demo Dataset story (2.3) will need to "retrofit" one. Refuted as a defect in *this* story: the current filename-based chip fully satisfies Story 2.1's own AC, and adding a name field now with nothing to use it for would be premature (YAGNI) — normal incremental evolution when Story 2.3 actually needs it, not a gap in this story.
- **rejected (low, both conditions met)** — blind-hunter: no de-duplication for two files sharing the same filename in one upload. Unlikely to be encountered in everyday use, and the fix requires a real design decision (rename? merge? reject?) rather than a direct correction — rejected per the triage rule.
- **rejected (low, no demonstrated bad outcome)** — blind-hunter: a zero-byte `.txt` file is accepted without comment. No concrete harm at this story's scope (nothing processes content yet); it will simply yield zero entities at future extraction time (Story 2.4), which is a benign, self-resolving outcome, not a defect to guard against now.

## Verification

**Commands run:**
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn package` (full reactor) -- `Tests run: 4/4/2/3` across `IngestCorpusTest`, `PlainTextDocumentParserAdapterTest`, `MainControllerTest`, `CorpusControllerTest`, all `Failures: 0, Errors: 0` -- `BUILD SUCCESS`
- `grep -riE "spring" graphrag-adapter-parsing/pom.xml` -- no matches (confirmed AC4; the only other `spring` hits in the repo are `graphrag-core/pom.xml`'s enforcer-rule text, which bans Spring rather than depending on it)
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn install -DskipTests` (needed once so `graphrag-core`/`graphrag-adapter-parsing` snapshot jars exist in `~/.m2` for a standalone module run) then, from `graphrag-web/`, `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn spring-boot:run &`
- `curl -i -F "files=@/tmp/test.txt" http://localhost:8080/api/corpora` -- `201`, `{"documentNames":["test.txt"],"corpusId":"1f5855d3-f7b5-4c09-b38d-00cf9d9d5cc5","documentCount":1}`
- `curl -i -F "files=@/tmp/test.pdf" http://localhost:8080/api/corpora` -- `400`, `{"error":"Unsupported file type: \"test.pdf\". Only .txt files are supported."}`
- `curl http://localhost:8080/` -- 200 OK; body contains the `#corpus-chip` (hidden) and `#error-banner` (hidden) slots, the `<input type="file" ... accept=".txt">` control, and the `upload.js` script tag
- `curl -o /dev/null -w "%{http_code}" http://localhost:8080/css/instrument.css` and `.../js/upload.js` -- both `200`
- Process stopped cleanly after verification; confirmed no lingering `spring-boot`/`graphrag` process afterward

**Result:** All four Acceptance Criteria pass — 201/corpusId/documentNames on `.txt` upload, 400/plain-language `error` naming the file on an unsupported upload, the created `Corpus` is present in `CorpusStore` with its document names and content preserved (`CorpusControllerTest`), and neither `graphrag-core/pom.xml` nor `graphrag-adapter-parsing/pom.xml` declares a Spring dependency.

**Manual checks (if no CLI):**
- Open `http://localhost:8080/`, upload a `.txt` file via the browser control, confirm the corpus chip appears with the filename; then try a `.jpg` and confirm the error banner appears with a clear message.
