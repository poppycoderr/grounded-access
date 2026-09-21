package io.groundedaccess.api;

import io.groundedaccess.identity.Principal;
import io.groundedaccess.retrieval.ChunkCursor;
import io.groundedaccess.retrieval.RetrievalService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

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
     */
    @GetMapping("/chunks")
    public ChunkPageResponse chunks(JwtAuthenticationToken authentication, @RequestParam(value = "after", required = false) @Nullable String after,
            @RequestParam(value = "limit", defaultValue = "500") @Min(1) @Max(1000) int limit) {
        ChunkCursor cursor = after == null ? null : ChunkCursor.parse(after).orElseThrow(() -> new InvalidCursorException(after));
        return ChunkPageResponse.from(retrieval.list(Principal.from(authentication.getToken()), cursor, limit));
    }

    @PostMapping("/search")
    public SearchResponse search(JwtAuthenticationToken authentication, @Valid @RequestBody SearchRequest request) {
        boolean debug = authentication.getAuthorities().stream().anyMatch(a -> "SCOPE_debug".equals(a.getAuthority()));
        var principal = Principal.from(authentication.getToken());
        return SearchResponse.from(retrieval.search(principal, request.query(), request.strategy(), request.limit()), debug);
    }
}
