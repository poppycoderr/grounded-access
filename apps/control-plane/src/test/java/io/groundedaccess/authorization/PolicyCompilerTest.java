package io.groundedaccess.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import io.groundedaccess.identity.Principal;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PolicyCompilerTest {

    private final PolicyCompiler compiler = new PolicyCompiler();

    @Test
    void thePredicateTextIsTheSameForEveryPrincipalAndCarriesNoPrincipalValue() {
        var hostile = new Principal("mallory", "x' or '1'='1", "eng') or true --", Set.of("a}\" or \"1\"=\"1"), "restricted' or '1'='1", null);
        var ordinary = new Principal("alice", "northstar", "engineering", Set.of("atlas"), "internal", "EU");

        assertThat(compiler.compile(hostile).sql()).isEqualTo(compiler.compile(ordinary).sql()).doesNotContain("or true", "'1'='1");
        assertThat(compiler.compile(hostile).parameters()).containsEntry("auth_tenant_id", "x' or '1'='1").containsEntry("auth_department", "eng') or true --");
    }

    @Test
    void coversEveryRowOfTheDecisionTable() {
        AuthorizationPredicate predicate = compiler.compile(new Principal("carol", "northstar", "engineering", Set.of("borealis", "atlas"), "confidential", "EU"));

        assertThat(predicate.sql()).contains("c.tenant_id = :auth_tenant_id", "v.classification_rank <= :auth_clearance_rank",
                "cast(:auth_department as text) = any(v.allowed_departments)", "v.required_projects && cast(:auth_projects as text[])");
        assertThat(predicate.parameters()).containsEntry("auth_tenant_id", "northstar")
                .containsEntry("auth_clearance_rank", 2)
                .containsEntry("auth_department", "engineering")
                .containsEntry("auth_projects", "{\"atlas\",\"borealis\"}");
        assertThat(predicate.policyVersion()).isEqualTo("abac/1");
    }

    @Test
    void missingAttributesGrantTheLeastAccess() {
        AuthorizationPredicate predicate = compiler.compile(new Principal("guest", "northstar", null, Set.of(), null, null));

        assertThat(predicate.parameters()).containsEntry("auth_clearance_rank", 0).containsEntry("auth_department", null).containsEntry("auth_projects", "{}");
    }

    @Test
    void anUnknownClearanceIsTreatedAsPublicNotAsTheHighestLevel() {
        for (String claim : List.of("top-secret", "RESTRICTED", "restricted ", "")) {
            assertThat(compiler.compile(new Principal("x", "northstar", null, Set.of(), claim, null)).parameters()).containsEntry("auth_clearance_rank", 0);
        }
        assertThat(compiler.compile(new Principal("x", "northstar", null, Set.of(), "restricted", null)).parameters()).containsEntry("auth_clearance_rank", 3);
    }

    @Test
    void arrayLiteralsQuoteEveryElementSoSeparatorsAndQuotesStayData() {
        assertThat(TextArrays.literal(Set.of("a,b", "c\"d", "e\\f", "{g}"))).isEqualTo("{\"a,b\",\"c\\\"d\",\"e\\\\f\",\"{g}\"}");
        assertThat(TextArrays.literal(Set.of())).isEqualTo("{}");
    }

    @Test
    void labelFingerprintsIgnoreOrderAndMatchTheMigrationBackfillForUnrestrictedLabels() {
        var labels = new AccessLabels(Classification.INTERNAL, Set.of("support", "engineering"), Set.of("atlas"));

        assertThat(labels.sha256()).isEqualTo(new AccessLabels(Classification.INTERNAL, Set.of("engineering", "support"), Set.of("atlas")).sha256())
                .isNotEqualTo(new AccessLabels(Classification.INTERNAL, Set.of("engineering"), Set.of("atlas", "support")).sha256())
                .isNotEqualTo(new AccessLabels(Classification.CONFIDENTIAL, Set.of("support", "engineering"), Set.of("atlas")).sha256());
        assertThat(AccessLabels.UNRESTRICTED.sha256()).isEqualTo("ad39a9fa770b8c62bc04e6378fb1fa23c6e5faf0a721e24696d2efe3fcf555b0");
    }
}
