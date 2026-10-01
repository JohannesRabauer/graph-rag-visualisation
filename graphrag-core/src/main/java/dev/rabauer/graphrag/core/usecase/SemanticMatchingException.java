package dev.rabauer.graphrag.core.usecase;

/**
 * Thrown when semantic seed matching fails at query time: embedding the
 * question or the graph store's similarity lookup threw. The searches never
 * fall back to keyword matching in that case, so the failure stays visible.
 */
public class SemanticMatchingException extends RuntimeException {

    public SemanticMatchingException(String message, Throwable cause) {
        super(message, cause);
    }
}
