package io.groundedaccess.identity;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token security. Tokens without an expiry, a subject or a tenant, or with an authorization claim of the wrong type, are rejected
 * during verification (ADR-0014), so every authenticated request has a subject and a tenant.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableConfigurationProperties(IdentityProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers("/api/v1/ingestion-jobs/**").hasAuthority("SCOPE_admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/documents/*").hasAuthority("SCOPE_query")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/documents/*").hasAuthority("SCOPE_admin")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/documents/*").hasAuthority("SCOPE_admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/retrieval/chunks").hasAllAuthorities("SCOPE_query", "SCOPE_debug")
                        .requestMatchers("/api/v1/retrieval/**").hasAuthority("SCOPE_query")
                        .requestMatchers(HttpMethod.POST, "/api/v1/query").hasAuthority("SCOPE_query")
                        .requestMatchers(HttpMethod.GET, "/api/v1/query-executions/*").hasAuthority("SCOPE_query")
                        .requestMatchers(HttpMethod.GET, "/api/v1/system").hasAuthority("SCOPE_debug")
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
                new TokenClaims()));
        return decoder;
    }
}
