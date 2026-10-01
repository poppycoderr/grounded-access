package io.groundedaccess.api;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.groundedaccess.authorization.AccessLabels;
import io.groundedaccess.authorization.Classification;
import io.groundedaccess.corpus.DocumentFormat;
import io.groundedaccess.corpus.DocumentScope;
import io.groundedaccess.corpus.SourceDocument;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * One document in an ingestion request. Content travels inline so the server never reads client-supplied file paths. The labels say who in
 * the tenant may read the document; the tenant itself always comes from the token. Regions and the validity window are scope: they say
 * where and when the document applies, not who may read it.
 */
public record IngestDocumentRequest(
        @NotBlank
        @Size(max = 200)
        @Pattern(regexp = "[a-z0-9][a-z0-9._-]*")
        String key,

        @NotBlank
        @Size(max = 500)
        String title,

        @Nullable
        @Size(max = 2000)
        String sourceUri,

        @NotBlank
        @Size(max = 500_000)
        String content,

        @Nullable
        @Pattern(regexp = "markdown|text")
        String format,

        @Nullable
        @Pattern(regexp = "public|internal|confidential|restricted")
        String classification,

        @Nullable
        @Size(max = 50)
        List<@NotBlank @Size(max = 100) String> allowedDepartments,

        @Nullable
        @Size(max = 50)
        List<@NotBlank @Size(max = 100) String> requiredProjects,

        @Nullable
        @Size(max = 50)
        List<@NotBlank @Size(max = 50) String> appliesToRegions,

        @Nullable Instant validFrom,

        @Nullable Instant validTo) {

    @JsonIgnore
    @AssertTrue(message = "validFrom must be before validTo")
    boolean isValidityWindowOrdered() {
        return validFrom == null || validTo == null || validFrom.isBefore(validTo);
    }

    /**
     * Markdown unless the request says {@code text}. A document without labels is public and unrestricted within its tenant.
     */
    SourceDocument toSource() {
        var labels = new AccessLabels(classification == null ? Classification.PUBLIC : Classification.fromColumn(classification),
                allowedDepartments == null ? Set.of() : Set.copyOf(allowedDepartments), requiredProjects == null ? Set.of() : Set.copyOf(requiredProjects));
        var scope = new DocumentScope(appliesToRegions == null ? Set.of() : Set.copyOf(appliesToRegions), validFrom, validTo);
        return new SourceDocument(key, title, sourceUri, content, format == null ? DocumentFormat.MARKDOWN : DocumentFormat.fromColumn(format), labels, scope);
    }
}
