package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutHistoryDeletionTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @Autowired
  JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    repository.deleteAll();
    jdbc.update("delete from retired_workout_keys");
  }

  private Workout finished() {
    Workout w = new Workout(UUID.randomUUID(), "alice", Instant.EPOCH);
    w.appendSet(new Workout.Entry("스쿼트", BigDecimal.TEN, 10));
    w.completeSet(0, true, 0, Instant.EPOCH);
    w.finish(Instant.EPOCH.plusSeconds(60));
    return repository.saveAndFlush(w);
  }

  @Test
  void confirmedDeleteRemovesWorkoutAndSetsButPreventsCreationReplay() throws Exception {
    Workout workout = finished();
    String id = workout.id().toString();
    String body = "{\"version\":" + workout.version() + ",\"confirmed\":true}";
    mvc
      .perform(
        post("/api/v1/workouts/" + id + "/delete")
          .with(user("bob"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body)
      )
      .andExpect(status().isNotFound());
    mvc
      .perform(
        post("/api/v1/workouts/" + id + "/delete")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + workout.version() + ",\"confirmed\":false}")
      )
      .andExpect(status().isBadRequest());
    for (int i = 0; i < 2; i++) mvc
      .perform(
        post("/api/v1/workouts/" + id + "/delete")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body)
      )
      .andExpect(status().isNoContent());
    assertThat(repository.findById(workout.id())).isEmpty();
    assertThat(
      jdbc.queryForObject(
        "select count(*) from workout_entries where workout_id=?",
        Long.class,
        workout.id()
      )
    ).isZero();
    assertThat(
      jdbc.queryForObject(
        "select count(*) from completed_workout_sets where workout_id=?",
        Long.class,
        workout.id()
      )
    ).isZero();
    mvc.perform(get("/api/v1/workouts/" + id).with(user("alice"))).andExpect(status().isNotFound());
    mvc
      .perform(get("/api/v1/workouts").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.totalElements").value(0));
    mvc
      .perform(
        post("/api/v1/workouts").header("Idempotency-Key", id).with(user("alice")).with(csrf())
      )
      .andExpect(status().isGone())
      .andExpect(jsonPath("$.code").value("RESOURCE_DELETED"));
    mvc
      .perform(
        post("/api/v1/workouts").header("Idempotency-Key", id).with(user("bob")).with(csrf())
      )
      .andExpect(status().isConflict());
    mvc
      .perform(post("/api/v1/workouts").with(user("alice")).with(csrf()))
      .andExpect(status().isCreated());
  }

  @Test
  void activeAndStaleRecordsCannotBeDeleted() throws Exception {
    Workout active = repository.saveAndFlush(
      new Workout(UUID.randomUUID(), "alice", Instant.EPOCH)
    );
    mvc
      .perform(
        post("/api/v1/workouts/" + active.id() + "/delete")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + active.version() + ",\"confirmed\":true}")
      )
      .andExpect(status().isConflict());
    Workout finished = finished();
    mvc
      .perform(
        post("/api/v1/workouts/" + finished.id() + "/delete")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + (finished.version() + 1) + ",\"confirmed\":true}")
      )
      .andExpect(status().isConflict());
    assertThat(repository.count()).isEqualTo(2);
  }
}
