package com.rufit.workout;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExerciseHistoryTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  private UUID save(String owner, int second, boolean finished, String... names) {
    Workout w = new Workout(UUID.randomUUID(), owner, Instant.EPOCH);
    w.replaceEntries(
      Arrays.stream(names)
        .map(name -> new Workout.Entry(name, BigDecimal.TEN, 10))
        .toList()
    );
    if (finished) {
      for (int i = 0; i < names.length; i++) w.completeSet(i, true, 0, Instant.EPOCH);
      w.finish(Instant.EPOCH.plusSeconds(second));
    }
    return repository.saveAndFlush(w).id();
  }

  @Test
  void returnsMatchingSetsOnlyWithoutDuplicateSessionsAndInNewestOrder() throws Exception {
    UUID older = save("alice", 10, true, "스쿼트", "푸시업", "스쿼트");
    UUID newer = save("alice", 20, true, "스쿼트");
    save("alice", 30, false, "스쿼트");
    save("bob", 40, true, "스쿼트");
    save("alice", 50, true, "스쿼트 변형");
    mvc
      .perform(
        get("/api/v1/workouts/exercise-history")
          .param("exerciseName", " 스쿼트 ")
          .param("size", "1")
          .with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.exerciseName").value("스쿼트"))
      .andExpect(jsonPath("$.totalElements").value(2))
      .andExpect(jsonPath("$.hasNext").value(true))
      .andExpect(jsonPath("$.items[0].workoutId").value(newer.toString()));
    mvc
      .perform(
        get("/api/v1/workouts/exercise-history")
          .param("exerciseName", "스쿼트")
          .param("size", "1")
          .param("page", "1")
          .with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items[0].workoutId").value(older.toString()))
      .andExpect(jsonPath("$.items[0].sets.length()").value(2))
      .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void noMatchesAndInvalidRequestsAreUnambiguous() throws Exception {
    mvc
      .perform(
        get("/api/v1/workouts/exercise-history")
          .param("exerciseName", "없는 운동")
          .with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items.length()").value(0))
      .andExpect(jsonPath("$.totalElements").value(0));
    mvc
      .perform(
        get("/api/v1/workouts/exercise-history").param("exerciseName", " ").with(user("alice"))
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        get("/api/v1/workouts/exercise-history")
          .param("exerciseName", "스쿼트")
          .param("size", "51")
          .with(user("alice"))
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(get("/api/v1/workouts/exercise-history").param("exerciseName", "스쿼트"))
      .andExpect(status().isUnauthorized());
  }
}
