package dev.rabauer.graphrag.core.usecase;

import java.util.List;

/**
 * What one extraction run did: how many Text Units it processed and which
 * ones failed (only with {@link FailurePolicy#ISOLATE_ITEM}; otherwise the
 * first failure stops the run). A failed unit is still persisted as a Text
 * Unit, without Entities or Relationships.
 *
 * @param textUnits the number of Text Units processed
 * @param failures  the units whose extraction failed, in order
 */
public record ExtractionReport(int textUnits, List<UnitFailure> failures) {

    public ExtractionReport {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /** Whether every unit was extracted. */
    public boolean isComplete() {
        return failures.isEmpty();
    }

    /**
     * One failed Text Unit.
     *
     * @param textUnitId   the unit's id
     * @param documentName its document
     * @param passage      its 1-based passage number within the document
     * @param message      the failure message
     */
    public record UnitFailure(String textUnitId, String documentName, int passage, String message) {
    }
}
