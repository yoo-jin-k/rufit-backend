package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rufit.exercise.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PreviousExerciseSetsTests {

  static final UUID SQUAT = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final UUID BENCH = UUID.fromString("00000000-0000-4000-8000-000000000002");
  static final UUID DEADLIFT = UUID.fromString("00000000-0000-4000-8000-000000000003");

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutService workouts;

  @Autowired
  PreviousExerciseSets previous;

  @Autowired
  ExerciseService exercises;

  @Autowired
  JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from workouts");
    jdbc.update("delete from routines");
    jdbc.update("delete from exercises where owner_name is not null");
  }

  private WorkoutController.View finish(String owner, int weight, UUID... ids) {
    var w = workouts.start(owner);
    for (UUID id : ids)
      w = workouts.appendSet(
        w.id(),
        owner,
        new WorkoutController.SetEdit(
          w.version(),
          new WorkoutController.SetInput("Selected", BigDecimal.valueOf(weight), 5, id)
        )
      );
    for (int i = 0; i < ids.length; i++) w = workouts.complete(
      w.id(),
      owner,
      i,
      new WorkoutController.Completion(w.version(), true, 0)
    );
    return workouts.finish(w.id(), owner, w.version(), false);
  }

  @Test
  void latestCompletedSetsAreSelectedPerExerciseInRequestedOrderWithoutChangingActiveWorkout() {
    var older = finish("previous-alice", 10, SQUAT, BENCH, SQUAT);
    var newer = finish("previous-alice", 20, SQUAT, SQUAT);
    finish("previous-bob", 99, SQUAT, BENCH);
    var active = workouts.start("previous-alice");
    active = workouts.appendSet(
      active.id(),
      "previous-alice",
      new WorkoutController.SetEdit(
        active.version(),
        new WorkoutController.SetInput("Selected", BigDecimal.valueOf(88), 5, SQUAT)
      )
    );
    active = workouts.get(active.id(), "previous-alice");
    var result = previous.get("previous-alice", List.of(BENCH, SQUAT, DEADLIFT, SQUAT));
    assertThat(result.items())
      .extracting(PreviousExerciseSetsController.Item::exerciseId)
      .containsExactly(BENCH, SQUAT, DEADLIFT);
    assertThat(result.items().get(0).record().workoutId()).isEqualTo(older.id());
    assertThat(result.items().get(1).record().workoutId()).isEqualTo(newer.id());
    assertThat(result.items().get(1).record().sets())
      .hasSize(2)
      .allSatisfy(set -> assertThat(set.weight()).isEqualByComparingTo("20"));
    assertThat(result.items().get(2).record()).isNull();
    assertThat(workouts.get(active.id(), "previous-alice")).isEqualTo(active);
    workouts.deleteHistory(newer.id(), "previous-alice", newer.version());
    assertThat(
      previous.get("previous-alice", List.of(SQUAT)).items().getFirst().record().workoutId()
    ).isEqualTo(older.id());
  }

  @Test
  void archivedPersonalExerciseKeepsHistoricalNameAndPrivateIdsAreNotExposed() throws Exception {
    var exercise = exercises.create(
      "previous-alice",
      UUID.randomUUID(),
      new ExerciseController.Create("Original", Exercise.BodyPart.LEGS, Exercise.Equipment.OTHER)
    );
    finish("previous-alice", 10, exercise.id());
    exercise = exercises.update(
      exercise.id(),
      "previous-alice",
      new ExerciseController.Edit(
        exercise.version(),
        "Renamed",
        Exercise.BodyPart.LEGS,
        Exercise.Equipment.OTHER
      )
    );
    exercises.archive(exercise.id(), "previous-alice", exercise.version());
    var item = previous.get("previous-alice", List.of(exercise.id())).items().getFirst();
    assertThat(item.exerciseName()).isEqualTo("Renamed");
    assertThat(item.record().sets().getFirst().exerciseName()).isEqualTo("Original");
    mvc
      .perform(
        get("/api/v1/workouts/previous-sets")
          .param("exerciseIds", SQUAT + "," + exercise.id())
          .with(user("previous-bob"))
      )
      .andExpect(status().isNotFound());
  }

  @Test
  void apiValidatesBoundedIdsAndReturnsExplicitNullForNoHistory() throws Exception {
    mvc
      .perform(
        get("/api/v1/workouts/previous-sets")
          .param("exerciseIds", SQUAT.toString())
          .with(user("previous-alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items[0].exerciseId").value(SQUAT.toString()))
      .andExpect(jsonPath("$.items[0].record").isEmpty());
    mvc
      .perform(get("/api/v1/workouts/previous-sets").param("exerciseIds", SQUAT.toString()))
      .andExpect(status().isUnauthorized());
    for (String value : List.of(
      "",
      "not-a-uuid",
      String.join(",", Collections.nCopies(51, SQUAT.toString()))
    ))
      mvc
        .perform(
          get("/api/v1/workouts/previous-sets")
            .param("exerciseIds", value)
            .with(user("previous-alice"))
        )
        .andExpect(status().isBadRequest());
  }
}
