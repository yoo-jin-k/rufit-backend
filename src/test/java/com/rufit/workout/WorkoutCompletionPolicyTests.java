package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkoutCompletionPolicyTests {

  @Test
  void finishRequestCanOmitDiscardChoice() throws Exception {
    var mapper = new tools.jackson.databind.ObjectMapper();
    var request = mapper.readValue("{\"version\":1}", WorkoutController.FinishRequest.class);
    assertThat(Boolean.TRUE.equals(request.discardIncomplete())).isFalse();
  }

  private Workout workout() {
    Workout w = new Workout(UUID.randomUUID(), "policy-test", Instant.EPOCH);
    w.replaceEntries(
      List.of(
        new Workout.Entry("스쿼트", BigDecimal.TEN, 10),
        new Workout.Entry("스쿼트", BigDecimal.TEN, 8)
      )
    );
    return w;
  }

  @Test
  void enteredButUncompletedSetsCannotFinishWorkout() {
    Workout w = workout();
    assertThatThrownBy(() -> w.finish(Instant.EPOCH.plusSeconds(60))).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThat(w.finishedAt()).isNull();
  }

  @Test
  void incompleteSetsNeedAnExplicitDecisionBeforeFinishing() {
    Workout w = workout();
    w.completeSet(0, true, 30, Instant.EPOCH);
    assertThatThrownBy(() -> w.finish(Instant.EPOCH.plusSeconds(60))).isInstanceOf(
      WorkoutConflict.class
    );
    assertThat(w.entries()).hasSize(2);
    assertThat(w.finishedAt()).isNull();
  }

  @Test
  void explicitDiscardKeepsOnlyCompletedSetsInOriginalOrder() {
    Workout w = workout();
    w.completeSet(1, true, 90, Instant.EPOCH);
    w.finish(Instant.EPOCH.plusSeconds(60), true);
    assertThat(w.entries()).hasSize(1);
    assertThat(w.entries().getFirst().reps()).isEqualTo(8);
    assertThat(w.completedSetIndexes()).containsExactly(0);
    assertThat(w.restEndsAt()).isNull();
  }

  @Test
  void discardDoesNotAllowAnEmptyCompletedWorkout() {
    Workout w = workout();
    assertThatThrownBy(() -> w.finish(Instant.EPOCH.plusSeconds(60), true)).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThat(w.entries()).hasSize(2);
  }
}
