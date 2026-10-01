package dev.rabauer.graphrag.core.usecase;

/**
 * Progress report for one extracted Text Unit, delivered after that unit's
 * extraction has been persisted and before its Entities/Relationships are
 * reported.
 *
 * @param index        1-based position of the unit among all units of the corpus
 * @param total        total number of Text Units in the corpus
 * @param documentName the filename of the document the unit was cut from
 * @param textUnitId   stable id of the Text Unit
 * @param ordinal      0-based position of the Text Unit within its document
 */
public record TextUnitProgress(int index, int total, String documentName, String textUnitId, int ordinal) {

    public TextUnitProgress(int index, int total, String documentName) {
        this(index, total, documentName, "", -1);
    }
}
