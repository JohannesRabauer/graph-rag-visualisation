package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextUnitSplitterTest {

    @Test
    void shortDocumentBecomesOneUnitWithTheWholeText() {
        String text = "Sherlock Holmes met Dr. Watson. ".repeat(25); // 800 chars
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("short.txt", text)));

        List<TextUnit> units = TextUnitSplitter.split(corpus);

        assertEquals(1, units.size());
        TextUnit unit = units.getFirst();
        assertEquals("c1::doc-0::tu-0", unit.id());
        assertEquals("c1", unit.corpusId());
        assertEquals("short.txt", unit.documentName());
        assertEquals(0, unit.ordinal());
        assertEquals(text, unit.text());
    }

    @Test
    void longDocumentIsSplitIntoOverlappingUnitsThatCoverEveryCharacter() {
        StringBuilder builder = new StringBuilder();
        int paragraph = 0;
        while (builder.length() < 20_000) {
            builder.append("Paragraph ").append(paragraph++).append(" talks about Java. ")
                    .append("It mentions James Gosling and the Green Project in some detail. ".repeat(5))
                    .append("\n\n");
        }
        String text = builder.toString();
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("long.txt", text)));

        List<TextUnit> units = TextUnitSplitter.split(corpus);

        assertTrue(units.size() >= 4, "expected at least 4 units but got " + units.size());
        assertContiguousOverlappingCoverage(text, units);
        for (int i = 0; i < units.size() - 1; i++) {
            assertTrue(units.get(i).text().endsWith("\n\n"), "unit " + i + " should be cut on a paragraph break");
        }
    }

    @Test
    void crlfParagraphBreaksAreUsedAsCuts() {
        StringBuilder builder = new StringBuilder();
        int paragraph = 0;
        while (builder.length() < 20_000) {
            builder.append("Paragraph ").append(paragraph++).append(" talks about Java. ")
                    .append("It mentions James Gosling and the Green Project in some detail. ".repeat(5))
                    .append("\r\n\r\n");
        }
        String text = builder.toString();

        List<TextUnit> units = TextUnitSplitter.split(new Corpus("c1", List.of(new UploadedDocument("crlf.txt", text))));

        assertContiguousOverlappingCoverage(text, units);
        for (int i = 0; i < units.size() - 1; i++) {
            assertTrue(units.get(i).text().endsWith("\r\n\r\n"), "unit " + i + " should be cut on a CRLF paragraph break");
        }
    }

    /**
     * Asserts every unit is a slice of {@code text} within the size limit,
     * overlaps its predecessor with no gap, and the units together cover the
     * whole text. Assumes {@code text} has no repeated unit-length slices.
     */
    private static void assertContiguousOverlappingCoverage(String text, List<TextUnit> units) {
        int coveredUpTo = 0;
        int searchFrom = 0;
        for (int i = 0; i < units.size(); i++) {
            TextUnit unit = units.get(i);
            assertEquals(i, unit.ordinal());
            assertEquals("c1::doc-0::tu-" + i, unit.id());
            assertTrue(unit.text().length() <= TextUnitSplitter.TARGET_CHARS);
            int start = text.indexOf(unit.text(), searchFrom);
            assertTrue(start >= 0, "unit text must be a slice of the document");
            assertTrue(start <= coveredUpTo, "no gap may appear before unit " + i);
            if (i > 0) {
                assertTrue(start < coveredUpTo, "unit " + i + " must overlap its predecessor");
                String previous = units.get(i - 1).text();
                assertTrue(previous.endsWith(text.substring(start, coveredUpTo)));
            }
            coveredUpTo = start + unit.text().length();
            searchFrom = start + 1;
        }
        assertEquals(text.length(), coveredUpTo);
    }

    @Test
    void textWithoutParagraphBreaksIsCutAtASentenceEnd() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; builder.length() < 20_000; i++) {
            builder.append("Note ").append(i).append(" by Ada Lovelace covers the Analytical Engine. ");
        }
        String text = builder.toString();
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("sentences.txt", text)));

        List<TextUnit> units = TextUnitSplitter.split(corpus);

        assertTrue(units.size() >= 2);
        assertContiguousOverlappingCoverage(text, units);
        for (int i = 0; i < units.size() - 1; i++) {
            assertTrue(units.get(i).text().endsWith("Engine. "), "unit " + i + " should be cut at a sentence end");
        }
    }

    @Test
    void textWithoutSentenceEndsIsCutOnWhitespaceAndUnitsStartOnAWord() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; builder.length() < 20_000; i++) {
            builder.append("alpha").append(i).append(" beta gamma ");
        }
        String text = builder.toString();
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("words.txt", text)));

        List<TextUnit> units = TextUnitSplitter.split(corpus);

        assertTrue(units.size() >= 2);
        assertContiguousOverlappingCoverage(text, units);
        for (int i = 0; i < units.size(); i++) {
            String unitText = units.get(i).text();
            assertTrue(Character.isLetter(unitText.charAt(0)), "unit " + i + " should start on a word");
            if (i < units.size() - 1) {
                assertTrue(unitText.endsWith(" "), "unit " + i + " should be cut on whitespace");
            }
        }
    }

    @Test
    void textWithoutAnyBoundaryIsHardCutAndTerminates() {
        String text = "x".repeat(15_000);
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("blob.txt", text)));

        List<TextUnit> units = TextUnitSplitter.split(corpus);

        assertEquals(TextUnitSplitter.TARGET_CHARS, units.getFirst().text().length());
        int total = 0;
        for (TextUnit unit : units) {
            assertTrue(unit.text().length() <= TextUnitSplitter.TARGET_CHARS);
            total += unit.text().length();
        }
        assertEquals(text.length() + (units.size() - 1) * TextUnitSplitter.OVERLAP_CHARS, total);
    }

    @Test
    void blankDocumentsContributeNoUnitsAndKeepTheirDocumentIndex() {
        Corpus corpus = new Corpus("c1", List.of(
                new UploadedDocument("a.txt", "Alpha text."),
                new UploadedDocument("empty.txt", "   \n "),
                new UploadedDocument("c.txt", "Gamma text.")));

        List<TextUnit> units = TextUnitSplitter.split(corpus);

        assertEquals(2, units.size());
        assertEquals("c1::doc-0::tu-0", units.get(0).id());
        assertEquals("c1::doc-2::tu-0", units.get(1).id());
        assertEquals("c.txt", units.get(1).documentName());
        assertEquals(0, units.get(1).ordinal());
    }
}
