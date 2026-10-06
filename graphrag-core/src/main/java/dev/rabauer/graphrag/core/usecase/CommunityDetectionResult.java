package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;

import java.util.List;

/**
 * The outcome of one {@link DetectCommunities} run: the persisted
 * Communities and, per Community, how its summary was obtained.
 *
 * <p>A run is {@link Status#PARTIAL} when at least one summary
 * {@link SummaryStatus#FAILED failed} (with failures isolated per item) or was
 * {@link SummaryStatus#SKIPPED_BUDGET skipped by the budget}. A partial result
 * is still valid: every detected Community and membership is persisted, the
 * affected ones with the deterministic title and summary.
 *
 * @param corpusId    the corpus the Communities belong to
 * @param status      {@link Status#COMPLETE}, {@link Status#PARTIAL} or
 *                    {@link Status#EMPTY} (no group reached the minimum size)
 * @param communities the persisted Communities, {@code community-1..n}
 * @param summaries   one outcome per Community, in Community order
 * @param elapsedMs   wall time of the run in milliseconds
 */
public record CommunityDetectionResult(String corpusId, Status status, List<Community> communities,
                                       List<SummaryOutcome> summaries, long elapsedMs) {

    public CommunityDetectionResult {
        corpusId = corpusId == null ? "" : corpusId;
        status = status == null ? Status.EMPTY : status;
        communities = communities == null ? List.of() : List.copyOf(communities);
        summaries = summaries == null ? List.of() : List.copyOf(summaries);
    }

    /** How many summaries ended with {@code status}. */
    public int count(SummaryStatus status) {
        return (int) summaries.stream().filter(outcome -> outcome.status() == status).count();
    }

    /** Whether the run finished every summary as asked. */
    public boolean isComplete() {
        return status != Status.PARTIAL;
    }

    /** The overall state of a run. */
    public enum Status {
        /** Every summary was generated, reused or deterministic by design. */
        COMPLETE,
        /** At least one summary failed or was skipped by the budget; the result is still valid. */
        PARTIAL,
        /** No group reached the minimum Community size; nothing was persisted. */
        EMPTY
    }

    /** How one Community's title and summary were obtained. */
    public enum SummaryStatus {
        /** The {@code LlmPort} wrote it. */
        GENERATED,
        /** Taken from a stored Community with the same content hash; no port call. */
        REUSED,
        /** The deterministic title and summary: no port, or the port returned null. */
        DETERMINISTIC,
        /** The port failed and failures are isolated per item; the deterministic summary was used. */
        FAILED,
        /** The run's budget (Community count or wall time) was used up; the deterministic summary was used. */
        SKIPPED_BUDGET
    }

    /**
     * One Community's summary outcome.
     *
     * @param communityId the Community's id
     * @param status      how the summary was obtained
     * @param error       the failure message for {@link SummaryStatus#FAILED}, else {@code ""}
     */
    public record SummaryOutcome(String communityId, SummaryStatus status, String error) {
        public SummaryOutcome {
            communityId = communityId == null ? "" : communityId;
            error = error == null ? "" : error;
        }
    }
}
