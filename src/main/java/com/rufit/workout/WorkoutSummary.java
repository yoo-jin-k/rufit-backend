package com.rufit.workout;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class WorkoutSummary {

  private final WorkoutService workouts;

  WorkoutSummary(WorkoutService workouts) {
    this.workouts = workouts;
  }

  public View get(UUID id, String owner) {
    // The service returns a fully materialized DTO from one database snapshot.
    WorkoutController.View workout = workouts.get(id, owner);
    if (workout.finishedAt() == null) throw new WorkoutConflict(
      "완료한 운동의 결과만 조회할 수 있습니다."
    );
    Map<String, Exercise> groups = new LinkedHashMap<>();
    for (int index : workout.completedSetIndexes()) {
      var set = workout.sets().get(index);
      String key =
        set.exerciseId() == null ? "name:" + set.exerciseName() : "id:" + set.exerciseId();
      Exercise previous = groups.get(key);
      BigDecimal volume = set.weight().multiply(BigDecimal.valueOf(set.reps()));
      boolean heavier =
        previous == null ||
        set.weight().compareTo(previous.heaviestWeight()) > 0 ||
        (set.weight().compareTo(previous.heaviestWeight()) == 0 &&
          set.reps() > previous.repsAtHeaviestWeight());
      groups.put(
        key,
        new Exercise(
          set.exerciseId(),
          previous == null ? set.exerciseName() : previous.exerciseName(),
          (previous == null ? 0 : previous.completedSets()) + 1,
          (previous == null ? 0 : previous.totalReps()) + set.reps(),
          (previous == null ? BigDecimal.ZERO : previous.volumeKgReps()).add(volume),
          heavier ? set.weight() : previous.heaviestWeight(),
          heavier ? set.reps() : previous.repsAtHeaviestWeight()
        )
      );
    }
    List<Exercise> exercises = List.copyOf(groups.values());
    return new View(
      workout.id(),
      workout.version(),
      workout.startedAt(),
      workout.finishedAt(),
      (Duration.between(workout.startedAt(), workout.finishedAt()).toMillis() -
          workout.pausedDurationMs()) /
        1000,
      workout.completedSetIndexes().size(),
      exercises.stream().mapToLong(Exercise::totalReps).sum(),
      exercises.stream().map(Exercise::volumeKgReps).reduce(BigDecimal.ZERO, BigDecimal::add),
      exercises
    );
  }

  @Schema(
    requiredProperties = {
      "exerciseId",
      "exerciseName",
      "completedSets",
      "totalReps",
      "volumeKgReps",
      "heaviestWeight",
      "repsAtHeaviestWeight",
    }
  )
  public record Exercise(
    @Schema(nullable = true) UUID exerciseId,
    String exerciseName,
    int completedSets,
    long totalReps,
    BigDecimal volumeKgReps,
    BigDecimal heaviestWeight,
    int repsAtHeaviestWeight
  ) {}

  @Schema(
    requiredProperties = {
      "workoutId",
      "version",
      "startedAt",
      "finishedAt",
      "durationSeconds",
      "completedSets",
      "totalReps",
      "volumeKgReps",
      "exercises",
    }
  )
  public record View(
    UUID workoutId,
    long version,
    Instant startedAt,
    Instant finishedAt,
    long durationSeconds,
    int completedSets,
    long totalReps,
    BigDecimal volumeKgReps,
    List<Exercise> exercises
  ) {}
}
