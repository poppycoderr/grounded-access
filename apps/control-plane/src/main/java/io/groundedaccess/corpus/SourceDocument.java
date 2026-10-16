package io.groundedaccess.corpus;

import io.groundedaccess.authorization.AccessLabels;

import java.util.Collection;

import org.jspecify.annotations.Nullable;

/**
 * A document as submitted for ingestion, identified by a key that is stable within its tenant. {@code format} decides how it is chunked and
 * {@code labels} who may read it; {@code scope} says where and when it applies.
 *
 * <p>
 * Label and region values are checked here as well as in the API, so that a caller inside the application cannot store a value the API would
 * reject: no value is blank or contains a control character. A blank source link is no source link.
 */
public record SourceDocument(
        String key,

        String title,

        @Nullable String sourceUri,

        String content,

        DocumentFormat format,

        AccessLabels labels,

        DocumentScope scope) {

    public SourceDocument {
        sourceUri = sourceUri == null || sourceUri.isBlank() ? null : sourceUri;
        requirePlain("allowedDepartments", labels.allowedDepartments());
        requirePlain("requiredProjects", labels.requiredProjects());
        requirePlain("appliesToRegions", scope.appliesToRegions());
    }

    private static void requirePlain(String field, Collection<String> values) {
        for (String value : values) {
            if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(field + " values must not be blank or contain control characters");
            }
        }
    }
}
