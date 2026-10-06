package dev.rabauer.graphrag.core.usecase;

/**
 * What a use case does when one item of a batch of model calls fails
 * (one Community summary, one Text Unit extraction, one DRIFT sub-question
 * derivation). Failures are always visible; the policy only decides whether
 * they stop the run.
 */
public enum FailurePolicy {

    /** The first failure propagates and stops the run (the default). */
    FAIL_RUN,

    /**
     * The failing item falls back to its deterministic result, is marked as
     * failed with its error message, and counted in the run's result; the run
     * continues with the next item.
     */
    ISOLATE_ITEM
}
