package io.groundedaccess.api;

import io.groundedaccess.retrieval.RetrievalStrategy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A question to answer. {@code strategy} overrides the retrieval strategy the server would use; {@code asOf} and {@code region} are scope, as
 * on a search.
 */
public record QueryRequest(
        @NotBlank
        @Size(max = 2000)
        String query,

        @Nullable RetrievalStrategy strategy,

        @Nullable Instant asOf,

        @Nullable
        @Size(min = 1, max = 50)
        String region) {
}
