package io.groundedaccess.ingestion;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker settings. A job is retried at most {@code maxAttempts} times in total, waiting {@code retryBackoff} doubled after each failed attempt.
 * {@code lease} is renewed every third of its length while a job runs; a worker that stops renewing loses the job once it expires.
 */
@ConfigurationProperties("ga.ingestion.worker")
public record IngestionWorkerProperties(
        boolean enabled,

        Duration pollInterval,

        Duration lease,

        int maxAttempts,

        Duration retryBackoff) {
}
