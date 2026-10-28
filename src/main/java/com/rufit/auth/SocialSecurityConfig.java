package com.rufit.auth;

import com.rufit.common.ApiSecurityErrors;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;

@Configuration
@Profile("social")
public class SocialSecurityConfig {
    public SocialSecurityConfig(Environment environment) {
        if(environment.acceptsProfiles(Profiles.of("oidc","local"))) throw new IllegalStateException("social profile must not be combined with oidc/local");
    }
    @Bean Clock authClock() { return Clock.systemUTC(); }
    @Bean SecurityFilterChain socialSecurity(HttpSecurity http,AuthSessions sessions,ObjectMapper mapper,ApiSecurityErrors errors) throws Exception {
        // 모바일 헤더 인증 전용이다. 쿠키 인증을 추가할 때 CSRF 정책부터 별도 설계해야 한다.
        return http.cors(org.springframework.security.config.Customizer.withDefaults())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable()).csrf(c->c.disable())
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/auth/login-attempts","/api/v1/auth/session-exchanges","/api/v1/auth/refresh","/api/v1/auth/logout").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/auth/callback/google","/api/v1/auth/callback/kakao").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(h->h.authenticationEntryPoint(errors::bearer).accessDeniedHandler(errors))
            .addFilterBefore(new SessionAuthenticationFilter(sessions,mapper),UsernamePasswordAuthenticationFilter.class).build();
    }
}
