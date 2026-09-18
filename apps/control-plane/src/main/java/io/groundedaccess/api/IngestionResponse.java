package io.groundedaccess.api;

import io.groundedaccess.corpus.IngestionResult;

/**
 * Result of a synchronous ingestion run.
 */
public record IngestionResponse(
        String status,

        int created,

        int updated,

        int unchanged,

        int chunks) {

    static IngestionResponse from(IngestionResult result) {
        return new IngestionResponse("succeeded", result.created(), result.updated(), result.unchanged(), result.chunks());
    }
}
