package com.graphraglens.adapter.langchain4j;

import com.graphraglens.core.port.EmbeddingPort;

import java.util.Locale;

public class LangChain4jEmbeddingPort implements EmbeddingPort {

    static final int DIMENSIONS = 64;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }

        for (String token : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (token.isBlank()) {
                continue;
            }
            vector[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1.0f;
        }
        normalize(vector);
        return vector;
    }

    private void normalize(float[] vector) {
        double sumOfSquares = 0.0;
        for (float value : vector) {
            sumOfSquares += value * value;
        }
        if (sumOfSquares == 0.0) {
            return;
        }

        float norm = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }
}
