package io.groundedaccess.api;

import io.groundedaccess.corpus.IngestionService;
import io.groundedaccess.identity.Principal;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion for the caller's tenant. Runs synchronously in the walking skeleton; milestone M1 moves it onto the ingestion job queue.
 */
@RestController
@RequestMapping("/api/v1/ingestion-jobs")
public class IngestionController {

    private final IngestionService ingestion;

    public IngestionController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping
    public IngestionResponse ingest(@AuthenticationPrincipal Jwt token, @Valid @RequestBody IngestDocumentsRequest request) {
        var principal = Principal.from(token);
        return IngestionResponse.from(ingestion.ingest(principal.tenantId(), request.documents().stream().map(IngestDocumentRequest::toSource).toList()));
    }
}
