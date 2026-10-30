package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SetDuplicationDomainTests {

  private Workout workout() {
    return Workout.fromRoutine(
      UUID.randomUUID(),
      "alice",
      Instant.EPOCH,
      UUID.randomUUID(),
      List.of(
        new Workout.Entry("스쿼트", BigDecimal.ONE, 2),
        new Workout.Entry(UUID.randomUUID(), "덤벨 컬", new BigDecimal("5.25"), 10),
        new Workout.Entry("벤치프레스", BigDecimal.TEN, 8)
      )
    );
  }

  @Test
  void copiesIndependentSnapshotAndKeepsPausedClockNoteAndRoutineSource() {
    Workout w = workout();
    UUID routineId = w.sourceRoutineId();
    w.updateNote("세트 복제 시험");
    w.pauseTimer(Instant.EPOCH.plusSeconds(1));
    w.resumeTimer(Instant.EPOCH.plusSeconds(4));
    w.pauseTimer(Instant.EPOCH.plusSeconds(10));
    Workout.Entry source = w.entries().get(1);
    w.duplicateSet(1);
    assertThat(w.entries()).extracting(Workout.Entry::reps).containsExactly(2, 10, 10, 8);
    assertThat(w.entries().get(2)).usingRecursiveComparison().isEqualTo(source);
    assertThat(w.entries().get(2)).isNotSameAs(source);
    assertThat(w.completedSetIndexes()).isEmpty();
    assertThat(w.restEndsAt()).isNull();
    assertThat(w.pausedAt()).isEqualTo(Instant.EPOCH.plusSeconds(10));
    assertThat(w.pausedDurationMs()).isEqualTo(3000);
    assertThat(w.elapsedMillis(Instant.EPOCH.plusSeconds(100))).isEqualTo(7000);
    assertThat(w.sourceRoutineId()).isEqualTo(routineId);
    assertThat(w.note()).isEqualTo("세트 복제 시험");
    w.editSet(2, new Workout.Entry("새 종목", BigDecimal.ZERO, 1));
    assertThat(w.entries().get(1)).isSameAs(source);
  }

  @Test
  void insertingBeforeAtOrAfterRestTargetKeepsOriginalCompletionsAndDeletionReversesInsertion() {
    for (int source : List.of(0, 1, 2))
      for (int rest : List.of(0, 2)) {
        Workout w = workout();
        List<Workout.Entry> original = w.entries();
        w.completeSet(0, true, 90, Instant.EPOCH);
        w.completeSet(2, true, 90, Instant.EPOCH);
        if (rest == 0) {
          w.completeSet(0, false, 0, Instant.EPOCH);
          w.completeSet(0, true, 90, Instant.EPOCH);
        }
        w.duplicateSet(source);
        assertThat(
          w
            .completedSetIndexes()
            .stream()
            .map(i -> w.entries().get(i).reps())
        ).containsExactly(2, 8);
        assertThat(w.completedSetIndexes()).doesNotContain(source + 1);
        assertThat(w.entries().get(w.restSetIndex()).reps()).isEqualTo(original.get(rest).reps());
        assertThat(w.restEndsAt()).isEqualTo(Instant.EPOCH.plusSeconds(90));
        w.removeSet(source + 1);
        assertThat(w.entries()).containsExactlyElementsOf(original);
        assertThat(w.completedSetIndexes()).containsExactly(0, 2);
        assertThat(w.restSetIndex()).isEqualTo(rest);
      }
  }

  @Test
  void rejectsInvalidIndexesCapacityAndClosedWorkoutsWithoutChangingSets() {
    Workout w = workout();
    for (int index : List.of(-1, 3, Integer.MAX_VALUE))
      assertThatThrownBy(() -> w.duplicateSet(index)).isInstanceOf(IllegalArgumentException.class);
    assertThat(w.entries()).hasSize(3);
    w.replaceEntries(Collections.nCopies(299, new Workout.Entry("스쿼트", BigDecimal.ONE, 2)));
    w.duplicateSet(298);
    assertThat(w.entries()).hasSize(300);
    assertThatThrownBy(() -> w.duplicateSet(0)).isInstanceOf(IllegalArgumentException.class);
    assertThat(w.entries()).hasSize(300);
    w.completeSet(0, true, 0, Instant.EPOCH);
    w.finish(Instant.EPOCH.plusSeconds(1), true);
    assertThatThrownBy(() -> w.duplicateSet(0)).isInstanceOf(WorkoutConflict.class);
    Workout cancelled = workout();
    cancelled.cancel(Instant.EPOCH.plusSeconds(1));
    assertThatThrownBy(() -> cancelled.duplicateSet(0)).isInstanceOf(WorkoutConflict.class);
  }
}
