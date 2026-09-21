package io.groundedaccess.api;

import io.groundedaccess.identity.Principal;
import io.groundedaccess.ingestion.IngestionJob;
import io.groundedaccess.ingestion.IngestionJobService;

import jakarta.validation.Valid;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion jobs for the caller's tenant. Submitting returns {@code 202} at once; the client polls the job until it reaches a terminal status.
 */
@RestController
@RequestMapping("/api/v1/ingestion-jobs")
public class IngestionController {

    private final IngestionJobService jobs;

    public IngestionController(IngestionJobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    public ResponseEntity<IngestionJobResponse> submit(@AuthenticationPrincipal Jwt token, @Valid @RequestBody IngestDocumentsRequest request) {
        IngestionJob job = jobs.submit(Principal.from(token), request.documents().stream().map(IngestDocumentRequest::toSource).toList());
        return ResponseEntity.accepted().location(URI.create("/api/v1/ingestion-jobs/" + job.id())).body(IngestionJobResponse.from(job));
    }

    /**
     * A job of another tenant answers 404, the same as a job that does not exist.
     */
    @GetMapping("/{id}")
    public IngestionJobResponse find(@AuthenticationPrincipal Jwt token, @PathVariable("id") UUID id) {
        return jobs.find(Principal.from(token), id).map(IngestionJobResponse::from).orElseThrow(JobNotFoundException::new);
    }
}
