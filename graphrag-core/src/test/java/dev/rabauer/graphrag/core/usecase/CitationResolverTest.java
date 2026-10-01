package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.RetrievalStep.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Story 15.2: drop, renumber and citation-list rules for synthesized
 * Local Search answers.
 */
class CitationResolverTest {

    private static final Citation TU_A = new Citation("tu-a", "a.txt", "Passage A");
    private static final Citation TU_B = new Citation("tu-b", "b.txt", "Passage B");
    private static final Citation TU_C = new Citation("tu-c", "c.txt", "Passage C");

    /** 1 = entity, 2 = relationship, 3 = tu-b, 4 = tu-a, 5 = tu-c, 6 = entity. */
    private static final List<ContextItem> CONTEXT = List.of(
            new ContextItem(1, Kind.ENTITY, "Irene Adler (Person)", null),
            new ContextItem(2, Kind.RELATIONSHIP, "Holmes -[admired]-> Adler", null),
            new ContextItem(3, Kind.TEXT_UNIT, "Holmes admired her.", "tu-b"),
            new ContextItem(4, Kind.TEXT_UNIT, "Adler left London.", "tu-a"),
            new ContextItem(5, Kind.TEXT_UNIT, "The photograph.", "tu-c"),
            new ContextItem(6, Kind.ENTITY, "Sherlock Holmes (Person)", null));

    private static final Map<String, Citation> CITATIONS = Map.of("tu-a", TU_A, "tu-b", TU_B, "tu-c", TU_C);

    private static CitationResolver.Resolution resolve(String text) {
        return CitationResolver.resolve(text, CONTEXT, CITATIONS);
    }

    @Test
    void keepsTextUnitMarkersRenumberedAndDropsEntityMarkers() {
        CitationResolver.Resolution resolution = resolve("Holmes admired Adler [3][1].");

        assertEquals("Holmes admired Adler [1].", resolution.text());
        assertEquals(List.of(TU_B), resolution.citations());
    }

    @Test
    void dropsAnOutOfRangeMarkerWithItsLeadingSpace() {
        CitationResolver.Resolution resolution = resolve("Adler left London [9].");

        assertEquals("Adler left London.", resolution.text());
        assertEquals(List.of(), resolution.citations());
    }

    @Test
    void repeatedCitationsShareOneNumberAndOneEntry() {
        CitationResolver.Resolution resolution = resolve("A [4] then B [4] and C [5].");

        assertEquals("A [1] then B [1] and C [2].", resolution.text());
        assertEquals(List.of(TU_A, TU_C), resolution.citations());
    }

    @Test
    void groupedMarkersKeepOnlyTheirTextUnitNumbers() {
        CitationResolver.Resolution resolution = resolve("Both [5, 3] and [2, 4] and [1,6].");

        assertEquals("Both [1, 2] and [3] and.", resolution.text());
        assertEquals(List.of(TU_C, TU_B, TU_A), resolution.citations());
    }

    @Test
    void hyphenAndEnDashRangesAreExpandedThenFilteredAndRenumbered() {
        CitationResolver.Resolution hyphen = resolve("All three [4-6].");
        assertEquals("All three [1, 2].", hyphen.text());
        assertEquals(List.of(TU_A, TU_C), hyphen.citations());

        CitationResolver.Resolution enDash = resolve("All [2–4] and [ 5 – 5 ].");
        assertEquals("All [1, 2] and [3].", enDash.text());
        assertEquals(List.of(TU_B, TU_A, TU_C), enDash.citations());
    }

    @Test
    void semicolonSeparatorsFollowTheSameRules() {
        CitationResolver.Resolution resolution = resolve("Both [4; 5] but not [1; 6], then [3;4-5].");

        assertEquals("Both [1, 2] but not, then [3, 1, 2].", resolution.text());
        assertEquals(List.of(TU_A, TU_C, TU_B), resolution.citations());
    }

    @Test
    void aRangeOfOnlyNonTextUnitItemsIsRemoved() {
        CitationResolver.Resolution resolution = resolve("Background [1-2].");

        assertEquals("Background.", resolution.text());
        assertEquals(List.of(), resolution.citations());
    }

    @Test
    void relationshipAndEntityMarkersAreRemoved() {
        CitationResolver.Resolution resolution = resolve("Holmes admired Adler [2] [6].");

        assertEquals("Holmes admired Adler.", resolution.text());
        assertEquals(List.of(), resolution.citations());
    }

    @Test
    void numberingFollowsFirstAppearanceNotContextOrder() {
        CitationResolver.Resolution resolution = resolve("[5] first, then [3], then [4].");

        assertEquals("[1] first, then [2], then [3].", resolution.text());
        assertEquals(List.of(TU_C, TU_B, TU_A), resolution.citations());
    }

    @Test
    void textWithoutMarkersIsUnchanged() {
        CitationResolver.Resolution resolution = resolve("No citations here.");

        assertEquals("No citations here.", resolution.text());
        assertEquals(List.of(), resolution.citations());
    }

    @Test
    void blankTextResolvesToEmpty() {
        assertEquals("", resolve("  ").text());
        assertEquals("", resolve(null).text());
    }
}
