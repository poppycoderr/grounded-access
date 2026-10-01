package io.groundedaccess.api;

import io.groundedaccess.retrieval.RetrievalPlan;
import io.groundedaccess.retrieval.RetrievalResult;
import io.groundedaccess.retrieval.RetrievalStrategy;
import io.groundedaccess.retrieval.Scope;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Ranked results plus the plan hash and policy version, so an evaluation run can record exactly what produced them. The plan itself is a debug
 * field. A non-empty {@code degraded} means the results did not come from the full plan.
 */
public record SearchResponse(
        RetrievalStrategy strategy,

        String policyVersion,

        String planHash,

        @Nullable RetrievalPlan plan,

        List<String> degraded,

        Scope scope,

        List<SearchResultResponse> results) {

    static SearchResponse from(RetrievalResult result, boolean debug) {
        return new SearchResponse(result.plan().strategy(), result.policyVersion(), result.plan().hash(), debug ? result.plan() : null, result.degraded(), result.scope(),
                result.chunks().stream().map(c -> SearchResultResponse.from(c, debug)).toList());
    }
}
