package io.groundedaccess.ingestion;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What a client can see about an ingestion job. The counts grow as documents are processed; {@code errorCode} holds the last failure, which on a
 * queued job is the reason for the pending retry.
 */
public record IngestionJob(
        UUID id,

        IngestionJobStatus status,

        int documents,

        int processed,

        int created,

        int updated,

        int unchanged,

        int chunks,

        int attempts,

        int maxAttempts,

        @Nullable String errorCode,

        Instant createdAt,

        @Nullable Instant startedAt,

        @Nullable Instant finishedAt) {
}
