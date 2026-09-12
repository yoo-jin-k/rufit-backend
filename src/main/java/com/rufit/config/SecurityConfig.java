package com.rufit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import com.rufit.common.ApiSecurityErrors;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    @Profile("!local & !test & !oidc")
    SecurityFilterChain lockedByDefault(HttpSecurity http, ApiSecurityErrors errors) throws Exception {
        return http.cors(org.springframework.security.config.Customizer.withDefaults()).authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll()
                .anyRequest().denyAll())
                .exceptionHandling(handler -> handler.accessDeniedHandler(errors)
                    .authenticationEntryPoint((request, response, exception) -> errors.forbidden(request, response)))
                .build();
    }

    @Bean
    @Profile("!oidc & (local | test)")
    SecurityFilterChain development(HttpSecurity http, ApiSecurityErrors errors) throws Exception {
        // CSRF stays enabled: cookie/browser clients must not issue unprotected writes.
        return http.cors(org.springframework.security.config.Customizer.withDefaults()).authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll()
                .anyRequest().authenticated())
                .exceptionHandling(handler -> handler.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .httpBasic(basic -> basic.authenticationEntryPoint(errors)).build();
    }
}
