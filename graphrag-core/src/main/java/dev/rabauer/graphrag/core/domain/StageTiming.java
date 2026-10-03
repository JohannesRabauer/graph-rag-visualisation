package dev.rabauer.graphrag.core.domain;

/**
 * Where the wall-clock time of one retrieval run went: the time spent inside
 * embedding calls, the time spent inside LLM calls, and everything else
 * (graph and vector store reads, ranking, context assembly), which is
 * reported as retrieval.
 *
 * <p>LLM time covers every LLM call of the run, not only the final answer:
 * DRIFT Search's sub-question calls count here too, which is why
 * {@code llmCalls} is reported alongside it.
 *
 * @param totalMs        the whole run, in milliseconds
 * @param retrievalMs    {@code totalMs} minus embedding and LLM time
 * @param embeddingMs    time spent inside embedding calls
 * @param embeddingCalls how many embedding calls the run made
 * @param llmMs          time spent inside LLM calls
 * @param llmCalls       how many LLM calls the run made
 */
public record StageTiming(long totalMs, long retrievalMs, long embeddingMs, int embeddingCalls, long llmMs,
                          int llmCalls) {

    public StageTiming {
        totalMs = Math.max(0L, totalMs);
        embeddingMs = Math.max(0L, embeddingMs);
        llmMs = Math.max(0L, llmMs);
        retrievalMs = Math.max(0L, retrievalMs);
        embeddingCalls = Math.max(0, embeddingCalls);
        llmCalls = Math.max(0, llmCalls);
    }
}
