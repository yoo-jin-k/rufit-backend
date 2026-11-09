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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DailyWorkoutHistoryTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  private Workout save(String owner, String start, String state) {
    Instant time = Instant.parse(start);
    Workout w = new Workout(UUID.randomUUID(), owner, time);
    w.appendSet(new Workout.Entry("스쿼트", BigDecimal.TEN, 5));
    w.completeSet(0, true, 0, time);
    if (state.equals("finished")) w.finish(time.plusSeconds(600));
    if (state.equals("cancelled")) w.cancel(time.plusSeconds(60));
    return repository.saveAndFlush(w);
  }

  @Test
  void dateMatchesCalendarStartDayWithOwnerStateAndPagination() throws Exception {
    save("alice", "2026-08-31T14:59:59Z", "finished");
    Workout first = save("alice", "2026-08-31T15:00:00Z", "finished");
    Workout last = save("alice", "2026-09-01T14:59:59Z", "finished");
    save("alice", "2026-09-01T15:00:00Z", "finished");
    save("bob", "2026-09-01T01:00:00Z", "finished");
    save("alice", "2026-09-01T02:00:00Z", "cancelled");
    save("alice", "2026-09-01T03:00:00Z", "active");
    mvc
      .perform(
        get("/api/v1/workouts").param("date", "2026-09-01").param("size", "1").with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.totalElements").value(2))
      .andExpect(jsonPath("$.hasNext").value(true))
      .andExpect(jsonPath("$.items[0].id").value(last.id().toString()));
    mvc
      .perform(
        get("/api/v1/workouts")
          .param("date", "2026-09-01")
          .param("size", "1")
          .param("page", "1")
          .with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.hasNext").value(false))
      .andExpect(jsonPath("$.items[0].id").value(first.id().toString()));
    mvc
      .perform(get("/api/v1/workouts/calendar").param("month", "2026-09").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.days[0].workouts").value(2));
    mvc
      .perform(get("/api/v1/workouts").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.totalElements").value(4));
    repository.deleteById(first.id());
    mvc
      .perform(get("/api/v1/workouts").param("date", "2026-09-01").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void daylightSavingDaysUseLocalMidnightsInsteadOfFixed24Hours() throws Exception {
    save("alice", "2026-03-08T05:00:00Z", "finished");
    save("alice", "2026-03-09T03:59:59Z", "finished");
    save("alice", "2026-03-09T04:00:00Z", "finished");
    save("alice", "2026-11-01T04:00:00Z", "finished");
    save("alice", "2026-11-02T04:59:59Z", "finished");
    save("alice", "2026-11-02T05:00:00Z", "finished");
    for (String date : new String[] { "2026-03-08", "2026-11-01" })
      mvc
        .perform(
          get("/api/v1/workouts")
            .param("date", date)
            .param("zone", "America/New_York")
            .with(user("alice"))
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void validatesDateAndZoneAndReturnsEmptyForMissingOrSkippedDay() throws Exception {
    for (String date : new String[] {
      "",
      "2026-02-29",
      "2026-04-31",
      "26-09-01",
      "1899-12-31",
      "2101-01-01",
    })
      mvc
        .perform(get("/api/v1/workouts").param("date", date).with(user("alice")))
        .andExpect(status().isBadRequest());
    mvc
      .perform(
        get("/api/v1/workouts")
          .param("date", "2026-09-01")
          .param("zone", "invalid")
          .with(user("alice"))
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(get("/api/v1/workouts").param("date", "2024-02-29").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items.length()").value(0));
    mvc
      .perform(
        get("/api/v1/workouts")
          .param("date", "2011-12-30")
          .param("zone", "Pacific/Apia")
          .with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.totalElements").value(0));
    mvc
      .perform(get("/api/v1/workouts").param("date", "2026-09-01"))
      .andExpect(status().isUnauthorized());
  }
}
