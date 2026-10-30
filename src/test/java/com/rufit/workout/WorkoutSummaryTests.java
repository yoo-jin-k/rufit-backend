package com.rufit.workout;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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
class WorkoutSummaryTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  @Test
  void completedSummaryCountsBodyweightAndGroupsRepeatedExerciseWithoutDuplicatingDuration()
    throws Exception {
    Instant start = Instant.parse("2026-09-01T00:00:00Z");
    UUID squat = UUID.fromString("00000000-0000-4000-8000-000000000001");
    Workout w = new Workout(UUID.randomUUID(), "alice", start);
    w.appendSet(new Workout.Entry(squat, "Squat", new BigDecimal("40.5"), 10));
    w.appendSet(new Workout.Entry("Pushup", BigDecimal.ZERO, 12));
    w.appendSet(new Workout.Entry(squat, "Squat", new BigDecimal("45"), 8));
    w.appendSet(new Workout.Entry("Unfinished", BigDecimal.TEN, 5));
    for (int i = 0; i < 3; i++) w.completeSet(i, true, 0, start);
    w.finish(start.plusSeconds(600), true);
    repository.saveAndFlush(w);
    mvc
      .perform(get("/api/v1/workouts/" + w.id() + "/summary").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.durationSeconds").value(600))
      .andExpect(jsonPath("$.completedSets").value(3))
      .andExpect(jsonPath("$.totalReps").value(30))
      .andExpect(jsonPath("$.volumeKgReps").value(765))
      .andExpect(jsonPath("$.exercises.length()").value(2))
      .andExpect(jsonPath("$.exercises[0].completedSets").value(2))
      .andExpect(jsonPath("$.exercises[0].heaviestWeight").value(45))
      .andExpect(jsonPath("$.exercises[1].volumeKgReps").value(0));
    mvc
      .perform(get("/api/v1/workouts/" + w.id() + "/summary").with(user("bob")))
      .andExpect(status().isNotFound());
    String corrected = mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/history")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"version\":" +
              w.version() +
              ",\"sets\":[{\"exerciseId\":\"" +
              squat +
              "\",\"exerciseName\":\"Squat\",\"weight\":80,\"reps\":5}]}"
          )
      )
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    mvc
      .perform(get("/api/v1/workouts/" + w.id() + "/summary").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.completedSets").value(1))
      .andExpect(jsonPath("$.volumeKgReps").value(400))
      .andExpect(jsonPath("$.durationSeconds").value(600));
    mvc
      .perform(
        post("/api/v1/workouts/" + w.id() + "/delete")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + JsonPath.read(corrected, "$.version") + ",\"confirmed\":true}")
      )
      .andExpect(status().isNoContent());
    mvc
      .perform(get("/api/v1/workouts/" + w.id() + "/summary").with(user("alice")))
      .andExpect(status().isNotFound());
  }

  @Test
  void activeAndCancelledWorkoutsDoNotHaveCompletedResults() throws Exception {
    Workout w = repository.saveAndFlush(new Workout(UUID.randomUUID(), "alice", Instant.now()));
    mvc
      .perform(get("/api/v1/workouts/" + w.id() + "/summary").with(user("alice")))
      .andExpect(status().isConflict());
    mvc
      .perform(
        post("/api/v1/workouts/" + w.id() + "/cancel")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + w.version() + ",\"confirmed\":true}")
      )
      .andExpect(status().isOk());
    mvc
      .perform(get("/api/v1/workouts/" + w.id() + "/summary").with(user("alice")))
      .andExpect(status().isConflict());
    mvc
      .perform(get("/api/v1/workouts/" + w.id() + "/summary"))
      .andExpect(status().isUnauthorized());
  }
}
