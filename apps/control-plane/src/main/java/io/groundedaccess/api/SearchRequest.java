package io.groundedaccess.api;

import io.groundedaccess.retrieval.RetrievalStrategy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

/**
 * A retrieval request. {@code k} defaults to 10.
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
        Integer k) {

    int limit() {
        return k == null ? 10 : k;
    }
}
