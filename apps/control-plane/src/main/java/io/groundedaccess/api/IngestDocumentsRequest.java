package io.groundedaccess.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A batch of documents for the caller's tenant; the tenant comes from the token, never from this body.
 */
public record IngestDocumentsRequest(
        @NotEmpty
        @Size(max = 500)
        List<@Valid IngestDocumentRequest> documents) {
}
