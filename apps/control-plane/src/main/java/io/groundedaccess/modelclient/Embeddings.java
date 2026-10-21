package io.groundedaccess.modelclient;

import java.util.List;

/**
 * Vectors for a batch of texts, in request order, with the exact model that produced them, the instruction it prepended to every text and the
 * vector size, and how long the model service let the call wait and compute ({@code -1} when it did not say). Together with the control plane's chunking settings these make up the index profile the vectors belong to (ADR-0007).
 */
public record Embeddings(
        String model,

        String revision,

        String prefix,

        int dimensions,

        List<float[]> vectors,

        long queueMs,

        long computeMs) {

    /**
     * Without the model service's timing, as a stand-in reports it.
     */
    public Embeddings(String model, String revision, String prefix, int dimensions, List<float[]> vectors) {
        this(model, revision, prefix, dimensions, vectors, -1, -1);
    }

    public String modelId() {
        return model + "@" + revision;
    }
}
