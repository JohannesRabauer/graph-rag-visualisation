package dev.rabauer.graphrag.core.usecase;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityTypesTest {

    @Test
    void listsExactlyTheEightTypesInOrder() {
        assertEquals(List.of("Person", "Organization", "Product", "Technology", "Version", "Event", "Location",
                "Concept"), EntityTypes.ALL);
    }

    @Test
    void normalizesCaseInsensitivelyAndMapsEverythingElseToConcept() {
        assertEquals("Concept", EntityTypes.normalize("Place"));
        assertEquals("Person", EntityTypes.normalize("person"));
        assertEquals("Organization", EntityTypes.normalize("  ORGANIZATION "));
        assertEquals("Concept", EntityTypes.normalize(""));
        assertEquals("Concept", EntityTypes.normalize("   "));
        assertEquals("Concept", EntityTypes.normalize(null));
    }
}
