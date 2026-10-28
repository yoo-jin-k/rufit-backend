package com.rufit.config;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.context.annotation.Profile;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!oidc & !social & (local | test)")
public class DevelopmentCsrfController {
    @GetMapping("/api/v1/csrf")
    public Token token(CsrfToken token) { return new Token(token.getHeaderName(), token.getToken()); }
    @Schema(requiredProperties={"headerName", "token"})
    public record Token(String headerName, String token) {}
}
