package io.groundedaccess.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A stored execution record as its owner may read it.
 */
public record QueryExecution(
        UUID id,

        String traceId,

        String planHash,

        String policyVersion,

        @Nullable String embeddingModel,

        String status,

        List<String> degradedReasons,

        int resultCount,

        long totalMs,

        Instant createdAt,

        @Nullable String rerankerModel) {
}
