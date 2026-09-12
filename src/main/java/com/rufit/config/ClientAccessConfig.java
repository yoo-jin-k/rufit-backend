package com.rufit.config;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class ClientAccessConfig {
    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(
        @Value("${rufit.allowed-origins:}") String configured, Environment environment) {
        var origins = Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        origins.forEach(ClientAccessConfig::validateOrigin);
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-CSRF-TOKEN"));
        cors.setAllowCredentials(!environment.acceptsProfiles(Profiles.of("oidc"))
            && environment.acceptsProfiles(Profiles.of("local | test")));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        cors.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    static void validateOrigin(String origin) {
        URI uri = URI.create(origin);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
            || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
            || uri.getRawFragment() != null || !uri.getRawPath().isEmpty() || origin.contains("*"))
            throw new IllegalArgumentException("Allowed origins must be exact HTTP(S) origins without paths or wildcards");
    }
}
