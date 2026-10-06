package dev.rabauer.graphrag.core.retrieval;

import dev.rabauer.graphrag.core.usecase.FailurePolicy;

/**
 * How DRIFT retrieval runs: the candidate Communities are chosen like Global
 * retrieval's, each yields one sub-question (from the {@code LlmPort}), and
 * every sub-question runs a Local expansion; the items are the candidate
 * Communities plus the de-duplicated union of the branches.
 *
 * @param communities   how candidate Communities are chosen (only
 *                      {@code maxCommunities}, {@code matchMembers} and
 *                      {@code memberSeedLimit} apply)
 * @param local         how each branch expands
 * @param failurePolicy {@link FailurePolicy#ISOLATE_ITEM}: when deriving the
 *                      sub-questions fails, the deterministic sub-questions
 *                      are used and a warning is reported; otherwise the
 *                      failure propagates
 */
public record DriftRetrievalOptions(GlobalRetrievalOptions communities, LocalRetrievalOptions local,
                                    FailurePolicy failurePolicy) {

    public DriftRetrievalOptions {
        communities = communities == null ? GlobalRetrievalOptions.defaults() : communities;
        local = local == null ? LocalRetrievalOptions.defaults() : local;
        failurePolicy = failurePolicy == null ? FailurePolicy.FAIL_RUN : failurePolicy;
    }

    /** Global and Local defaults; a failing sub-question derivation falls back with a warning. */
    public static DriftRetrievalOptions defaults() {
        return new DriftRetrievalOptions(GlobalRetrievalOptions.defaults(), LocalRetrievalOptions.defaults(),
                FailurePolicy.ISOLATE_ITEM);
    }

    public DriftRetrievalOptions withCommunities(GlobalRetrievalOptions value) {
        return new DriftRetrievalOptions(value, local, failurePolicy);
    }

    public DriftRetrievalOptions withLocal(LocalRetrievalOptions value) {
        return new DriftRetrievalOptions(communities, value, failurePolicy);
    }

    public DriftRetrievalOptions withFailurePolicy(FailurePolicy value) {
        return new DriftRetrievalOptions(communities, local, value);
    }
}
