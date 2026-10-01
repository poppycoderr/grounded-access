package io.groundedaccess.authorization;

import io.groundedaccess.identity.Principal;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Compiles a principal into the SQL predicate that every chunk query embeds. It implements the decision table in
 * docs/architecture/authorization.md: tenant, clearance, department and project, all of which must hold. The predicate text is constant; only bound
 * parameters vary with the principal, so no principal value can change the shape of the query.
 */
@Component
public class PolicyCompiler {

    public static final String POLICY_VERSION = "abac/1";

    /**
     * Refers to the chunk as {@code c} and to its document version as {@code v}. A principal without a department or without projects sees only
     * documents that do not restrict that attribute: comparing against a null department is never true, and nothing overlaps an empty array.
     */
    private static final String PREDICATE = """
            c.tenant_id = :auth_tenant_id \
            and v.classification_rank <= :auth_clearance_rank \
            and (cardinality(v.allowed_departments) = 0 or cast(:auth_department as text) = any(v.allowed_departments)) \
            and (cardinality(v.required_projects) = 0 or v.required_projects && cast(:auth_projects as text[]))""";

    public AuthorizationPredicate compile(Principal principal) {
        Map<String, @Nullable Object> parameters = new HashMap<>();
        parameters.put("auth_tenant_id", principal.tenantId());
        parameters.put("auth_clearance_rank", Classification.clearanceOf(principal.clearance()).rank());
        parameters.put("auth_department", principal.department());
        parameters.put("auth_projects", TextArrays.literal(principal.projects()));
        return new AuthorizationPredicate(PREDICATE, parameters, POLICY_VERSION);
    }
}
