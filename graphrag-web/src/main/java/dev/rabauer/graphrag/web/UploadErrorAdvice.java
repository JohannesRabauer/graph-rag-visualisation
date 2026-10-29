package dev.rabauer.graphrag.web;

import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Answers a too-large upload with 413 and an RFC 9457 problem body.
 *
 * <p>The multipart size check fails while the request is being resolved, before
 * a controller is chosen, so a handler inside {@code CorpusController} would never
 * see it; this advice is therefore global. It handles that one exception only,
 * which only multipart endpoints can raise, so no other endpoint's errors change.
 */
@RestControllerAdvice
public class UploadErrorAdvice {

    private final MultipartProperties properties;

    public UploadErrorAdvice(MultipartProperties properties) {
        this.properties = properties;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleTooLarge(MaxUploadSizeExceededException ex) {
        String detail = "The upload is too large. Each file may be at most "
                + format(properties.getMaxFileSize().toBytes())
                + " and one upload at most " + format(properties.getMaxRequestSize().toBytes()) + ".";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, detail);
        problem.setTitle("Upload too large");
        problem.setProperty("error", detail);
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem);
    }

    private static String format(long bytes) {
        return bytes % (1024 * 1024) == 0 ? (bytes / (1024 * 1024)) + " MB" : (bytes / 1024) + " KB";
    }
}
