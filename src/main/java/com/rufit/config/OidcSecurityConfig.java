package com.rufit.config;

import com.rufit.common.ApiSecurityErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Configuration
@Profile("oidc")
public class OidcSecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${rufit.auth.issuer}") String issuer,
        @Value("${rufit.auth.jwk-set-uri}") String jwkSetUri, @Value("${rufit.auth.audience}") String audience) {
        if (issuer.isBlank() || jwkSetUri.isBlank() || audience.isBlank())
            throw new IllegalArgumentException("OIDC issuer, JWKS, audience 설정이 필요합니다.");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            if (jwt.getAudience() != null && jwt.getAudience().contains(audience) && jwt.getExpiresAt() != null
                && jwt.getSubject() != null && !jwt.getSubject().isBlank()) return OAuth2TokenValidatorResult.success();
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required claims are missing or invalid", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }
    @Bean
    SecurityFilterChain oidc(HttpSecurity http, ApiSecurityErrors errors) throws Exception {
        // This profile accepts Authorization-header bearer tokens only, never cookies or Basic credentials.
        return http.cors(org.springframework.security.config.Customizer.withDefaults()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated())
            .exceptionHandling(handler -> handler.authenticationEntryPoint(errors::bearer).accessDeniedHandler(errors))
            .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(errors::bearer).accessDeniedHandler(errors)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> new JwtAuthenticationToken(token, List.of(), ownerKey(token)))))
            .build();
    }
    static String ownerKey(Jwt jwt) {
        // Include the issuer so equal subjects from different providers never share ownership.
        String issuer = jwt.getIssuer().toString();
        String identity = issuer.length() + ":" + issuer + jwt.getSubject();
        try {
            return "oidc:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
