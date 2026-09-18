package io.groundedaccess;

import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic stand-in for the model service: hashes lowercase words into 384 buckets and normalizes, so texts sharing words are close.
 */
public class HashingEmbeddingClient implements EmbeddingClient {

    @Override
    public Embeddings embed(List<String> texts, InputType inputType) {
        return new Embeddings("hashing", "test", texts.stream().map(HashingEmbeddingClient::vector).toList());
    }

    private static float[] vector(String text) {
        float[] vector = new float[384];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty()) {
                vector[Math.floorMod(word.hashCode(), vector.length)] += 1;
            }
        }
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = norm == 0 ? 0 : (float) (vector[i] / Math.sqrt(norm));
        }
        return vector;
    }
}
