package com.rufit.exercise;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/exercises/{id}/rest")
public class ExerciseRestController {

  private final ExerciseRestService service;

  ExerciseRestController(ExerciseRestService service) {
    this.service = service;
  }

  @GetMapping
  public View get(@PathVariable UUID id, Principal principal) {
    return service.get(id, principal.getName());
  }

  @PutMapping
  public View save(@PathVariable UUID id, @Valid @RequestBody Edit edit, Principal principal) {
    return service.save(id, principal.getName(), edit);
  }

  public enum Mode {
    RECOMMENDED,
    PERSONAL,
  }

  public enum Source {
    RECOMMENDED,
    PERSONAL,
    DEFAULT,
  }

  public record Edit(
    @NotNull @PositiveOrZero Long version,
    @NotNull Mode mode,
    @Min(0) @Max(3600) Integer restSeconds
  ) {}

  @Schema(
    requiredProperties = {
      "exerciseId",
      "version",
      "recommendedRestSeconds",
      "personalRestSeconds",
      "effectiveRestSeconds",
      "source",
    }
  )
  public record View(
    UUID exerciseId,
    long version,
    @Schema(nullable = true) Integer recommendedRestSeconds,
    @Schema(nullable = true) Integer personalRestSeconds,
    int effectiveRestSeconds,
    Source source
  ) {}
}
