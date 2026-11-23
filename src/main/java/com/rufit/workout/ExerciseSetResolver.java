package com.rufit.workout;

import com.rufit.exercise.Exercise;
import com.rufit.exercise.ExerciseController;
import com.rufit.exercise.ExerciseService;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ExerciseSetResolver {

  private final ExerciseService catalog;

  public ExerciseSetResolver(ExerciseService catalog) {
    this.catalog = catalog;
  }

  public List<Workout.Entry> resolve(
    String owner,
    List<WorkoutController.SetInput> inputs,
    List<Workout.Entry> existing
  ) {
    Map<UUID, Workout.Entry> snapshots = new HashMap<>();
    for (var entry : existing)
      if (entry.exerciseId() != null) snapshots.putIfAbsent(entry.exerciseId(), entry);
    Map<UUID, ExerciseController.View> lookedUp = new HashMap<>();
    return inputs
      .stream()
      .map(input -> {
        if (input.exerciseId() == null) return entry(input, null, input.exerciseName());
        // 종목의 기록 방식(무게·횟수 또는 시간)과 세트 모양이 맞는지 확인하려고 종목을 한 번씩 조회합니다.
        var selected = lookedUp.computeIfAbsent(input.exerciseId(), id -> catalog.get(id, owner));
        boolean timed = selected.tracking() == Exercise.Tracking.DURATION;
        if (timed != (input.durationSeconds() != null)) throw new IllegalArgumentException(
          timed
            ? selected.name() + "은(는) 시간(초)으로 기록하는 종목입니다."
            : selected.name() + "은(는) 무게·횟수로 기록하는 종목입니다."
        );
        Workout.Entry snapshot = snapshots.get(input.exerciseId());
        String name;
        if (snapshot != null) name = snapshot.name();
        else {
          if (selected.archivedAt() != null) throw new IllegalArgumentException(
            "보관한 종목은 새로 추가할 수 없습니다."
          );
          name = selected.name();
        }
        return entry(input, input.exerciseId(), name);
      })
      .toList();
  }

  private static Workout.Entry entry(WorkoutController.SetInput input, UUID exerciseId, String name) {
    return new Workout.Entry(
      exerciseId,
      name,
      input.weight(),
      input.reps(),
      input.durationSeconds(),
      input.setType(),
      input.rpe()
    );
  }

  public String name(String owner, UUID id) {
    return catalog.get(id, owner).name();
  }
}
