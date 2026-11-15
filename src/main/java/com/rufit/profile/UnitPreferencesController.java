package com.rufit.profile;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

/** 무게 표시 단위(kg·lb) 설정입니다. 세트 무게는 언제나 kg로 저장합니다. */
@RestController
@RequestMapping("/api/v1/preferences/units")
public class UnitPreferencesController {

  private final UnitPreferencesService service;

  UnitPreferencesController(UnitPreferencesService service) {
    this.service = service;
  }

  @GetMapping
  public View get(Principal principal) {
    return service.get(principal.getName());
  }

  @PutMapping
  public View save(@Valid @RequestBody Edit edit, Principal principal) {
    return service.save(principal.getName(), edit);
  }

  public record Edit(
    @NotNull @PositiveOrZero Long version,
    @NotNull @Pattern(regexp = "KG|LB") @Schema(allowableValues = { "KG", "LB" }) String weightUnit
  ) {}

  @Schema(requiredProperties = { "version", "weightUnit" })
  public record View(long version, @Schema(allowableValues = { "KG", "LB" }) String weightUnit) {}
}
