package com.rufit.workout;

import io.swagger.v3.oas.annotations.media.Schema;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workouts/previous-sets")
public class PreviousExerciseSetsController {

  private final PreviousExerciseSets service;

  PreviousExerciseSetsController(PreviousExerciseSets service) {
    this.service = service;
  }

  @GetMapping
  public Results get(@RequestParam List<UUID> exerciseIds, Principal principal) {
    return service.get(principal.getName(), exerciseIds);
  }

  @Schema(requiredProperties = { "items" })
  public record Results(List<Item> items) {}

  @Schema(requiredProperties = { "exerciseId", "exerciseName", "record" })
  public record Item(
    UUID exerciseId,
    String exerciseName,
    @Schema(nullable = true) Record record
  ) {}

  @Schema(requiredProperties = { "workoutId", "finishedAt", "sets" })
  public record Record(UUID workoutId, Instant finishedAt, List<WorkoutController.SetInput> sets) {}
}
