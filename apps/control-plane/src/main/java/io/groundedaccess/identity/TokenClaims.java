package io.groundedaccess.identity;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The claims a token must carry and the type of each claim authorization reads (ADR-0014). A token that breaks any rule is rejected during
 * verification, so a claim of the wrong type is never turned into a string and read as a value.
 */
public final class TokenClaims implements OAuth2TokenValidator<Jwt> {

    static final String DEPARTMENT = "department";
    static final String PROJECTS = "projects";
    static final String CLEARANCE = "clearance";
    static final String REGION = "region";
    private static final List<String> OPTIONAL_STRINGS = List.of(DEPARTMENT, CLEARANCE, REGION);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> problems = problems(jwt);
        return problems.isEmpty()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", String.join("; ", problems), null));
    }

    static List<String> problems(Jwt jwt) {
        List<String> problems = new ArrayList<>();
        if (jwt.getExpiresAt() == null) {
            problems.add("exp is required");
        }
        if (!nonBlankString(jwt.getClaims().get("sub"))) {
            problems.add("sub must be a non-blank string");
        }
        if (!nonBlankString(jwt.getClaims().get(Principal.TENANT_CLAIM))) {
            problems.add(Principal.TENANT_CLAIM + " must be a non-blank string");
        }
        for (String name : OPTIONAL_STRINGS) {
            Object value = jwt.getClaims().get(name);
            if (value != null && !(value instanceof String)) {
                problems.add(name + " must be a string");
            }
        }
        Object projects = jwt.getClaims().get(PROJECTS);
        if (projects != null && !(projects instanceof List<?> list && list.stream().allMatch(String.class::isInstance))) {
            problems.add(PROJECTS + " must be an array of strings");
        }
        return problems;
    }

    private static boolean nonBlankString(@Nullable Object value) {
        return value instanceof String text && !text.isBlank();
    }
}
