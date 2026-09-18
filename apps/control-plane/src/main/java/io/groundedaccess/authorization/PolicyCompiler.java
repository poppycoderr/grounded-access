package io.groundedaccess.authorization;

import io.groundedaccess.identity.Principal;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Compiles a principal into the SQL predicate that both retrieval channels embed. Walking-skeleton version: tenant isolation only; the full decision
 * table from docs/architecture/authorization.md replaces it in milestone M2.
 */
@Component
public class PolicyCompiler {

    public static final String POLICY_VERSION = "tenant-only/1";

    public AuthorizationPredicate compile(Principal principal) {
        return new AuthorizationPredicate("c.tenant_id = :auth_tenant_id", Map.of("auth_tenant_id", principal.tenantId()), POLICY_VERSION);
    }
}
