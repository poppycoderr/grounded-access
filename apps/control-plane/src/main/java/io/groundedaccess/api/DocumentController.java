package io.groundedaccess.api;

import io.groundedaccess.corpus.DocumentLifecycle;
import io.groundedaccess.identity.Principal;
import io.groundedaccess.retrieval.RetrievalService;

import jakarta.validation.Valid;

import java.util.regex.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Documents of the caller's tenant, addressed by the key they were ingested under. Reading needs the {@code query} scope and goes through the
 * authorization predicate; changing status and deleting need the {@code admin} scope and apply to the next query. A key the caller may not see
 * answers 404, whatever the reason.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,199}");

    private final DocumentLifecycle documents;

    private final RetrievalService retrieval;

    public DocumentController(DocumentLifecycle documents, RetrievalService retrieval) {
        this.documents = documents;
        this.retrieval = retrieval;
    }

    /**
     * Reads a document the caller is authorized for. Every other case gives the same 404: a key that does not exist, a document of another
     * tenant, one the caller's attributes do not admit, a disabled or deleted one, and a key that is not well formed.
     */
    @GetMapping("/{key}")
    public DocumentViewResponse read(@AuthenticationPrincipal Jwt token, @PathVariable("key") String key) {
        if (!KEY.matcher(key).matches()) {
            throw new NotFoundException("No such document");
        }
        return retrieval.document(Principal.from(token), key).map(DocumentViewResponse::from).orElseThrow(() -> new NotFoundException("No such document"));
    }

    @PatchMapping("/{key}")
    public DocumentResponse changeStatus(@AuthenticationPrincipal Jwt token, @PathVariable("key") String key,
            @Valid @RequestBody DocumentStatusRequest request) {
        return documents.setStatus(Principal.from(token), key, request.toStatus())
                .map(DocumentResponse::from)
                .orElseThrow(() -> new NotFoundException("No such document"));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt token, @PathVariable("key") String key) {
        if (!documents.delete(Principal.from(token), key)) {
            throw new NotFoundException("No such document");
        }
        return ResponseEntity.noContent().build();
    }
}
