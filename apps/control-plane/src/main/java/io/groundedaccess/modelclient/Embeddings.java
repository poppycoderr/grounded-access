package io.groundedaccess.modelclient;

import java.util.List;

/**
 * Vectors for a batch of texts, in request order, with the exact model that produced them.
 */
public record Embeddings(
        String model,

        String revision,

        List<float[]> vectors) {

    public String modelId() {
        return model + "@" + revision;
    }
}
