package dev.rabauer.graphrag.core.llm;

/**
 * A model's reply could not be used for one item (one Community summary, one
 * Text Unit's extraction, one set of DRIFT sub-questions), even after the
 * corrective retry. The use cases fail that item only when their
 * {@code FailurePolicy} is {@code ISOLATE_ITEM}; otherwise it stops the run.
 */
public class LlmReplyException extends RuntimeException {

    private final PromptedLlmPort.Purpose purpose;
    private final int attempts;
    private final String lastReply;

    public LlmReplyException(PromptedLlmPort.Purpose purpose, int attempts, String lastReply, String message,
                             Throwable cause) {
        super(message, cause);
        this.purpose = purpose;
        this.attempts = attempts;
        this.lastReply = lastReply == null ? "" : lastReply;
    }

    /** What the failed call was for. */
    public PromptedLlmPort.Purpose purpose() {
        return purpose;
    }

    /** How many replies were tried (1, or 2 with the corrective retry). */
    public int attempts() {
        return attempts;
    }

    /** The last raw reply. */
    public String lastReply() {
        return lastReply;
    }
}
