package dev.rabauer.graphrag.core.domain;

/**
 * One source passage a synthesized Local Search answer cites (Story 15.2).
 * The answer's {@code [i]} marker refers to the {@code i}-th citation.
 *
 * @param textUnitId   the cited Text Unit's id; never null
 * @param documentName the document the passage was cut from
 * @param excerpt      the first 200 characters of the passage,
 *                     whitespace-collapsed, with "…" when cut
 */
public record Citation(String textUnitId, String documentName, String excerpt) {

    public Citation {
        textUnitId = textUnitId == null ? "" : textUnitId;
        documentName = documentName == null ? "" : documentName;
        excerpt = excerpt == null ? "" : excerpt;
    }
}
