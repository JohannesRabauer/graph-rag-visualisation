package dev.rabauer.graphrag.core.llm.shadow;

/**
 * Stands in for a client library's message type (Spring AI's {@code Message},
 * for example) that an application imports or declares next to its
 * {@code PromptedLlmPort} subclass.
 */
public record Message(String content) {
}
