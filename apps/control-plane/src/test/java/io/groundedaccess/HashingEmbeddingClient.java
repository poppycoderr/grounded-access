package io.groundedaccess;

import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.Embeddings;
import io.groundedaccess.modelclient.InputType;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for the model service: hashes lowercase words into 384 buckets and normalizes, so texts sharing words are close.
 */
public class HashingEmbeddingClient implements EmbeddingClient {

    @Override
    public String modelName() {
        return "hashing";
    }

    @Override
    public Embeddings embed(List<String> texts, InputType inputType) {
        return new Embeddings("hashing", "test", "", 384, texts.stream().map(HashingEmbeddingClient::vector).toList());
    }

    /**
     * One token per whitespace-separated word, plus two special tokens, the way a word-piece tokenizer counts plain English at best.
     */
    @Override
    public List<Integer> fittingChars(List<String> texts, int budget) {
        return texts.stream().map(text -> {
            Matcher words = WORD.matcher(text);
            int count = 2;
            int end = 0;
            while (words.find()) {
                if (++count > budget) {
                    return end;
                }
                end = words.end();
            }
            return text.length();
        }).toList();
    }

    private static final Pattern WORD = Pattern.compile("\\S+");

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
