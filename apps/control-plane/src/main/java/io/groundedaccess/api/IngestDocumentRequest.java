package io.groundedaccess.api;

import io.groundedaccess.corpus.SourceDocument;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

/**
 * One document in an ingestion request. Content travels inline so the server never reads client-supplied file paths.
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
        String content) {

    SourceDocument toSource() {
        return new SourceDocument(key, title, sourceUri, content);
    }
}
