package dev.rabauer.graphrag.core.domain;

/**
 * Which source (a file, a document) a graph element belongs to, for
 * incremental updates ({@code UpdateSources}): the {@value #ATTRIBUTE}
 * attribute when present, else the locator's path, else — for a Text Unit —
 * its document name. {@code ""} when none is known: such an element belongs
 * to no source and is never removed by source.
 */
public final class Sources {

    /** The attribute that names an element's source explicitly. */
    public static final String ATTRIBUTE = "source";

    private Sources() {
    }

    public static String of(Entity entity) {
        return of(entity.attributes().get(ATTRIBUTE), entity.locator(), "");
    }

    public static String of(Relationship relationship) {
        return of(relationship.attributes().get(ATTRIBUTE), relationship.locator(), "");
    }

    public static String of(TextUnit unit) {
        return of(unit.attributes().get(ATTRIBUTE), unit.locator(), unit.documentName());
    }

    private static String of(String explicit, SourceLocator locator, String fallback) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (locator != null && !locator.path().isBlank()) {
            return locator.path();
        }
        return fallback == null ? "" : fallback;
    }
}
