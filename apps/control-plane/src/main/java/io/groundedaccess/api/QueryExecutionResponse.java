package io.groundedaccess.api;

import io.groundedaccess.audit.QueryExecution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * How one of the caller's own retrieval requests was executed. It contains versions, counts and timings, never the query or its results.
 */
public record QueryExecutionResponse(
        UUID id,

        String traceId,

        String planHash,

        String policyVersion,

        @Nullable String embeddingModel,

        @Nullable String rerankerModel,

        String status,

        List<String> degraded,

        int resultCount,

        long totalMs,

        Instant createdAt) {

    static QueryExecutionResponse from(QueryExecution execution) {
        return new QueryExecutionResponse(execution.id(), execution.traceId(), execution.planHash(), execution.policyVersion(), execution.embeddingModel(), execution.rerankerModel(),
                execution.status(), execution.degradedReasons(), execution.resultCount(), execution.totalMs(), execution.createdAt());
    }
}
