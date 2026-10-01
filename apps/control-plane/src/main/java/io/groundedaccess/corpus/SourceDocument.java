package io.groundedaccess.corpus;

import io.groundedaccess.authorization.AccessLabels;

import org.jspecify.annotations.Nullable;

/**
 * A document as submitted for ingestion, identified by a key that is stable within its tenant. {@code format} decides how it is chunked and
 * {@code labels} who may read it; {@code scope} says where and when it applies.
 */
public record SourceDocument(
        String key,

        String title,

        @Nullable String sourceUri,

        String content,

        DocumentFormat format,

        AccessLabels labels,

        DocumentScope scope) {
}
