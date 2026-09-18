package io.groundedaccess.identity;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token security. Tokens without a subject or tenant claim are rejected during verification, so every authenticated request has both.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers("/api/v1/ingestion-jobs/**").hasAuthority("SCOPE_admin")
                        .requestMatchers("/api/v1/retrieval/**").hasAuthority("SCOPE_query")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> { }));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(IdentityProperties properties) throws IOException {
        RSAPublicKey key;
        try (InputStream in = properties.publicKey().getInputStream()) {
            key = RsaKeyConverters.x509().convert(in);
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(properties.audience())),
                SecurityConfiguration::requireSubjectAndTenant));
        return decoder;
    }

    private static OAuth2TokenValidatorResult requireSubjectAndTenant(Jwt jwt) {
        String subject = jwt.getSubject();
        String tenant = jwt.getClaimAsString(Principal.TENANT_CLAIM);
        return subject == null || subject.isBlank() || tenant == null || tenant.isBlank()
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "sub and tenant_id claims are required", null))
                : OAuth2TokenValidatorResult.success();
    }
}
