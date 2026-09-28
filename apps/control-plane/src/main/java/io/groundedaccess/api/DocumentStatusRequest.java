package io.groundedaccess.api;

import io.groundedaccess.corpus.DocumentStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Locale;

/**
 * A status change for one document: {@code active} or {@code disabled}. Deleting uses {@code DELETE} instead.
 */
public record DocumentStatusRequest(
        @NotNull
        @Pattern(regexp = "active|disabled")
        String status) {

    DocumentStatus toStatus() {
        return DocumentStatus.valueOf(status.toUpperCase(Locale.ROOT));
    }
}
