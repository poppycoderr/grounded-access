package io.groundedaccess.api;

import io.groundedaccess.corpus.DocumentLifecycle;
import io.groundedaccess.identity.Principal;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration of documents in the caller's tenant, addressed by the key they were ingested under. Changes apply to the next query. A key
 * that is unknown, deleted or owned by another tenant answers 404.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentLifecycle documents;

    public DocumentController(DocumentLifecycle documents) {
        this.documents = documents;
    }

    @PatchMapping("/{key}")
    public DocumentResponse changeStatus(@AuthenticationPrincipal Jwt token, @PathVariable("key") String key,
            @Valid @RequestBody DocumentStatusRequest request) {
        return documents.setStatus(Principal.from(token).tenantId(), key, request.toStatus())
                .map(DocumentResponse::from)
                .orElseThrow(() -> new NotFoundException("No such document"));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt token, @PathVariable("key") String key) {
        if (!documents.delete(Principal.from(token).tenantId(), key)) {
            throw new NotFoundException("No such document");
        }
        return ResponseEntity.noContent().build();
    }
}
