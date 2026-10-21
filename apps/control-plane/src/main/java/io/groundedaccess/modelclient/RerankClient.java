package io.groundedaccess.modelclient;

import java.time.Duration;
import java.util.List;

/**
 * Scores authorized passages against a query with a cross-encoder. Implementations must never receive tenant or principal data.
 */
public interface RerankClient {

    /**
     * One score per passage, in the order given; higher is more relevant. Scores are only comparable within one call.
     */
    RerankScores score(String query, List<String> passages);

    /**
     * As {@link #score(String, List)}, waiting at most {@code wait} for a reranking slot before the call is sent (ADR-0010).
     */
    default RerankScores score(String query, List<String> passages, Duration wait) {
        return score(query, passages);
    }

    /**
     * How long the call itself may take once it is sent.
     */
    default Duration timeout() {
        return Duration.ZERO;
    }

    /**
     * Name of the reranker, without a revision; part of the retrieval plan.
     */
    String modelName();
}
