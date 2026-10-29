package dev.rabauer.graphrag.web;

/**
 * The user-facing messages shared by the corpus controllers (ingestion
 * failures, query failures and the 409 gates). Moved verbatim out of the former
 * single {@code CorpusController}; the wording is part of the API.
 */
final class CorpusMessages {

    static final String EXTRACTION_FAILURE_MESSAGE =
            "The LLM call failed during knowledge graph extraction. Nothing was retried — try again when ready.";
    static final String COMMUNITY_DETECTION_FAILURE_MESSAGE =
            "Community detection failed after the knowledge graph was extracted (is the Neo4j Graph Data Science "
                    + "plugin available?). Nothing was retried — try again when ready.";
    static final String EMBEDDING_FAILURE_MESSAGE =
            "The embedding call failed while preparing Entities and Communities for semantic search. "
                    + "Nothing was retried — try again when ready.";
    static final String SEMANTIC_MATCHING_FAILURE_MESSAGE =
            "Semantic matching failed: the embedding call for your question did not succeed, so no starting "
                    + "points could be found. Nothing was retried — try again when ready.";
    static final String ANSWER_SYNTHESIS_FAILURE_MESSAGE =
            "The LLM call failed while writing the answer to your question. Nothing was retried — "
                    + "try again when ready.";
    static final String GRAPH_BUILDING_MESSAGE =
            "The graph is still building for this corpus. Wait for “Knowledge Graph — Ready”, then ask your question.";
    static final String GRAPH_FAILED_MESSAGE =
            "Graph construction failed for this corpus. Upload again or use the demo dataset to restart.";
    static final String OFFLINE_QUERY_BLOCKED_MESSAGE =
            "This is the offline demo corpus — its questions are pre-recorded, not live. Load a live corpus "
                    + "(upload your own, or the live Demo Dataset) to ask your own question.";

    private CorpusMessages() {
    }
}
