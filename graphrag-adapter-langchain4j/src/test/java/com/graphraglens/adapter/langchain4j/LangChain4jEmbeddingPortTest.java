package com.graphraglens.adapter.langchain4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jEmbeddingPortTest {

    @Test
    void theOfflineStubIsNotASemanticEmbeddingModel() {
        assertFalse(new LangChain4jEmbeddingPort().isSemantic());
    }

    @Test
    void returnsTheSameEmbeddingForTheSameTextAcrossCallsAndInstances() {
        LangChain4jEmbeddingPort first = new LangChain4jEmbeddingPort();
        LangChain4jEmbeddingPort second = new LangChain4jEmbeddingPort();

        float[] firstVector = first.embed("Sherlock Holmes investigates clues");
        float[] secondVector = first.embed("Sherlock Holmes investigates clues");
        float[] thirdVector = second.embed("Sherlock Holmes investigates clues");

        assertArrayEquals(firstVector, secondVector);
        assertArrayEquals(firstVector, thirdVector);
    }

    @Test
    void returnsDifferentEmbeddingsForDifferentTexts() {
        LangChain4jEmbeddingPort port = new LangChain4jEmbeddingPort();

        float[] firstVector = port.embed("Sherlock Holmes investigates clues");
        float[] secondVector = port.embed("Professor Moriarty plots crimes");

        assertFalse(java.util.Arrays.equals(firstVector, secondVector));
    }

    @Test
    void returnsAllZeroVectorsForBlankOrNullText() {
        LangChain4jEmbeddingPort port = new LangChain4jEmbeddingPort();

        assertTrue(allZero(port.embed("")));
        assertTrue(allZero(port.embed("   ")));
        assertTrue(allZero(port.embed(null)));
    }

    @Test
    void normalizesEveryNonBlankEmbeddingToUnitLength() {
        LangChain4jEmbeddingPort port = new LangChain4jEmbeddingPort();

        float[] vector = port.embed("Sherlock Holmes investigates clues");

        assertNotEquals(0.0, norm(vector), 1.0e-6);
        assertTrue(Math.abs(norm(vector) - 1.0) < 1.0e-6);
    }

    private static boolean allZero(float[] vector) {
        for (float value : vector) {
            if (value != 0.0f) {
                return false;
            }
        }
        return true;
    }

    private static double norm(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }
}
