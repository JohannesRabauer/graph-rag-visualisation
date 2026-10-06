package dev.rabauer.graphrag.core.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceLocatorTest {

    @Test
    void formatsPathLineAndRange() {
        assertEquals("src/A.java:12-20", SourceLocator.of("src/A.java", 12, 20).format());
        assertEquals("src/A.java:12", SourceLocator.of("src/A.java", 12).format());
        assertEquals("src/A.java", SourceLocator.of("src/A.java").format());
        assertEquals("src/A.java:12-20", SourceLocator.of("src/A.java", 12, 20).toString());
    }

    @Test
    void normalisesNullPathNegativeLinesAndAnEndBeforeTheStart() {
        SourceLocator locator = new SourceLocator(null, -3, 7);
        assertEquals("", locator.path());
        assertEquals(0, locator.startLine());
        assertEquals(0, locator.endLine());
        assertFalse(locator.hasLines());

        SourceLocator reversed = SourceLocator.of("A.java", 20, 10);
        assertEquals(20, reversed.endLine());
        assertTrue(reversed.hasLines());
    }

    @Test
    void parsesWhatItFormats() {
        for (SourceLocator locator : new SourceLocator[] {
                SourceLocator.of("src/main/java/com/acme/OrderService.java", 42, 57),
                SourceLocator.of("src/main/java/com/acme/OrderService.java", 42),
                SourceLocator.of("C:/repo/Order.java"),
                SourceLocator.of("README.md")}) {
            assertEquals(Optional.of(locator), SourceLocator.parse(locator.format()));
        }
        assertEquals(Optional.empty(), SourceLocator.parse(" "));
        assertEquals(Optional.empty(), SourceLocator.parse(null));
    }

    @Test
    void keepsAWindowsDriveLetterInThePath() {
        assertEquals(SourceLocator.of("C:/repo/Order.java", 3, 4), SourceLocator.parse("C:/repo/Order.java:3-4").orElseThrow());
    }
}
