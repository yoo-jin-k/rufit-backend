package com.rufit.config;

import io.swagger.v3.oas.annotations.media.Schema;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentAccountController {
    @GetMapping("/api/v1/me")
    public Account me(Principal principal) { return new Account(principal.getName()); }
    @Schema(requiredProperties={"id"})
    public record Account(String id) {}
}
