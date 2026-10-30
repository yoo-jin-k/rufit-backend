package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkoutSetEditingTests {

  private Workout.Entry entry(int reps) {
    return new Workout.Entry("스쿼트", BigDecimal.TEN, reps);
  }

  private Workout workout() {
    Workout w = new Workout(UUID.randomUUID(), "set-edit-test", Instant.EPOCH);
    w.replaceEntries(List.of(entry(10), entry(8), entry(6)));
    return w;
  }

  @Test
  void editingAndAppendingPreserveCompletedSetsAndRestDeadline() {
    Workout w = workout();
    w.completeSet(0, true, 90, Instant.EPOCH);
    w.editSet(1, entry(12));
    w.appendSet(entry(5));
    assertThat(w.entries()).hasSize(4);
    assertThat(w.entries().get(1).reps()).isEqualTo(12);
    assertThat(w.completedSetIndexes()).containsExactly(0);
    assertThat(w.restEndsAt()).isEqualTo(Instant.EPOCH.plusSeconds(90));
  }

  @Test
  void removalRemapsCompletionsAndRestSource() {
    Workout w = workout();
    w.completeSet(2, true, 90, Instant.EPOCH);
    w.removeSet(0);
    assertThat(w.entries()).extracting(Workout.Entry::reps).containsExactly(8, 6);
    assertThat(w.completedSetIndexes()).containsExactly(1);
    assertThat(w.restEndsAt()).isEqualTo(Instant.EPOCH.plusSeconds(90));
    w.completeSet(1, false, 0, Instant.EPOCH.plusSeconds(10));
    assertThat(w.restEndsAt()).isNull();
  }

  @Test
  void removingRestSourceClearsTimerAndLeavesOtherCompletion() {
    Workout w = workout();
    w.completeSet(0, true, 0, Instant.EPOCH);
    w.completeSet(1, true, 60, Instant.EPOCH);
    w.removeSet(1);
    assertThat(w.completedSetIndexes()).containsExactly(0);
    assertThat(w.restEndsAt()).isNull();
  }

  @Test
  void deletingAllSetsLeavesAnActiveEmptyDraft() {
    Workout w = workout();
    for (int i = 0; i < 3; i++) w.removeSet(0);
    assertThat(w.entries()).isEmpty();
    assertThatThrownBy(() -> w.finish(Instant.EPOCH.plusSeconds(10))).isInstanceOf(
      IllegalArgumentException.class
    );
    w.appendSet(entry(1));
    assertThat(w.entries()).hasSize(1);
  }

  @Test
  void boundsAndFinishedWorkoutAreProtected() {
    Workout w = workout();
    assertThatThrownBy(() -> w.editSet(-1, entry(1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> w.removeSet(3)).isInstanceOf(IllegalArgumentException.class);
    w.replaceEntries(Collections.nCopies(300, entry(1)));
    assertThatThrownBy(() -> w.appendSet(entry(1))).isInstanceOf(IllegalArgumentException.class);
    w.completeSet(0, true, 0, Instant.EPOCH);
    w.finish(Instant.EPOCH.plusSeconds(1), true);
    assertThatThrownBy(() -> w.editSet(0, entry(2))).isInstanceOf(WorkoutConflict.class);
    assertThatThrownBy(() -> w.appendSet(entry(2))).isInstanceOf(WorkoutConflict.class);
    assertThatThrownBy(() -> w.removeSet(0)).isInstanceOf(WorkoutConflict.class);
  }
}
