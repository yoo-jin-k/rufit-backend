package com.rufit.profile;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rufit.workout.WorkoutController;
import com.rufit.workout.WorkoutService;
import java.math.BigDecimal;
import java.util.UUID;
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
class RestPreferencesTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  WorkoutService workouts;

  @Autowired
  RestPreferencesService preferences;

  @BeforeEach
  void clean() {
    jdbc.update("delete from rest_preferences");
  }

  @Test
  void readsDoNotCreateRowsAndOwnersHaveIndependentVersionedPreferences() throws Exception {
    mvc
      .perform(get("/api/v1/preferences/rest").with(user("rest-alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(0))
      .andExpect(jsonPath("$.defaultRestSeconds").value(90));
    assertThat(jdbc.queryForObject("select count(*) from rest_preferences", Long.class)).isZero();
    mvc
      .perform(
        put("/api/v1/preferences/rest")
          .with(user("rest-alice"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"version\":0,\"defaultRestSeconds\":0,\"ownerName\":\"rest-bob\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(1))
      .andExpect(jsonPath("$.defaultRestSeconds").value(0));
    mvc
      .perform(get("/api/v1/preferences/rest").with(user("rest-bob")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(0))
      .andExpect(jsonPath("$.defaultRestSeconds").value(90));
    mvc
      .perform(
        put("/api/v1/preferences/rest")
          .with(user("rest-alice"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"version\":1,\"defaultRestSeconds\":3600}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(2));
    for (int stale : new int[] { 0, 1 })
      mvc
        .perform(
          put("/api/v1/preferences/rest")
            .with(user("rest-alice"))
            .with(csrf())
            .contentType("application/json")
            .content("{\"version\":" + stale + ",\"defaultRestSeconds\":30}")
        )
        .andExpect(status().isConflict());
    assertThat(preferences.get("rest-alice")).isEqualTo(
      new RestPreferencesController.View(2, 3600)
    );
    mvc
      .perform(
        put("/api/v1/preferences/rest")
          .with(user("rest-bob"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"version\":2,\"defaultRestSeconds\":30}")
      )
      .andExpect(status().isConflict());
  }

  @Test
  void invalidAndUnauthenticatedRequestsDoNotSaveSettings() throws Exception {
    mvc.perform(get("/api/v1/preferences/rest")).andExpect(status().isUnauthorized());
    for (String body : new String[] {
      "{}",
      "{\"version\":0}",
      "{\"version\":0,\"defaultRestSeconds\":null}",
      "{\"version\":0,\"defaultRestSeconds\":-1}",
      "{\"version\":0,\"defaultRestSeconds\":3601}",
      "{\"version\":0,\"defaultRestSeconds\":1.5}",
      "{\"version\":0,\"defaultRestSeconds\":\"90\"}",
    })
      mvc
        .perform(
          put("/api/v1/preferences/rest")
            .with(user("rest-alice"))
            .with(csrf())
            .contentType("application/json")
            .content(body)
        )
        .andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("select count(*) from rest_preferences", Long.class)).isZero();
  }

  @Test
  void changingPreferenceDoesNotRewriteAnAlreadyRunningRestOrWorkoutVersion() {
    String owner = "rest-" + UUID.randomUUID();
    var w = workouts.start(owner);
    w = workouts.appendSet(
      w.id(),
      owner,
      new WorkoutController.SetEdit(
        w.version(),
        new WorkoutController.SetInput("Squat", BigDecimal.TEN, 5)
      )
    );
    w = workouts.complete(
      w.id(),
      owner,
      0,
      new WorkoutController.Completion(w.version(), true, 90)
    );
    preferences.save(owner, new RestPreferencesController.Edit(0L, 180));
    assertThat(workouts.get(w.id(), owner)).isEqualTo(w);
  }
}
