package com.rufit.workout;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ManualImportDateTests {

  @Autowired
  MockMvc mvc;

  @Test
  void manualDateSurvivesReplayAndCalendarAggregation() throws Exception {
    String owner = "manual-" + UUID.randomUUID(),
      key = UUID.randomUUID().toString();
    String body = """
      {"startedAt":"2026-01-10T12:00:00Z","finishedAt":"2026-01-10T12:00:00Z","recordDate":"2025-12-31","entryMode":"manual",
       "sets":[{"set":{"exerciseName":"스쿼트","weight":50,"reps":10},"completed":true}],"note":"","confirmed":true}
      """;
    mvc
      .perform(
        post("/api/v1/workouts/import")
          .with(user(owner))
          .with(csrf())
          .header("Idempotency-Key", key)
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.workout.recordDate").value("2025-12-31"))
      .andExpect(jsonPath("$.replayed").value(false));
    mvc
      .perform(
        post("/api/v1/workouts/import")
          .with(user(owner))
          .with(csrf())
          .header("Idempotency-Key", key)
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.replayed").value(true));
    mvc
      .perform(get("/api/v1/workouts?date=2025-12-31").with(user(owner)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items.length()").value(1));
    mvc
      .perform(get("/api/v1/workouts/calendar?month=2025-12").with(user(owner)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.workoutDays").value(1))
      .andExpect(jsonPath("$.durationSeconds").value(0));
    mvc
      .perform(get("/api/v1/workouts?date=2026-01-10").with(user(owner)))
      .andExpect(jsonPath("$.items.length()").value(0));
    mvc
      .perform(
        post("/api/v1/workouts/import")
          .with(user(owner))
          .with(csrf())
          .header("Idempotency-Key", key)
          .contentType("application/json")
          .content(body.replace("2025-12-31", "2025-12-30"))
      )
      .andExpect(status().isConflict());
    mvc
      .perform(
        post("/api/v1/workouts/import")
          .with(user(owner))
          .with(csrf())
          .header("Idempotency-Key", UUID.randomUUID().toString())
          .contentType("application/json")
          .content(body.replace("2025-12-31", "2999-01-01"))
      )
      .andExpect(status().isBadRequest());
  }
}
