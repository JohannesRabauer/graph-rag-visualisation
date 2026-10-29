package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.adapter.langchain4j.OpenAiLlmPort;
import dev.rabauer.graphrag.core.domain.UnreadableDocumentException;
import dev.rabauer.graphrag.core.domain.UnsupportedFileTypeException;
import dev.rabauer.graphrag.core.usecase.SemanticMatchingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Base of every corpus REST controller: the {@code @ExceptionHandler}s the
 * former single {@code CorpusController} declared, unchanged, so each concern
 * controller maps the same exceptions to the same statuses and bodies
 * (Spring resolves handlers declared on a controller's superclass too).
 */
abstract class CorpusApiController {

    private static final Logger LOG = LoggerFactory.getLogger(CorpusApiController.class);

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedFileType(UnsupportedFileTypeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
        problem.setTitle("Unsupported file type");
        problem.setProperty("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(problem);
    }

    @ExceptionHandler(UnreadableDocumentException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableDocument(UnreadableDocumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    /**
     * Story 15.1: embedding the question (or the vector lookup) failed at query
     * time. No keyword fallback is attempted, so the outage stays visible.
     */
    @ExceptionHandler(SemanticMatchingException.class)
    public ResponseEntity<Map<String, String>> handleSemanticMatchingFailure(SemanticMatchingException ex) {
        LOG.warn("Semantic matching failed at query time: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", CorpusMessages.SEMANTIC_MATCHING_FAILURE_MESSAGE));
    }

    /**
     * Story 15.2: the answer-synthesis LLM call failed at query time. There is
     * no templated fallback, so the failure stays visible.
     */
    @ExceptionHandler(OpenAiLlmPort.LlmCallFailedException.class)
    public ResponseEntity<Map<String, String>> handleLlmCallFailure(OpenAiLlmPort.LlmCallFailedException ex) {
        LOG.warn("LLM call failed at query time: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", CorpusMessages.ANSWER_SYNTHESIS_FAILURE_MESSAGE));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> handleUncheckedIOException(UncheckedIOException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to read an uploaded file. Please try again."));
    }
}
