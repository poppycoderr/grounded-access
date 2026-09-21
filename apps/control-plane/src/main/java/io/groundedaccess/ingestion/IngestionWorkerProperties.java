package io.groundedaccess.ingestion;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker settings. A job is retried at most {@code maxAttempts} times in total, waiting {@code retryBackoff} doubled after each failed attempt.
 * {@code lease} must be longer than the slowest single document takes to embed and write, because the lease is renewed after every document.
 */
@ConfigurationProperties("ga.ingestion.worker")
public record IngestionWorkerProperties(
        boolean enabled,

        Duration pollInterval,

        Duration lease,

        int maxAttempts,

        Duration retryBackoff) {
}
