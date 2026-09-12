package com.rufit.config;

import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentAccountController {
    @GetMapping("/api/v1/me")
    public Account me(Principal principal) { return new Account(principal.getName()); }
    public record Account(String id) {}
}
