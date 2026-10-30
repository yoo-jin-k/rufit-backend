package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkoutPauseDomainTests {

  private final Instant start = Instant.parse("2026-09-01T00:00:00Z");

  private Workout workout() {
    Workout w = new Workout(UUID.randomUUID(), "alice", start);
    w.appendSet(new Workout.Entry("스쿼트", BigDecimal.ONE, 2));
    w.completeSet(0, true, 90, start);
    return w;
  }

  @Test
  void elapsedTimeContinuesWithoutAnyClientActivityUntilExplicitPause() {
    Workout w = workout();
    assertThat(w.elapsedMillis(start.plusSeconds(43200))).isEqualTo(43200000);
    w.pauseTimer(start.plusSeconds(43200));
    assertThat(w.elapsedMillis(start.plusSeconds(86400))).isEqualTo(43200000);
    assertThat(w.restEndsAt()).isEqualTo(start.plusSeconds(90));
    w.pauseTimer(start.plusSeconds(50000));
    assertThat(w.pausedAt()).isEqualTo(start.plusSeconds(43200));
  }

  @Test
  void repeatedPauseResumeAccumulatesOnlyPausedIntervalsAndFinishFreezesResult() {
    Workout w = workout();
    w.pauseTimer(start.plusSeconds(10));
    w.resumeTimer(start.plusSeconds(40));
    w.resumeTimer(start.plusSeconds(50));
    assertThat(w.pausedDurationMs()).isEqualTo(30000);
    w.pauseTimer(start.plusSeconds(60));
    w.finish(start.plusSeconds(100));
    assertThat(w.pausedAt()).isNull();
    assertThat(w.pausedDurationMs()).isEqualTo(70000);
    assertThat(w.elapsedMillis(start.plusSeconds(999))).isEqualTo(30000);
    assertThatThrownBy(() -> w.pauseTimer(start.plusSeconds(101))).isInstanceOf(
      WorkoutConflict.class
    );
    assertThatThrownBy(() -> w.resumeTimer(start.plusSeconds(101))).isInstanceOf(
      WorkoutConflict.class
    );
  }

  @Test
  void reversedClockCannotChangePausedWorkoutOrDiscardIncompleteSets() {
    Workout w = workout();
    w.appendSet(new Workout.Entry("푸시업", BigDecimal.ZERO, 5));
    w.pauseTimer(start.plusSeconds(60));
    assertThatThrownBy(() -> w.resumeTimer(start.plusSeconds(59))).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThatThrownBy(() -> w.finish(start.plusSeconds(59), true)).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThat(w.entries()).hasSize(2);
    assertThat(w.pausedDurationMs()).isZero();
    w.cancel(start.plusSeconds(120));
    assertThat(w.elapsedMillis(start.plusSeconds(999))).isEqualTo(60000);
    assertThat(w.pausedAt()).isNull();
  }
}
