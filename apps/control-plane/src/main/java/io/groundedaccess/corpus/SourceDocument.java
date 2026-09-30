package io.groundedaccess.corpus;

import org.jspecify.annotations.Nullable;

/**
 * A document as submitted for ingestion, identified by a key that is stable within its tenant. {@code format} decides how it is chunked.
 */
public record SourceDocument(
        String key,

        String title,

        @Nullable String sourceUri,

        String content,

        DocumentFormat format) {
}
