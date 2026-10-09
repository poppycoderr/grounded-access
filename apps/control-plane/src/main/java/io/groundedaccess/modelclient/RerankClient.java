package io.groundedaccess.modelclient;

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
     * Name of the reranker, without a revision; part of the retrieval plan.
     */
    String modelName();
}
