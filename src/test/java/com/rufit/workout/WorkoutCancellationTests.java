package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;
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
class WorkoutCancellationTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  @Test
  void cancellationRequiresConfirmationAndAllowsANewWorkoutWithoutResurrectingRetries()
    throws Exception {
    String key = UUID.randomUUID().toString();
    mvc
      .perform(
        post("/api/v1/workouts").header("Idempotency-Key", key).with(user("alice")).with(csrf())
      )
      .andExpect(status().isCreated());
    for (String body : List.of("{\"version\":0}", "{\"version\":0,\"confirmed\":false}")) {
      mvc
        .perform(
          post("/api/v1/workouts/" + key + "/cancel")
            .with(user("alice"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
        )
        .andExpect(status().isBadRequest());
    }
    mvc
      .perform(
        post("/api/v1/workouts/" + key + "/cancel")
          .with(user("bob"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":0,\"confirmed\":true}")
      )
      .andExpect(status().isNotFound());
    String cancelled = mvc
      .perform(
        post("/api/v1/workouts/" + key + "/cancel")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":0,\"confirmed\":true}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
      .andExpect(jsonPath("$.finishedAt").isEmpty())
      .andReturn()
      .getResponse()
      .getContentAsString();
    mvc
      .perform(get("/api/v1/workouts/active").with(user("alice")))
      .andExpect(status().isNoContent());
    mvc
      .perform(get("/api/v1/workouts").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.totalElements").value(0));
    String fresh = mvc
      .perform(post("/api/v1/workouts").with(user("alice")).with(csrf()))
      .andExpect(status().isCreated())
      .andReturn()
      .getResponse()
      .getContentAsString();
    mvc
      .perform(
        post("/api/v1/workouts").header("Idempotency-Key", key).with(user("alice")).with(csrf())
      )
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
    mvc
      .perform(get("/api/v1/workouts/active").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.id").value((String) JsonPath.read(fresh, "$.id")));
    Number version = JsonPath.read(cancelled, "$.version");
    mvc
      .perform(
        post("/api/v1/workouts/" + key + "/sets")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"version\":" +
              version +
              ",\"set\":{\"exerciseName\":\"스쿼트\",\"weight\":0,\"reps\":10}}"
          )
      )
      .andExpect(status().isConflict());
  }

  @Test
  void cancellationPreservesDraftButClearsRestAndRejectsTerminalChanges() {
    Workout w = new Workout(UUID.randomUUID(), "domain", Instant.EPOCH);
    w.appendSet(new Workout.Entry("스쿼트", BigDecimal.TEN, 10));
    w.completeSet(0, true, 90, Instant.EPOCH);
    w.cancel(Instant.EPOCH.plusSeconds(10));
    assertThat(w.restEndsAt()).isNull();
    assertThat(w.entries()).hasSize(1);
    assertThatThrownBy(() -> w.finish(Instant.EPOCH.plusSeconds(20))).isInstanceOf(
      WorkoutConflict.class
    );
    assertThatThrownBy(() -> w.cancel(Instant.EPOCH.plusSeconds(20))).isInstanceOf(
      WorkoutConflict.class
    );
    assertThatThrownBy(() -> w.skipRest()).isInstanceOf(WorkoutConflict.class);
  }
}
