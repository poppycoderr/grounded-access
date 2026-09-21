package io.groundedaccess.api;

import io.groundedaccess.ingestion.IngestionJob;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * State of an ingestion job. {@code status} is one of {@code queued}, {@code running}, {@code succeeded} or {@code failed}.
 */
public record IngestionJobResponse(
        UUID jobId,

        String status,

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

    static IngestionJobResponse from(IngestionJob job) {
        return new IngestionJobResponse(job.id(), job.status().name().toLowerCase(Locale.ROOT), job.documents(), job.processed(), job.created(),
                job.updated(), job.unchanged(), job.chunks(), job.attempts(), job.maxAttempts(), job.errorCode(), job.createdAt(), job.startedAt(),
                job.finishedAt());
    }
}
