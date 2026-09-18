package io.groundedaccess.api;

import io.groundedaccess.identity.Principal;
import io.groundedaccess.retrieval.RetrievalService;

import jakarta.validation.Valid;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retrieval endpoint used by clients and by the evaluation CLI.
 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class RetrievalController {

    private final RetrievalService retrieval;

    public RetrievalController(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    @PostMapping("/search")
    public SearchResponse search(JwtAuthenticationToken authentication, @Valid @RequestBody SearchRequest request) {
        boolean debug = authentication.getAuthorities().stream().anyMatch(a -> "SCOPE_debug".equals(a.getAuthority()));
        var principal = Principal.from(authentication.getToken());
        return SearchResponse.from(retrieval.search(principal, request.query(), request.strategy(), request.limit()), debug);
    }
}
