package io.groundedaccess.corpus;

import org.jspecify.annotations.Nullable;

/**
 * A document as submitted for ingestion, identified by a key that is stable within its tenant.
 */
public record SourceDocument(
        String key,

        String title,

        @Nullable String sourceUri,

        String content) {
}
