package io.graphrag.core.usecase;

/**
 * Progress report for one extracted Text Unit, delivered after that unit's
 * extraction has been persisted and before its Entities/Relationships are
 * reported.
 *
 * @param index        1-based position of the unit among all units of the corpus
 * @param total        total number of Text Units in the corpus
 * @param documentName the filename of the document the unit was cut from
 */
public record TextUnitProgress(int index, int total, String documentName) {
}
