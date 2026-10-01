package io.groundedaccess.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side retrieval settings. {@code candidates} is how many rows each channel contributes before fusion and deduplication; {@code rrfK} is
 * the constant of reciprocal rank fusion. Clients choose only the strategy and {@code k}.
 */
@ConfigurationProperties("ga.retrieval")
public record RetrievalProperties(
        int candidates,

        int rrfK) {
}
