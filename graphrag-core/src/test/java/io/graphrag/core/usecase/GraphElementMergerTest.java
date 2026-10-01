package io.graphrag.core.usecase;

import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.Relationship;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphElementMergerTest {

    @Test
    void mergesDistinctEntitySentencesAndSourceIdsInOrder() {
        Entity first = new Entity("Ada", "Person", "A mathematician.", List.of("u0"));
        Entity second = new Entity("Ada", "Person", "A mathematician. She wrote notes.", List.of("u1"));

        Entity merged = GraphElementMerger.merge(first, second);

        assertEquals("A mathematician. She wrote notes.", merged.description());
        assertEquals(List.of("u0", "u1"), merged.sourceTextUnitIds());
    }

    @Test
    void relationshipWeightIsSourceUnitCount() {
        Relationship merged = GraphElementMerger.merge(
                new Relationship("Ada", "Person", "wrote_about", "Engine", "Concept", "Ada wrote about it.",
                        List.of("u0"), 1),
                new Relationship("Ada", "Person", "wrote_about", "Engine", "Concept", "Ada wrote about it.",
                        List.of("u1", "u2"), 2));

        assertEquals(List.of("u0", "u1", "u2"), merged.sourceTextUnitIds());
        assertEquals(3, merged.weight());
    }

    @Test
    void descriptionMergeKeepsEarliestSentencesAndCapsAtOneThousandCharacters() {
        String longSentence = "A".repeat(1_200);

        String merged = GraphElementMerger.mergeDescriptions("", longSentence);

        assertEquals(1_000, merged.length());
        assertTrue(GraphElementMerger.mergeDescriptions("First sentence.", "Second sentence.".repeat(100))
                .length() <= 1_000);
    }
}
