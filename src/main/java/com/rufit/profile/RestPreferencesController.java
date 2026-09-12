package com.rufit.profile;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/preferences/rest")
public class RestPreferencesController {
    private final RestPreferencesService service;
    RestPreferencesController(RestPreferencesService service) { this.service=service; }
    @GetMapping public View get(Principal principal) { return service.get(principal.getName()); }
    @PutMapping public View save(@Valid @RequestBody Edit edit,Principal principal) {
        return service.save(principal.getName(),edit);
    }
    public record Edit(@NotNull @PositiveOrZero Long version,@NotNull @Min(0) @Max(3600) Integer defaultRestSeconds) {}
    @Schema(requiredProperties={"version","defaultRestSeconds"})
    public record View(long version,int defaultRestSeconds) {}
}
