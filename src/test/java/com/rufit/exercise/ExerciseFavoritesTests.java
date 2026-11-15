package com.rufit.exercise;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
class ExerciseFavoritesTests {

  static final String SQUAT = "00000000-0000-4000-8000-000000000001";
  static final String BENCH = "00000000-0000-4000-8000-000000000002";

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from exercise_favorites");
  }

  @Test
  void addIsIdempotentListIsNewestFirstAndRemoveIsIdempotent() throws Exception {
    mvc.perform(put("/api/v1/exercises/" + SQUAT + "/favorite").with(user("fav-alice")).with(csrf()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.exerciseId").value(SQUAT));
    mvc.perform(put("/api/v1/exercises/" + SQUAT + "/favorite").with(user("fav-alice")).with(csrf()))
      .andExpect(status().isOk());
    Thread.sleep(5);
    mvc.perform(put("/api/v1/exercises/" + BENCH + "/favorite").with(user("fav-alice")).with(csrf()))
      .andExpect(status().isOk());
    mvc.perform(get("/api/v1/exercises/favorites").with(user("fav-alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.favorites.length()").value(2))
      .andExpect(jsonPath("$.favorites[0].exerciseId").value(BENCH))
      .andExpect(jsonPath("$.favorites[1].exerciseId").value(SQUAT));
    mvc.perform(get("/api/v1/exercises/favorites").with(user("fav-bob")))
      .andExpect(jsonPath("$.favorites.length()").value(0));
    mvc.perform(delete("/api/v1/exercises/" + SQUAT + "/favorite").with(user("fav-alice")).with(csrf()))
      .andExpect(status().isNoContent());
    mvc.perform(delete("/api/v1/exercises/" + SQUAT + "/favorite").with(user("fav-alice")).with(csrf()))
      .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/exercises/favorites").with(user("fav-alice")))
      .andExpect(jsonPath("$.favorites.length()").value(1));
  }

  @Test
  void unknownExerciseIsNotFound() throws Exception {
    mvc.perform(put("/api/v1/exercises/" + UUID.randomUUID() + "/favorite").with(user("fav-alice")).with(csrf()))
      .andExpect(status().isNotFound());
  }
}
