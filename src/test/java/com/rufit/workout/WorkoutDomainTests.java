package com.rufit.workout;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class WorkoutDomainTests {
    @Test void completeWorkoutAndPreventFurtherEditing() {
        Workout w = new Workout(UUID.randomUUID(), "alice", Instant.EPOCH);
        w.replaceEntries(List.of(new Workout.Entry("푸시업", BigDecimal.ZERO, 10)));
        w.finish(Instant.EPOCH.plusSeconds(60));
        assertThat(w.finishedAt()).isEqualTo(Instant.EPOCH.plusSeconds(60));
        assertThatThrownBy(() -> w.replaceEntries(List.of())).isInstanceOf(WorkoutConflict.class);
        assertThatThrownBy(() -> w.finish(Instant.now())).isInstanceOf(WorkoutConflict.class);
    }
    @Test void rejectsEmptyWorkoutAndInvalidInputs() {
        Workout w = new Workout(UUID.randomUUID(), "alice", Instant.EPOCH);
        assertThatThrownBy(() -> w.finish(Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Workout.Entry(" ", BigDecimal.ONE, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Workout.Entry("스쿼트", new BigDecimal("-1"), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Workout.Entry("스쿼트", new BigDecimal("1.001"), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Workout.Entry("스쿼트", BigDecimal.ONE, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
