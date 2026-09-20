package com.graphraglens.core.domain;

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
        SYNTHESIS
    }
}
