package io.graphrag.core.domain;

/**
 * A single, ordered touch recorded while answering a query — one Entity,
 * Relationship, or Community the retrieval process examined on its way to
 * an answer, including DRIFT Search's spawned sub-questions and final
 * synthesis output.
 *
 * <p>{@code identifier} matches the identity strings already used elsewhere
 * (an Entity's {@link Entity#normalizedIdentity()} or a Community's
 * {@link Community#id()}), so a future frontend (Story 5.2's replay) can
 * cross-reference a trace step against the same graph visualisation nodes
 * without a second identity scheme.
 */
public record RetrievalStep(Kind kind, String identifier, String label) {

    public RetrievalStep {
        java.util.Objects.requireNonNull(kind, "kind must not be null");
        identifier = identifier == null ? "" : identifier.trim();
        label = label == null ? "" : label.trim();
    }

    /**
     * The kind of retrieval event a step records.
     */
    public enum Kind {
        ENTITY,
        RELATIONSHIP,
        COMMUNITY,
        SUB_QUESTION_SPAWNED,
        SYNTHESIS,
        /**
         * Records that the query was embedded at the start of a vector-baseline answer run.
         * Identifier is {@code "query"}; label is the question text.
         */
        VECTOR_QUERY_EMBEDDED,
        /**
         * Records a single chunk retrieved by cosine similarity during a vector-baseline answer run.
         * Identifier is the chunk's {@link io.graphrag.core.domain.Chunk#id()};
         * label is the similarity score formatted as {@code "score=0.XXX"}.
         */
        VECTOR_CHUNK,
        /**
         * Records a source passage read into a synthesized Local, Global or
         * DRIFT Search answer's context (Stories 15.2, 15.3). Identifier is the
         * {@link io.graphrag.core.domain.TextUnit#id()}; label is the
         * passage excerpt.
         */
        TEXT_UNIT
    }
}
