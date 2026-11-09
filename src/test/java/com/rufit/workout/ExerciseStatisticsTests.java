package com.rufit.workout;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExerciseStatisticsTests {

  static final UUID SQUAT = UUID.fromString("00000000-0000-4000-8000-000000000001");

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  private Workout.Entry set(String name, int weight, int reps) {
    return new Workout.Entry(SQUAT, name, BigDecimal.valueOf(weight), reps);
  }

  private Workout save(String owner, int day, String state, List<Workout.Entry> sets) {
    Instant start = Instant.parse("2026-09-01T00:00:00Z").plusSeconds(day * 86400L);
    Workout w = new Workout(UUID.randomUUID(), owner, start);
    for (Workout.Entry entry : sets) w.appendSet(entry);
    for (int i = 0; i < sets.size(); i++) w.completeSet(i, true, 0, start);
    if (state.equals("finished")) w.finish(start.plusSeconds(600));
    if (state.equals("cancelled")) w.cancel(start.plusSeconds(600));
    return repository.saveAndFlush(w);
  }

  @Test
  void aggregatesCompletedOwnedSetsAcrossRenamesAndExcludesActiveCancelledAndOtherUsers()
    throws Exception {
    save(
      "alice",
      0,
      "finished",
      List.of(
        set("Old", 40, 10),
        set("Old", 40, 12),
        new Workout.Entry("Press", BigDecimal.valueOf(200), 2)
      )
    );
    Workout best = save("alice", 1, "finished", List.of(set("Renamed", 50, 3)));
    save("bob", 2, "finished", List.of(set("Old", 100, 30)));
    save("alice", 3, "cancelled", List.of(set("Old", 1000, 100)));
    save("alice", 4, "active", List.of(set("Old", 1000, 100)));
    mvc
      .perform(
        get("/api/v1/workouts/exercise-statistics")
          .param("exerciseId", SQUAT.toString())
          .with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.workouts").value(2))
      .andExpect(jsonPath("$.completedSets").value(3))
      .andExpect(jsonPath("$.totalReps").value(25))
      .andExpect(jsonPath("$.volumeKgReps").value(1030))
      .andExpect(jsonPath("$.heaviestSet.workoutId").value(best.id().toString()))
      .andExpect(jsonPath("$.heaviestSet.weight").value(50))
      .andExpect(jsonPath("$.lastPerformedAt").value(best.finishedAt().toString()));
    mvc
      .perform(
        get("/api/v1/workouts/exercise-statistics")
          .param("exerciseName", " Old ")
          .with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.workouts").value(1))
      .andExpect(jsonPath("$.volumeKgReps").value(880));
  }

  @Test
  void correctingAndDeletingHistoryRecalculatesBestSetWithDeterministicTieBreaking()
    throws Exception {
    Workout first = save(
      "alice",
      0,
      "finished",
      List.of(set("Squat", 40, 10), set("Squat", 40, 12))
    );
    Workout second = save("alice", 1, "finished", List.of(set("Squat", 50, 3)));
    String corrected = mvc
      .perform(
        put("/api/v1/workouts/" + second.id() + "/history")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"version\":" +
              second.version() +
              ",\"sets\":[{\"exerciseId\":\"" +
              SQUAT +
              "\",\"exerciseName\":\"Squat\",\"weight\":45,\"reps\":4}]}"
          )
      )
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    mvc
      .perform(
        get("/api/v1/workouts/exercise-statistics")
          .param("exerciseId", SQUAT.toString())
          .with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.volumeKgReps").value(1060))
      .andExpect(jsonPath("$.heaviestSet.weight").value(45));
    mvc
      .perform(
        post("/api/v1/workouts/" + second.id() + "/delete")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + JsonPath.read(corrected, "$.version") + ",\"confirmed\":true}")
      )
      .andExpect(status().isNoContent());
    mvc
      .perform(
        get("/api/v1/workouts/exercise-statistics")
          .param("exerciseId", SQUAT.toString())
          .with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.workouts").value(1))
      .andExpect(jsonPath("$.volumeKgReps").value(880))
      .andExpect(jsonPath("$.heaviestSet.workoutId").value(first.id().toString()))
      .andExpect(jsonPath("$.heaviestSet.setIndex").value(1))
      .andExpect(jsonPath("$.heaviestSet.reps").value(12));
  }

  @Test
  void zeroHistoryAndInvalidSelectorsAndPrivateExercisesAreHandled() throws Exception {
    mvc
      .perform(
        get("/api/v1/workouts/exercise-statistics")
          .param("exerciseName", "Unknown")
          .with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.workouts").value(0))
      .andExpect(jsonPath("$.volumeKgReps").value(0))
      .andExpect(jsonPath("$.heaviestSet").isEmpty())
      .andExpect(jsonPath("$.lastPerformedAt").isEmpty());
    mvc
      .perform(get("/api/v1/workouts/exercise-statistics").with(user("alice")))
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        get("/api/v1/workouts/exercise-statistics")
          .param("exerciseName", "Squat")
          .param("exerciseId", SQUAT.toString())
          .with(user("alice"))
      )
      .andExpect(status().isBadRequest());
    String privateExercise = mvc
      .perform(
        post("/api/v1/exercises")
          .with(user("bob"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"name\":\"Private " +
              UUID.randomUUID() +
              "\",\"bodyPart\":\"LEGS\",\"equipment\":\"OTHER\"}"
          )
      )
      .andExpect(status().isCreated())
      .andReturn()
      .getResponse()
      .getContentAsString();
    mvc
      .perform(
        get("/api/v1/workouts/exercise-statistics")
          .param("exerciseId", (String) JsonPath.read(privateExercise, "$.id"))
          .with(user("alice"))
      )
      .andExpect(status().isNotFound());
    mvc
      .perform(get("/api/v1/workouts/exercise-statistics").param("exerciseName", "Squat"))
      .andExpect(status().isUnauthorized());
  }
}
