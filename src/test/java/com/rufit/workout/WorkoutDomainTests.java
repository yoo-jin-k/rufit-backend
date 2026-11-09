package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkoutDomainTests {

  @Test
  void completeWorkoutAndPreventFurtherEditing() {
    Workout w = new Workout(UUID.randomUUID(), "alice", Instant.EPOCH);
    w.replaceEntries(List.of(new Workout.Entry("푸시업", BigDecimal.ZERO, 10)));
    w.completeSet(0, true, 0, Instant.EPOCH);
    w.finish(Instant.EPOCH.plusSeconds(60));
    assertThat(w.finishedAt()).isEqualTo(Instant.EPOCH.plusSeconds(60));
    assertThatThrownBy(() -> w.replaceEntries(List.of())).isInstanceOf(WorkoutConflict.class);
    assertThatThrownBy(() -> w.finish(Instant.now())).isInstanceOf(WorkoutConflict.class);
  }

  @Test
  void completionTracksAbsoluteRestAndRepeatedRequestsDoNotRestartTimer() {
    Workout w = new Workout(UUID.randomUUID(), "alice", Instant.EPOCH);
    w.replaceEntries(List.of(new Workout.Entry("스쿼트", BigDecimal.ONE, 10)));
    w.completeSet(0, true, 60, Instant.EPOCH);
    assertThat(w.restEndsAt()).isEqualTo(Instant.EPOCH.plusSeconds(60));
    w.completeSet(0, true, 60, Instant.EPOCH.plusSeconds(30));
    assertThat(w.restEndsAt()).isEqualTo(Instant.EPOCH.plusSeconds(60));
    assertThat(w.completedSetIndexes()).containsExactly(0);
    w.completeSet(0, false, 60, Instant.EPOCH.plusSeconds(40));
    assertThat(w.restEndsAt()).isNull();
    assertThat(w.completedSetIndexes()).isEmpty();
    assertThatThrownBy(() -> w.completeSet(1, true, 60, Instant.now())).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThatThrownBy(() -> w.completeSet(0, true, 3601, Instant.now())).isInstanceOf(
      IllegalArgumentException.class
    );
  }

  @Test
  void replacingSetsClearsCompletionAndRest() {
    Workout w = new Workout(UUID.randomUUID(), "alice", Instant.EPOCH);
    w.replaceEntries(List.of(new Workout.Entry("스쿼트", BigDecimal.ONE, 10)));
    w.completeSet(0, true, 60, Instant.EPOCH);
    w.replaceEntries(List.of(new Workout.Entry("푸시업", BigDecimal.ZERO, 10)));
    assertThat(w.completedSetIndexes()).isEmpty();
    assertThat(w.restEndsAt()).isNull();
  }

  @Test
  void rejectsEmptyWorkoutAndInvalidInputs() {
    Workout w = new Workout(UUID.randomUUID(), "alice", Instant.EPOCH);
    assertThatThrownBy(() -> w.finish(Instant.now())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Workout.Entry(" ", BigDecimal.ONE, 1)).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThatThrownBy(() -> new Workout.Entry("스쿼트", new BigDecimal("-1"), 1)).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThatThrownBy(() -> new Workout.Entry("스쿼트", new BigDecimal("1.001"), 1)).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThatThrownBy(() -> new Workout.Entry("스쿼트", BigDecimal.ONE, 0)).isInstanceOf(
      IllegalArgumentException.class
    );
  }
}
