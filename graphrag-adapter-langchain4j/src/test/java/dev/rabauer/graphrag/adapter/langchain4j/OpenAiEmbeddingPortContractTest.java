package dev.rabauer.graphrag.adapter.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.testkit.EmbeddingPortContract;

import java.util.List;
import java.util.Locale;

/**
 * The OpenAI embedding adapter meets the testkit's embedding contract. A fake, deterministic
 * LangChain4j {@link EmbeddingModel} stands in for the OpenAI one: no network, no API key.
 */
class OpenAiEmbeddingPortContractTest extends EmbeddingPortContract {

    private static final int DIMENSIONS = 64;

    @Override
    protected EmbeddingPort port() {
        return OpenAiEmbeddingPort.withModel(new FakeEmbeddingModel());
    }

    /** Hashed character trigrams, L2-normalised. */
    private static final class FakeEmbeddingModel implements EmbeddingModel {

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream().map(segment -> Embedding.from(vector(segment.text()))).toList());
        }

        private static float[] vector(String text) {
            float[] vector = new float[DIMENSIONS];
            String normalized = text.toLowerCase(Locale.ROOT).strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("text cannot be null or blank");
            }
            if (normalized.length() < 3) {
                vector[0] = 1f;
                return vector;
            }
            for (int i = 0; i + 3 <= normalized.length(); i++) {
                vector[Math.floorMod(normalized.substring(i, i + 3).hashCode(), DIMENSIONS)] += 1f;
            }
            double norm = 0;
            for (float value : vector) {
                norm += value * value;
            }
            float scale = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < DIMENSIONS; i++) {
                vector[i] *= scale;
            }
            return vector;
        }
    }
}
