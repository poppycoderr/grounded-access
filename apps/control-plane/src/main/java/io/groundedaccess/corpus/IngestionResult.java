package io.groundedaccess.corpus;

/**
 * Outcome of one ingestion request: how many documents got a new version and how many were already current.
 */
public record IngestionResult(
        int created,

        int updated,

        int unchanged,

        int chunks) {
}
