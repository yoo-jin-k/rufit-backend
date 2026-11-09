package com.rufit.routine;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
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
class RoutineApiTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from workout_entries");
    jdbc.update("delete from workouts");
    jdbc.update("delete from routine_entries");
    jdbc.update("delete from routines");
  }

  private String create() throws Exception {
    String json = mvc
      .perform(
        post("/api/v1/routines")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"title\":\"하체\",\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":40,\"reps\":12}]}"
          )
      )
      .andExpect(status().isCreated())
      .andReturn()
      .getResponse()
      .getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  @Test
  void routineStartsIndependentWorkout() throws Exception {
    String id = create();
    Number revision = JsonPath.read(
      mvc
        .perform(get("/api/v1/routines/" + id).with(user("alice")))
        .andReturn()
        .getResponse()
        .getContentAsString(),
      "$.version"
    );
    mvc
      .perform(post("/api/v1/routines/" + id + "/start").with(user("alice")).with(csrf()))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.sets[0].weight").value(40));
    mvc
      .perform(
        put("/api/v1/routines/" + id)
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"version\":" +
              revision +
              ",\"title\":\"수정\",\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":50,\"reps\":8}]}"
          )
      )
      .andExpect(status().isOk());
    org.assertj.core.api.Assertions.assertThat(
      jdbc.queryForObject("select weight from workout_entries", java.math.BigDecimal.class)
    ).isEqualByComparingTo("40");
    mvc
      .perform(post("/api/v1/routines/" + id + "/start").with(user("alice")).with(csrf()))
      .andExpect(status().isConflict());
  }

  @Test
  void ownershipAndValidation() throws Exception {
    String id = create();
    mvc
      .perform(get("/api/v1/routines").with(user("bob")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/v1/routines/" + id).with(user("bob"))).andExpect(status().isNotFound());
    mvc
      .perform(post("/api/v1/routines/" + id + "/start").with(user("bob")).with(csrf()))
      .andExpect(status().isNotFound());
    mvc
      .perform(
        post("/api/v1/routines")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"title\":\" \",\"sets\":[]}")
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  void retryKeepsEditedWorkoutAndDoesNotCopyChangedRoutineAgain() throws Exception {
    String id = create();
    String key = java.util.UUID.randomUUID().toString();
    String started = mvc
      .perform(
        post("/api/v1/routines/" + id + "/start")
          .header("Idempotency-Key", key)
          .with(user("alice"))
          .with(csrf())
      )
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.sourceRoutineId").value(id))
      .andReturn()
      .getResponse()
      .getContentAsString();
    Number version = JsonPath.read(started, "$.version");
    mvc
      .perform(
        put("/api/v1/workouts/" + key + "/sets/0")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"version\":" +
              version +
              ",\"set\":{\"exerciseName\":\"스쿼트\",\"weight\":55,\"reps\":8}}"
          )
      )
      .andExpect(status().isOk());
    Number revision = JsonPath.read(
      mvc
        .perform(get("/api/v1/routines/" + id).with(user("alice")))
        .andReturn()
        .getResponse()
        .getContentAsString(),
      "$.version"
    );
    mvc
      .perform(
        put("/api/v1/routines/" + id)
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"version\":" +
              revision +
              ",\"title\":\"변경\",\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":70,\"reps\":5}]}"
          )
      )
      .andExpect(status().isOk());
    mvc
      .perform(
        post("/api/v1/routines/" + id + "/start")
          .header("Idempotency-Key", key)
          .with(user("alice"))
          .with(csrf())
      )
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.id").value(key))
      .andExpect(jsonPath("$.sets[0].weight").value(55));
    mvc
      .perform(
        post("/api/v1/workouts").header("Idempotency-Key", key).with(user("alice")).with(csrf())
      )
      .andExpect(status().isConflict());
    String other = create();
    mvc
      .perform(
        post("/api/v1/routines/" + other + "/start")
          .header("Idempotency-Key", key)
          .with(user("alice"))
          .with(csrf())
      )
      .andExpect(status().isConflict());
  }
}
