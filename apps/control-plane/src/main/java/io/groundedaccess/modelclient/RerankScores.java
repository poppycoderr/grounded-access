package io.groundedaccess.modelclient;

import java.util.List;

/**
 * Cross-encoder scores in request order, with the exact model that produced them.
 */
public record RerankScores(
        String model,

        String revision,

        List<Double> scores) {

    public String modelId() {
        return model + "@" + revision;
    }
}
