package io.groundedaccess.modelclient;

import java.util.List;

/**
 * Vectors for a batch of texts, in request order, with the exact model that produced them, the instruction it prepended to every text and the
 * vector size. Together with the control plane's chunking settings these make up the index profile the vectors belong to (ADR-0007).
 */
public record Embeddings(
        String model,

        String revision,

        String prefix,

        int dimensions,

        List<float[]> vectors) {

    public String modelId() {
        return model + "@" + revision;
    }
}
