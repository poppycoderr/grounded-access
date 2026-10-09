package io.groundedaccess.audit;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Everything recorded about one retrieval request. The fields are the complete allow-list: there is no free-form attribute, so query text,
 * chunk text and document titles cannot reach the audit tables by accident. Documents are named by key and version.
 */
public record SearchRecord(
        String tenantId,

        String principalId,

        String traceId,

        String strategy,

        int k,

        String planHash,

        String planJson,

        String policyVersion,

        @Nullable String embeddingModel,

        @Nullable String rerankerModel,

        List<String> degradedReasons,

        List<String> documents,

        int resultCount,

        @Nullable Instant asOf,

        @Nullable String region,

        long totalMs,

        long sparseMs,

        long denseMs,

        long rerankMs) {
}
