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
    @Profile("!local & !test")
    SecurityFilterChain lockedByDefault(HttpSecurity http, ApiSecurityErrors errors) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().denyAll())
                .exceptionHandling(handler -> handler.accessDeniedHandler(errors)
                    .authenticationEntryPoint((request, response, exception) -> errors.forbidden(request, response)))
                .build();
    }

    @Bean
    @Profile({"local", "test"})
    SecurityFilterChain development(HttpSecurity http, ApiSecurityErrors errors) throws Exception {
        // CSRF stays enabled: cookie/browser clients must not issue unprotected writes.
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
                .exceptionHandling(handler -> handler.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .httpBasic(basic -> basic.authenticationEntryPoint(errors)).build();
    }
}
