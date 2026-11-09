package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class HistoryVolumeTests {

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  WorkoutService workouts;

  @Autowired
  WorkoutCalendar calendar;

  @Autowired
  ExerciseStatistics statistics;

  @Autowired
  PreviousExerciseSets previous;

  final String owner = "history-volume-" + UUID.randomUUID();
  final String other = owner + "-other";
  static final UUID EXERCISE = UUID.fromString("00000000-0000-4000-8000-000000000001");

  @BeforeEach
  void populate() {
    for (var population : Map.of(owner, 1000, other, 9000).entrySet()) {
      jdbc.update(
        """
        insert into workouts(id,owner_name,started_at,finished_at)
        select md5(? || ':' || n)::uuid,?,
            timestamptz '2026-01-01 12:00:00Z'+(n%100)*interval '1 day',
            timestamptz '2026-01-01 13:00:00Z'+(n%100)*interval '1 day'
        from generate_series(0,?-1) n
        """,
        population.getKey(),
        population.getKey(),
        population.getValue()
      );
    }
    // This synthetic bulk load needs current planner statistics before joined reads.
    jdbc.execute("analyze workouts");
    jdbc.update(
      """
      insert into workout_entries(workout_id,entry_order,exercise_id,exercise_name,weight,reps)
      select w.id,n,?,'Squat',50,10 from workouts w cross join generate_series(0,19) n
      where w.owner_name in (?,?)
      """,
      EXERCISE,
      owner,
      other
    );
    jdbc.update(
      """
      insert into completed_workout_sets(workout_id,set_index)
      select w.id,n from workouts w cross join generate_series(0,19) n
      where w.owner_name in (?,?)
      """,
      owner,
      other
    );
    jdbc.execute("analyze workout_entries");
    jdbc.execute("analyze completed_workout_sets");
  }

  @AfterEach
  void clean() {
    jdbc.update("delete from workouts where owner_name in (?,?)", owner, other);
  }

  @Test
  void tenThousandRecordsKeepPaginationAndAggregatesIsolatedAndComplete() {
    var expected = jdbc.queryForList(
      "select id from workouts where owner_name=? order by finished_at desc,id desc",
      UUID.class,
      owner
    );
    List<UUID> actual = new ArrayList<>();
    for (int page = 0; page < 10; page++) {
      var result = workouts.history(owner, page, 100);
      assertThat(result.totalElements()).isEqualTo(1000);
      assertThat(result.hasNext()).isEqualTo(page < 9);
      assertThat(result.items()).hasSize(100);
      actual.addAll(result.items().stream().map(WorkoutController.View::id).toList());
      assertThat(result.items()).allSatisfy(w -> {
        assertThat(w.sets()).hasSize(20);
        assertThat(w.completedSetIndexes()).hasSize(20);
      });
    }
    assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    assertThat(workouts.history(owner, 10, 100).items()).isEmpty();
    var daily = workouts.history(owner, 0, 100, "2026-01-01", "UTC");
    assertThat(daily.totalElements()).isEqualTo(10);
    assertThat(daily.items()).hasSize(10);
    var month = calendar.month(owner, "2026-01", "UTC");
    assertThat(month.workouts()).isEqualTo(310);
    assertThat(month.workoutDays()).isEqualTo(31);
    assertThat(month.completedSets()).isEqualTo(6200);
    assertThat(month.volumeKgReps()).isEqualByComparingTo("3100000");
    assertThat(month.durationSeconds()).isEqualTo(310L * 3600);
    var stats = statistics.get(owner, null, EXERCISE);
    assertThat(stats.workouts()).isEqualTo(1000);
    assertThat(stats.completedSets()).isEqualTo(20000);
    assertThat(stats.totalReps()).isEqualTo(200000);
    assertThat(stats.volumeKgReps()).isEqualByComparingTo("10000000");
    var latest = previous.get(owner, List.of(EXERCISE)).items().getFirst().record();
    assertThat(latest.workoutId()).isEqualTo(expected.getFirst());
    assertThat(latest.sets()).hasSize(20);
    var history = workouts.exerciseHistory(owner, null, EXERCISE, 19, 50);
    assertThat(history.totalElements()).isEqualTo(1000);
    assertThat(history.hasNext()).isFalse();
    assertThat(history.items())
      .extracting(WorkoutController.ExerciseRecord::workoutId)
      .containsExactlyElementsOf(expected.subList(950, 1000));
  }
}
