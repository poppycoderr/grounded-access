package io.groundedaccess.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class TokenClaimsTest {

    @Test
    void acceptsATokenWithAnExpirySubjectTenantAndWellTypedAttributes() {
        Jwt jwt = jwt(Map.of("department", "engineering", "projects", List.of("atlas"), "clearance", "internal", "region", "EU"), true);

        assertThat(TokenClaims.problems(jwt)).isEmpty();
        assertThat(Principal.from(jwt)).isEqualTo(new Principal("alice", "northstar", "engineering", Set.of("atlas"), "internal", "EU"));
    }

    @Test
    void acceptsATokenWithoutAnyOptionalAttribute() {
        assertThat(TokenClaims.problems(jwt(Map.of(), true))).isEmpty();
    }

    @Test
    void rejectsATokenWithoutAnExpiry() {
        assertThat(TokenClaims.problems(jwt(Map.of(), false))).containsExactly("exp is required");
    }

    @Test
    void rejectsATenantOrSubjectThatIsNotANonBlankString() {
        assertThat(TokenClaims.problems(jwt(Map.of("tenant_id", List.of("northstar", "orbit")), true))).containsExactly("tenant_id must be a non-blank string");
        assertThat(TokenClaims.problems(jwt(Map.of("tenant_id", " "), true))).containsExactly("tenant_id must be a non-blank string");
        assertThat(TokenClaims.problems(jwt(Map.of("sub", ""), true))).containsExactly("sub must be a non-blank string");
    }

    @Test
    void rejectsAttributesOfTheWrongTypeInsteadOfTurningThemIntoStrings() {
        assertThat(TokenClaims.problems(jwt(Map.of("clearance", List.of("restricted")), true))).containsExactly("clearance must be a string");
        assertThat(TokenClaims.problems(jwt(Map.of("department", List.of("engineering", "finance")), true))).containsExactly("department must be a string");
        assertThat(TokenClaims.problems(jwt(Map.of("region", 7), true))).containsExactly("region must be a string");
        assertThat(TokenClaims.problems(jwt(Map.of("projects", "atlas"), true))).containsExactly("projects must be an array of strings");
        assertThat(TokenClaims.problems(jwt(Map.of("projects", List.of("atlas", 3)), true))).containsExactly("projects must be an array of strings");
    }

    @Property
    void acceptsExactlyTheTokensWhoseAttributesHaveTheirTypeAndReadsThemUnchanged(
            @ForAll("claimValue") @Nullable Object department,
            @ForAll("claimValue") @Nullable Object clearance,
            @ForAll("claimValue") @Nullable Object region,
            @ForAll("claimValue") @Nullable Object projects) {
        Map<String, Object> claims = new HashMap<>();
        put(claims, "department", department);
        put(claims, "clearance", clearance);
        put(claims, "region", region);
        put(claims, "projects", projects);
        Jwt jwt = jwt(claims, true);

        boolean welltyped = stringOrAbsent(department) && stringOrAbsent(clearance) && stringOrAbsent(region)
                && (projects == null || projects instanceof List<?> list && list.stream().allMatch(String.class::isInstance));

        assertThat(TokenClaims.problems(jwt).isEmpty()).isEqualTo(welltyped);
        if (welltyped) {
            Principal principal = Principal.from(jwt);
            assertThat(principal.department()).isEqualTo(department);
            assertThat(principal.clearance()).isEqualTo(clearance);
            assertThat(principal.region()).isEqualTo(region);
            assertThat(principal.projects()).isEqualTo(projects == null ? Set.of() : Set.copyOf((List<?>) projects));
        }
    }

    @Provide
    Arbitrary<@Nullable Object> claimValue() {
        Arbitrary<String> text = Arbitraries.strings().withChars('a', 'b', 'E', 'U', ' ', ',').ofMaxLength(4);
        return Arbitraries.<@Nullable Object>oneOf(
                text,
                text.list().ofMaxSize(3).map(list -> (Object) list),
                Arbitraries.integers().map(i -> (Object) i),
                Arbitraries.of(true, false).map(b -> (Object) b),
                text.map(t -> (Object) Map.of("value", t)),
                Arbitraries.oneOf(text, Arbitraries.integers()).list().ofMinSize(1).ofMaxSize(3).map(list -> (Object) list)).injectNull(0.25);
    }

    private static boolean stringOrAbsent(@Nullable Object value) {
        return value == null || value instanceof String;
    }

    private static void put(Map<String, Object> claims, String name, @Nullable Object value) {
        if (value != null) {
            claims.put(name, value);
        }
    }

    private static Jwt jwt(Map<String, Object> attributes, boolean withExpiry) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").claim("sub", "alice").claim("tenant_id", "northstar");
        if (withExpiry) {
            builder.expiresAt(Instant.now().plusSeconds(600));
        }
        attributes.forEach(builder::claim);
        return builder.build();
    }
}
