package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rufit.routine.RoutineController;
import com.rufit.routine.RoutineService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutCapacityTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutService workouts;

  @Autowired
  RoutineService routines;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  ObjectMapper mapper;

  @BeforeEach
  void clean() {
    jdbc.update("delete from workouts");
    jdbc.update("delete from routines");
  }

  @Test
  void maximumSizeDraftFinishesSummarizesAndBecomesIndependentRoutineWithoutPartialOversizeSave()
    throws Exception {
    String owner = "capacity-test",
      name = "가".repeat(80),
      note = "메".repeat(2000);
    var workout = workouts.start(owner);
    var sets = IntStream.range(0, 300)
      .mapToObj(i ->
        new WorkoutController.DraftSet(
          new WorkoutController.SetInput(name, new BigDecimal("1500"), 1000),
          true
        )
      )
      .toList();
    UUID operation = UUID.randomUUID();
    var draft = new WorkoutController.Draft(
      operation,
      workout.version(),
      sets,
      299,
      Instant.now().plusSeconds(60),
      note
    );
    String body = mapper.writeValueAsString(draft);
    mvc
      .perform(
        put("/api/v1/workouts/" + workout.id() + "/draft")
          .with(user(owner))
          .with(csrf())
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.workout.sets.length()").value(300))
      .andExpect(jsonPath("$.workout.completedSetIndexes.length()").value(300))
      .andExpect(jsonPath("$.workout.restSetIndex").value(299))
      .andExpect(jsonPath("$.workout.note").value(note));
    var stored = workouts.get(workout.id(), owner);
    mvc
      .perform(
        put("/api/v1/workouts/" + workout.id() + "/draft")
          .with(user(owner))
          .with(csrf())
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.replayed").value(true))
      .andExpect(jsonPath("$.appliedVersion").value(stored.version()));
    var oversized = new ArrayList<>(sets);
    oversized.add(sets.getFirst());
    var rejected = new WorkoutController.Draft(
      UUID.randomUUID(),
      stored.version(),
      oversized,
      null,
      null,
      "Do not replace note"
    );
    mvc
      .perform(
        put("/api/v1/workouts/" + workout.id() + "/draft")
          .with(user(owner))
          .with(csrf())
          .contentType("application/json")
          .content(mapper.writeValueAsString(rejected))
      )
      .andExpect(status().isBadRequest());
    assertThat(workouts.get(workout.id(), owner)).isEqualTo(stored);
    mvc
      .perform(
        post("/api/v1/workouts/" + workout.id() + "/finish")
          .with(user(owner))
          .with(csrf())
          .contentType("application/json")
          .content(
            mapper.writeValueAsString(new WorkoutController.FinishRequest(stored.version(), false))
          )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.sets.length()").value(300))
      .andExpect(jsonPath("$.restEndsAt").isEmpty());
    var finished = workouts.get(workout.id(), owner);
    mvc
      .perform(get("/api/v1/workouts/" + workout.id() + "/summary").with(user(owner)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.completedSets").value(300))
      .andExpect(jsonPath("$.totalReps").value(300000))
      .andExpect(jsonPath("$.volumeKgReps").value(450000000))
      .andExpect(jsonPath("$.exercises.length()").value(1));
    var routine = routines.fromWorkout(
      finished.id(),
      owner,
      new RoutineController.Copy(finished.version(), "Maximum routine"),
      UUID.randomUUID()
    );
    assertThat(routine.sets()).hasSize(300);
    var next = routines.start(routine.id(), owner, UUID.randomUUID());
    assertThat(next.sets()).hasSize(300);
    assertThat(next.completedSetIndexes()).isEmpty();
    assertThat(next.note()).isEmpty();
    assertThat(next.sourceRoutineId()).isEqualTo(routine.id());
    assertThat(next.sets().getLast().exerciseName()).isEqualTo(name);
    assertThat(next.sets().getLast().weight()).isEqualByComparingTo("1500");
  }
}
