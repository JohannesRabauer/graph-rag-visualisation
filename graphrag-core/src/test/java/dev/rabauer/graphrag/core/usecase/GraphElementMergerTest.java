package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
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

    @Test
    void descriptionMergeAppendsFittingSentencesAndStopsBeforeTheCap() {
        String existing = "B".repeat(960) + ".";
        String merged = GraphElementMerger.mergeDescriptions(existing, "Fits here. " + "C".repeat(50) + ". Short.");

        assertEquals(existing + " Fits here.", merged);
    }

    @Test
    void descriptionMergeSkipsSentencesThatDifferOnlyInCaseOrWhitespace() {
        assertEquals("A mathematician. She wrote notes.",
                GraphElementMerger.mergeDescriptions("A mathematician.", "  a MATHEMATICIAN.   She wrote notes."));
    }

    @Test
    void mergingTheSameSourceUnitTwiceKeepsOneIdAndWeightOne() {
        Relationship sighting = new Relationship("Ada", "Person", "wrote_about", "Engine", "Concept",
                "Ada wrote about it.", List.of("u0"), 1);

        Relationship merged = GraphElementMerger.merge(sighting, sighting);

        assertEquals(List.of("u0"), merged.sourceTextUnitIds());
        assertEquals(1, merged.weight());
    }
}
