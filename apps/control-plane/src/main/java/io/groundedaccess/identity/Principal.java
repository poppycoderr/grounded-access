package io.groundedaccess.identity;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated caller and the attributes authorization decisions are based on. Built only from a verified token, never from request data; verification
 * has already checked the type of every claim read here ({@link TokenClaims}).
 */
public record Principal(
        String subject,

        String tenantId,

        @Nullable String department,

        Set<String> projects,

        @Nullable String clearance,

        @Nullable String region) {

    public static final String TENANT_CLAIM = "tenant_id";

    public static Principal from(Jwt jwt) {
        List<String> projects = Objects.requireNonNullElse(jwt.getClaimAsStringList(TokenClaims.PROJECTS), List.of());
        String subject = Objects.requireNonNull(jwt.getSubject(), "verified tokens carry a subject");
        String tenant = Objects.requireNonNull(jwt.getClaimAsString(TENANT_CLAIM), "the token validator rejects tokens without a tenant");
        return new Principal(subject, tenant, jwt.getClaimAsString(TokenClaims.DEPARTMENT), Set.copyOf(projects),
                jwt.getClaimAsString(TokenClaims.CLEARANCE), jwt.getClaimAsString(TokenClaims.REGION));
    }
}
