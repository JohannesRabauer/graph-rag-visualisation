package dev.rabauer.graphrag.core.domain;

import java.util.Objects;

/**
 * A short summary of where GraphRAG and Vector Search differ for one
 * question, and why.
 *
 * @param text   at most a couple of sentences; never null
 * @param source whether an LLM wrote it or the deterministic rule did; never null
 */
public record ComparisonVerdict(String text, Source source) {

    /** Who wrote a verdict. */
    public enum Source {
        /** Written by an LLM from the answers and the measured facts. */
        LLM,
        /** The deterministic, rule-based summary of the measured facts. */
        RULE
    }

    public ComparisonVerdict {
        text = text == null ? "" : text.trim();
        Objects.requireNonNull(source, "source must not be null");
    }

    /**
     * The deterministic summary of {@code facts}: which side retrieved more
     * distinct documents and more context items, and how many passages
     * overlap. It makes no claim about which answer is correct.
     */
    public static ComparisonVerdict ruleBased(ComparisonFacts facts) {
        ComparisonFacts f = facts == null ? new ComparisonFacts(null, null, null, 0, 0) : facts;
        String graphName = "GraphRAG (" + f.graphMode() + ")";
        String documents = compare(graphName, f.graph().distinctDocuments(), f.vector().distinctDocuments(),
                "passages from more distinct documents", "passages from the same number of distinct documents");
        String items = compare(graphName, f.graph().contextItems(), f.vector().contextItems(),
                "more context items", "the same number of context items");
        String overlap = f.vectorPassages() == 0
                ? "Vector Search retrieved no passages to compare."
                : f.sharedPassages() + " of the " + f.vectorPassages() + " passages Vector Search retrieved "
                        + (f.sharedPassages() == 1 ? "was" : "were") + " also read by GraphRAG.";
        return new ComparisonVerdict(documents + " " + items + " " + overlap, Source.RULE);
    }

    private static String compare(String graphName, int graph, int vector, String more, String same) {
        if (graph == vector) {
            return "Both sides retrieved " + same + " (" + graph + ").";
        }
        String winner = graph > vector ? graphName : "Vector Search";
        int high = Math.max(graph, vector);
        int low = Math.min(graph, vector);
        return winner + " retrieved " + more + " (" + high + " vs " + low + ").";
    }
}
