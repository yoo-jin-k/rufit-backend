package com.rufit.workout;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
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
class ArchivedCatalogHistoryTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutService workouts;

  @Autowired
  JdbcTemplate jdbc;

  UUID exerciseId;
  static final String OWNER = "archived-catalog-alice";

  @BeforeEach
  void prepare() {
    jdbc.update("delete from workouts");
    jdbc.update("delete from routines");
    exerciseId = UUID.randomUUID();
    jdbc.update(
      "insert into exercises(id,name,body_part,equipment) values (?,?,'LEGS','OTHER')",
      exerciseId,
      "Retired catalog fixture"
    );
  }

  @AfterEach
  void clean() {
    jdbc.update("delete from workouts where owner_name=?", OWNER);
    jdbc.update("delete from exercises where id=?", exerciseId);
  }

  @Test
  void retiringSharedExercisePreservesReadAccessAndHistoryButPreventsNewSelection()
    throws Exception {
    var w = workouts.start(OWNER);
    w = workouts.appendSet(
      w.id(),
      OWNER,
      new WorkoutController.SetEdit(
        w.version(),
        new WorkoutController.SetInput("Selected", BigDecimal.TEN, 5, exerciseId)
      )
    );
    w = workouts.complete(w.id(), OWNER, 0, new WorkoutController.Completion(w.version(), true, 0));
    w = workouts.finish(w.id(), OWNER, w.version(), false);
    jdbc.update("update exercises set archived_at=now(),version=version+1 where id=?", exerciseId);

    mvc
      .perform(get("/api/v1/exercises").param("query", "Retired catalog fixture").with(user(OWNER)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.totalElements").value(0));
    mvc
      .perform(
        get("/api/v1/exercises")
          .param("query", "Retired catalog fixture")
          .param("includeArchived", "true")
          .with(user(OWNER))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items[0].id").value(exerciseId.toString()));
    mvc
      .perform(get("/api/v1/exercises/" + exerciseId).with(user(OWNER)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.archivedAt").isNotEmpty());
    mvc
      .perform(
        get("/api/v1/workouts/exercise-history")
          .param("exerciseId", exerciseId.toString())
          .with(user(OWNER))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items[0].workoutId").value(w.id().toString()));
    mvc
      .perform(
        get("/api/v1/workouts/exercise-statistics")
          .param("exerciseId", exerciseId.toString())
          .with(user(OWNER))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.completedSets").value(1));
    mvc
      .perform(
        get("/api/v1/workouts/previous-sets")
          .param("exerciseIds", exerciseId.toString())
          .with(user(OWNER))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items[0].record.workoutId").value(w.id().toString()));
    mvc
      .perform(
        get("/api/v1/workouts/previous-sets")
          .param("exerciseIds", exerciseId.toString())
          .with(user("archived-catalog-bob"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items[0].record").isEmpty());
    var active = workouts.start(OWNER);
    mvc
      .perform(
        post("/api/v1/workouts/" + active.id() + "/sets")
          .with(user(OWNER))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"version\":" +
              active.version() +
              ",\"set\":{\"exerciseId\":\"" +
              exerciseId +
              "\",\"exerciseName\":\"Selected\",\"weight\":10,\"reps\":5}}"
          )
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        post("/api/v1/exercises/" + exerciseId + "/restore")
          .with(user(OWNER))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":1}")
      )
      .andExpect(status().isNotFound());
  }
}
