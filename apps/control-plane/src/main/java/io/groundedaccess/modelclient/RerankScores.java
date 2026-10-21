package io.groundedaccess.modelclient;

import java.util.List;

/**
 * Cross-encoder scores in request order, with the exact model that produced them. {@code queryTruncated} says the model service cut the query
 * so that it fit with the longest passage.
 */
public record RerankScores(
        String model,

        String revision,

        List<Double> scores,

        boolean queryTruncated,

        long queueMs,

        long computeMs) {

    /**
     * Without the model service's timing, as a stand-in reports it.
     */
    public RerankScores(String model, String revision, List<Double> scores, boolean queryTruncated) {
        this(model, revision, scores, queryTruncated, -1, -1);
    }

    public String modelId() {
        return model + "@" + revision;
    }
}
