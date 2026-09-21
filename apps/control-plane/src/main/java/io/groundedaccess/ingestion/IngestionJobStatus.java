package io.groundedaccess.ingestion;

import java.util.Locale;

/**
 * Lifecycle of an ingestion job. {@code FAILED} is terminal and doubles as the dead-letter state.
 */
public enum IngestionJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED;

    static IngestionJobStatus fromColumn(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
