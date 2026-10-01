package io.groundedaccess.api;

import io.groundedaccess.retrieval.RetrievalStrategy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A retrieval request. {@code k} defaults to 10. {@code asOf} and {@code region} are scope: they default to now and to the principal's region,
 * and they change which documents apply, never which documents the principal may read.
 */
public record SearchRequest(
        @NotBlank
        @Size(max = 2000)
        String query,

        @NotNull
        RetrievalStrategy strategy,

        @Nullable
        @Min(1)
        @Max(50)
        Integer k,

        @Nullable Instant asOf,

        @Nullable
        @Size(min = 1, max = 50)
        String region) {

    int limit() {
        return k == null ? 10 : k;
    }
}
