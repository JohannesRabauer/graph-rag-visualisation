package dev.rabauer.graphrag.core.domain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttributesAndLocatorsTest {

    private static final SourceLocator LOCATOR = SourceLocator.of("src/A.java", 3, 9);

    @Test
    void theExistingConstructorsCreateElementsWithoutAttributesAndLocator() {
        assertEquals(Map.of(), new Entity("A", "Class").attributes());
        assertNull(new Entity("A", "Class", "d", List.of()).locator());
        assertEquals(Map.of(), new Relationship("A", "Class", "CALLS", "B", "Class").attributes());
        assertNull(new Relationship("A", "Class", "CALLS", "B", "Class", "", List.of(), 2).locator());
        assertNull(new TextUnit("t", "c", "A.java", 0, "x").locator());
        assertNull(new Citation("t", "A.java", "x").locator());
        assertEquals(Map.of(), new Community("c-1", "t", "s").attributes());
        assertNull(new RetrievalStep(RetrievalStep.Kind.ENTITY, "a::class", "A").locator());
    }

    @Test
    void attributesAreSortedUnmodifiableAndDropNullsAndBlankKeys() {
        Map<String, String> raw = new HashMap<>();
        raw.put("visibility", "public");
        raw.put(" kind ", "class");
        raw.put("", "dropped");
        raw.put("nullValue", null);
        raw.put(null, "dropped");

        Entity entity = new Entity("A", "Class", "", List.of(), raw, LOCATOR);

        assertEquals(List.of("kind", "visibility"), List.copyOf(entity.attributes().keySet()));
        assertEquals(Optional.of("class"), entity.attribute("kind"));
        assertEquals(Optional.empty(), entity.attribute("missing"));
        assertThrows(UnsupportedOperationException.class, () -> entity.attributes().put("x", "y"));
    }

    @Test
    void attributesTakePartInEquality() {
        Entity plain = new Entity("A", "Class");
        assertEquals(plain, new Entity("A", "Class", "", List.of(), Map.of(), null));
        assertEquals(plain.withAttributes(Map.of("kind", "class")),
                new Entity("A", "Class", "", List.of(), Map.of("kind", "class"), null));
        assertEquals(LOCATOR, plain.withLocator(LOCATOR).locator());
    }

    @Test
    void withHelpersKeepAttributesAndLocator() {
        Entity entity = new Entity("A", "Class", "d", List.of("t1"), Map.of("kind", "class"), LOCATOR);
        Entity renamed = entity.with("B", "Interface", "e", List.of("t2"));
        assertEquals(Map.of("kind", "class"), renamed.attributes());
        assertEquals(LOCATOR, renamed.locator());
        assertEquals("b::interface", renamed.normalizedIdentity());

        Relationship relationship = new Relationship("A", "Class", "CALLS", "B", "Class", "", List.of(), 3,
                Map.of("callCount", "3"), LOCATOR);
        Relationship rekeyed = relationship.with("C", "Class", "B", "Class", "", List.of(), 3);
        assertEquals("CALLS", rekeyed.type());
        assertEquals(Map.of("callCount", "3"), rekeyed.attributes());
        assertEquals(LOCATOR, rekeyed.locator());
        assertEquals("c::class", rekeyed.sourceIdentity());
        assertEquals("b::class", rekeyed.targetIdentity());
    }

    @Test
    void entityTypesAndRelationshipTypesAreFreeForm() {
        assertEquals("Method", new Entity("a.B#c()", "Method").type());
        assertEquals("IMPLEMENTS", new Relationship("A", "Class", "IMPLEMENTS", "I", "Interface").type());
    }

    @Test
    void unionKeepsTheFirstValueOnConflicts() {
        assertEquals(Map.of("a", "1", "b", "2", "c", "3"),
                Attributes.union(Map.of("a", "1", "b", "2"), Map.of("b", "x", "c", "3")));
        assertEquals(Map.of("a", "1"), Attributes.union(null, Map.of("a", "1")));
        assertEquals(Map.of(), Attributes.union(null, null));
    }
}
