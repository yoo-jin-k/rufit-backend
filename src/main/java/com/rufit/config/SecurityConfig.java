package com.rufit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    @Profile("!local & !test")
    SecurityFilterChain lockedByDefault(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().denyAll()).build();
    }

    @Bean
    @Profile({"local", "test"})
    SecurityFilterChain development(HttpSecurity http) throws Exception {
        // CSRF stays enabled: cookie/browser clients must not issue unprotected writes.
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults()).build();
    }
}
