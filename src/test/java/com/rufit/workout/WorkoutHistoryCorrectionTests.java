package com.rufit.workout;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
class WorkoutHistoryCorrectionTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  private Workout save(boolean finish) {
    Workout w = new Workout(UUID.randomUUID(), "alice", Instant.parse("2026-09-01T00:00:00Z"));
    w.appendSet(new Workout.Entry("스쿼트", BigDecimal.TEN, 10));
    if (finish) {
      w.completeSet(0, true, 0, w.startedAt());
      w.finish(w.startedAt().plusSeconds(60));
    }
    return repository.saveAndFlush(w);
  }

  private String body(long version) {
    return (
      "{\"version\":" +
      version +
      ",\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":25.5,\"reps\":12},{\"exerciseName\":\"푸시업\",\"weight\":0,\"reps\":8}]}"
    );
  }

  @Test
  void correctedSetsUpdateHistoryAndCalendarWithoutChangingTimes() throws Exception {
    Workout w = save(true);
    mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/history")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body(w.version()))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.finishedAt").value(w.finishedAt().toString()))
      .andExpect(jsonPath("$.startedAt").value(w.startedAt().toString()))
      .andExpect(jsonPath("$.completedSetIndexes[0]").value(0))
      .andExpect(jsonPath("$.completedSetIndexes[1]").value(1));
    mvc
      .perform(
        get("/api/v1/workouts/exercise-history").param("exerciseName", "스쿼트").with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items[0].sets[0].weight").value(25.5));
    mvc
      .perform(get("/api/v1/workouts/calendar").param("month", "2026-09").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.completedSets").value(2))
      .andExpect(jsonPath("$.volumeKgReps").value(306))
      .andExpect(jsonPath("$.durationSeconds").value(60));
    mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/history")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body(w.version()))
      )
      .andExpect(status().isConflict());
  }

  @Test
  void ownershipActiveAndEmptyHistoryAreProtected() throws Exception {
    Workout w = save(true);
    mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/history")
          .with(user("bob"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body(w.version()))
      )
      .andExpect(status().isNotFound());
    mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/history")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + w.version() + ",\"sets\":[]}")
      )
      .andExpect(status().isBadRequest());
    Workout active = save(false);
    mvc
      .perform(
        put("/api/v1/workouts/" + active.id() + "/history")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body(active.version()))
      )
      .andExpect(status().isConflict());
  }
}
