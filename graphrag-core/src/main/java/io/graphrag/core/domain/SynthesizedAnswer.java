package io.graphrag.core.domain;

/**
 * What an answer-synthesizing LLM returned for a Local Search context
 * (Story 15.2): either an answer text with inline {@code [n]} citations,
 * or the signal that the context does not answer the question.
 *
 * @param notInContext true when the model said the context does not answer
 *                     the question
 * @param text         the raw answer text, citations not yet resolved;
 *                     never null
 */
public record SynthesizedAnswer(boolean notInContext, String text) {

    /** The answer text a model is told to give when the context does not answer the question. */
    public static final String NOT_IN_CONTEXT = "NOT_IN_CONTEXT";

    public SynthesizedAnswer {
        text = text == null ? "" : text;
    }

    /**
     * Whether {@code text} is the {@value #NOT_IN_CONTEXT} sentinel, ignoring
     * case, surrounding whitespace and quotes, and trailing punctuation
     * (e.g. {@code "not_in_context."} or {@code "\"NOT_IN_CONTEXT\""}).
     */
    public static boolean isNotInContextSentinel(String text) {
        if (text == null) {
            return false;
        }
        String stripped = text.strip()
                .replaceAll("^[\\s\"'`“”‘’]+", "")
                .replaceAll("[\\s\"'`“”‘’.!?,;:]+$", "");
        return NOT_IN_CONTEXT.equalsIgnoreCase(stripped);
    }
}
