package io.groundedaccess.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * Ranked authorized candidates together with the plan and policy that produced them. {@code degraded} names the parts of the plan that could
 * not run; a result with any entry is not what the plan describes and must not be used as an evaluation result. {@code scope} is the moment and region the
 * candidates were restricted to.
 */
public record RetrievalResult(
        RetrievalPlan plan,

        String policyVersion,

        List<RetrievedChunk> chunks,

        List<String> degraded,

        Scope scope,

        UUID executionId,

        String traceId) {
}
