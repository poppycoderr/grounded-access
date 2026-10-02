package io.groundedaccess.api;

import io.groundedaccess.audit.AuditTrail;
import io.groundedaccess.identity.Principal;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Execution records of the caller's own retrieval requests. A record of another principal answers 404, the same as one that does not exist.
 */
@RestController
@RequestMapping("/api/v1/query-executions")
public class QueryExecutionController {

    private final AuditTrail audit;

    public QueryExecutionController(AuditTrail audit) {
        this.audit = audit;
    }

    @GetMapping("/{id}")
    public QueryExecutionResponse find(@AuthenticationPrincipal Jwt token, @PathVariable("id") UUID id) {
        return audit.findExecution(Principal.from(token), id).map(QueryExecutionResponse::from).orElseThrow(() -> new NotFoundException("No such execution"));
    }
}
