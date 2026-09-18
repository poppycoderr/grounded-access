package io.groundedaccess.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import io.groundedaccess.identity.Principal;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PolicyCompilerTest {

    @Test
    void bindsTheTenantAsAParameterInsteadOfConcatenatingIt() {
        var principal = new Principal("mallory", "x' or '1'='1", null, Set.of(), null, null);

        AuthorizationPredicate predicate = new PolicyCompiler().compile(principal);

        assertThat(predicate.sql()).isEqualTo("c.tenant_id = :auth_tenant_id");
        assertThat(predicate.parameters()).isEqualTo(Map.of("auth_tenant_id", "x' or '1'='1"));
        assertThat(predicate.policyVersion()).isEqualTo(PolicyCompiler.POLICY_VERSION);
    }
}
