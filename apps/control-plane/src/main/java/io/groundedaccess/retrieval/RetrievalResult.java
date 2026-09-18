package io.groundedaccess.retrieval;

import java.util.List;

/**
 * Ranked authorized candidates together with the configuration and policy that produced them.
 */
public record RetrievalResult(
        RetrievalStrategy strategy,

        String policyVersion,

        List<RetrievedChunk> chunks) {
}
