package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.port.EmbeddingPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of an {@link EmbeddingPort}: non-empty, finite vectors of one
 * dimension for any text (including blank text), the same vector for the same
 * text when the model is deterministic, {@link EmbeddingPort#embedAll(List)}
 * giving the vectors of {@code embed} one per text in order, and — for a port that reports
 * {@link EmbeddingPort#isSemantic()} — nearby meanings closer than unrelated
 * ones. Extend it and return the port from {@link #port()}.
 */
public abstract class EmbeddingPortContract {

    /** The port under test. */
    protected abstract EmbeddingPort port();

    /** Whether the same text always gives the same vector; the default is {@code true}. */
    protected boolean deterministic() {
        return true;
    }

    /**
     * For a semantic port: an anchor text, a text close in meaning, and an
     * unrelated one. Override to fit your model's language or domain.
     */
    protected List<String> semanticProbe() {
        return List.of("Who calls the method that saves an order?",
                "Which code invokes the order repository save function?",
                "The weather in Lisbon is sunny and warm today.");
    }

    @Test
    void everyTextGetsAFiniteVectorOfOneDimension() {
        EmbeddingPort port = port();
        int dimensions = -1;
        for (String text : List.of("OrderService#placeOrder", "A longer sentence about orders and payments.", "", " ")) {
            float[] vector = port.embed(text);
            assertNotNull(vector, "vector for '" + text + "'");
            assertTrue(vector.length > 0, "empty vector for '" + text + "'");
            for (float value : vector) {
                assertTrue(Float.isFinite(value), "non-finite value for '" + text + "'");
            }
            if (dimensions < 0) {
                dimensions = vector.length;
            }
            assertEquals(dimensions, vector.length, "dimension for '" + text + "'");
        }
    }

    @Test
    void embedAllReturnsOneVectorPerTextInOrder() {
        EmbeddingPort port = port();
        List<String> texts = List.of("OrderService#placeOrder", "A longer sentence about orders and payments.", "");

        List<float[]> vectors = port.embedAll(texts);

        assertNotNull(vectors, "embedAll returned null");
        assertEquals(texts.size(), vectors.size(), "one vector per text");
        for (int i = 0; i < texts.size(); i++) {
            assertNotNull(vectors.get(i), "vector for '" + texts.get(i) + "'");
            assertEquals(port.embed(texts.get(i)).length, vectors.get(i).length, "dimension for '" + texts.get(i) + "'");
            if (deterministic()) {
                assertArrayEquals(port.embed(texts.get(i)), vectors.get(i), 1e-5f, "vector for '" + texts.get(i) + "'");
            }
        }
        assertTrue(port.embedAll(List.of()).isEmpty(), "no texts, no vectors");
    }

    @Test
    void theSameTextGetsTheSameVectorWhenDeterministic() {
        if (!deterministic()) {
            return;
        }
        assertArrayEquals(port().embed("placeOrder saves the order"), port().embed("placeOrder saves the order"),
                1e-6f);
    }

    @Test
    void nearbyMeaningsAreCloserThanUnrelatedOnesForASemanticModel() {
        EmbeddingPort port = port();
        if (!port.isSemantic()) {
            return;
        }
        List<String> probe = semanticProbe();
        float[] anchor = port.embed(probe.get(0));
        double near = cosine(anchor, port.embed(probe.get(1)));
        double far = cosine(anchor, port.embed(probe.get(2)));

        assertTrue(near > far, () -> "expected similarity " + near + " > " + far);
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }
}
