package io.groundedaccess.api;

import io.groundedaccess.identity.Principal;
import io.groundedaccess.retrieval.ChunkCursor;
import io.groundedaccess.retrieval.RetrievalService;
import io.groundedaccess.retrieval.Scope;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * Lists every chunk the caller may retrieve, for offline reference rankers in the evaluation. Requires the {@code debug} scope.
     * {@code includeOutOfScope} drops the region and validity filters, never the authorization predicate, so the evaluation can compare
     * everything a principal is authorized for with its hand-labelled visible set.
     */
    @GetMapping("/chunks")
    public ChunkPageResponse chunks(JwtAuthenticationToken authentication, @RequestParam(value = "after", required = false) @Nullable String after,
            @RequestParam(value = "limit", defaultValue = "500") @Min(1) @Max(1000) int limit,
            @RequestParam(value = "asOf", required = false) @Nullable Instant asOf,
            @RequestParam(value = "region", required = false) @Nullable String region,
            @RequestParam(value = "includeOutOfScope", defaultValue = "false") boolean includeOutOfScope) {
        ChunkCursor cursor = after == null ? null : ChunkCursor.parse(after).orElseThrow(() -> new InvalidCursorException(after));
        var principal = Principal.from(authentication.getToken());
        Scope scope = includeOutOfScope ? Scope.ANY : retrieval.scope(principal, asOf, region);
        return ChunkPageResponse.from(retrieval.list(principal, scope, cursor, limit));
    }

    @PostMapping("/search")
    public SearchResponse search(JwtAuthenticationToken authentication, @Valid @RequestBody SearchRequest request) {
        boolean debug = authentication.getAuthorities().stream().anyMatch(a -> "SCOPE_debug".equals(a.getAuthority()));
        var principal = Principal.from(authentication.getToken());
        return SearchResponse.from(
                retrieval.search(principal, request.query(), request.strategy(), request.limit(), retrieval.scope(principal, request.asOf(), request.region())),
                debug);
    }
}
