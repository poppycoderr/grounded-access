package io.groundedaccess.api;

import io.groundedaccess.retrieval.RetrievalResult;
import io.groundedaccess.retrieval.RetrievalStrategy;

import java.util.List;

/**
 * Ranked results plus the strategy and policy version, so an evaluation run can record exactly what produced them.
 */
public record SearchResponse(
        RetrievalStrategy strategy,

        String policyVersion,

        List<SearchResultResponse> results) {

    static SearchResponse from(RetrievalResult result, boolean debug) {
        return new SearchResponse(result.strategy(), result.policyVersion(), result.chunks().stream().map(c -> SearchResultResponse.from(c, debug)).toList());
    }
}
