package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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
class WorkoutPauseApiTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  private String action(UUID id, String path, long version, String owner, int expected)
    throws Exception {
    return mvc
      .perform(
        post("/api/v1/workouts/" + id + "/" + path)
          .with(user(owner))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + version + "}")
      )
      .andExpect(status().is(expected))
      .andReturn()
      .getResponse()
      .getContentAsString();
  }

  @Test
  void pausePersistsAcrossReadsAndResumeUsesStoredPauseWithOwnershipAndVersionChecks()
    throws Exception {
    Workout w = repository.saveAndFlush(
      new Workout(UUID.randomUUID(), "alice", Instant.now().minusSeconds(3600))
    );
    action(w.id(), "pause", 0, "bob", 404);
    String paused = action(w.id(), "pause", 0, "alice", 200);
    String at = JsonPath.read(paused, "$.pausedAt");
    assertThat(at).isNotBlank();
    action(w.id(), "pause", 0, "alice", 409);
    String replay = action(w.id(), "pause", 1, "alice", 200);
    assertThat((String) JsonPath.read(replay, "$.pausedAt")).isEqualTo(at);
    assertThat((Integer) JsonPath.read(replay, "$.version")).isEqualTo(1);
    mvc
      .perform(get("/api/v1/workouts/active").with(user("alice")))
      .andExpect(jsonPath("$.pausedAt").value(at));
    mvc
      .perform(get("/api/v1/workouts/" + w.id()).with(user("alice")))
      .andExpect(jsonPath("$.pausedAt").value(at));
    String resumed = action(w.id(), "resume", 1, "alice", 200);
    assertThat((Object) JsonPath.read(resumed, "$.pausedAt")).isNull();
    assertThat(
      ((Number) JsonPath.read(resumed, "$.pausedDurationMs")).longValue()
    ).isGreaterThanOrEqualTo(0);
    action(w.id(), "resume", 1, "alice", 409);
    action(w.id(), "resume", 2, "alice", 200);
    mvc
      .perform(
        post("/api/v1/workouts/" + w.id() + "/pause")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{}")
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        post("/api/v1/workouts/" + w.id() + "/pause")
          .with(user("alice"))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":2}")
      )
      .andExpect(status().isForbidden());
  }

  @Test
  void historySummaryAndBothCalendarsExcludeAllPausedTimeAfterReload() throws Exception {
    Instant start = Instant.parse("2026-09-01T00:00:00Z");
    Workout w = new Workout(UUID.randomUUID(), "alice", start);
    w.appendSet(new Workout.Entry("스쿼트", BigDecimal.ONE, 2));
    w.completeSet(0, true, 90, start);
    w.pauseTimer(start.plusSeconds(10));
    w.resumeTimer(start.plusSeconds(40));
    w.pauseTimer(start.plusSeconds(60));
    w.finish(start.plusSeconds(100));
    repository.saveAndFlush(w);
    mvc
      .perform(get("/api/v1/workouts/" + w.id() + "/summary").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.durationSeconds").value(30));
    mvc
      .perform(get("/api/v1/workouts/calendar").param("month", "2026-09").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.durationSeconds").value(30));
    mvc
      .perform(
        get("/api/v1/workouts/calendar/week").param("date", "2026-09-01").with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.durationSeconds").value(30));
    action(w.id(), "pause", 0, "alice", 409);
    action(w.id(), "resume", 0, "alice", 409);
  }

  @Test
  void importsPausedStateAndReplayNeverResumesItOrAcceptsChangedClock() throws Exception {
    UUID id = UUID.randomUUID();
    String body = """
      {"startedAt":"2026-09-01T00:00:00Z","sets":[],"note":"","confirmed":true,
       "pausedAt":"2026-09-01T00:01:00Z","pausedDurationMs":10000}
      """;
    for (int i = 0; i < 2; i++) mvc
      .perform(
        post("/api/v1/workouts/import")
          .header("Idempotency-Key", id)
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.workout.pausedAt").value("2026-09-01T00:01:00Z"))
      .andExpect(jsonPath("$.workout.pausedDurationMs").value(10000))
      .andExpect(jsonPath("$.replayed").value(i == 1));
    mvc
      .perform(
        post("/api/v1/workouts/import")
          .header("Idempotency-Key", id)
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body.replace("10000", "10001"))
      )
      .andExpect(status().isConflict());
    repository.deleteAll();
    for (String invalid : new String[] {
      body.replace("10000", "60001"),
      body.replace("10000", "-1"),
      body.replace("2026-09-01T00:01:00Z", "2026-08-01T00:01:00Z"),
      body.replace("2026-09-01T00:01:00Z", Instant.now().plusSeconds(3600).toString()),
      body.replace("\"sets\":[]", "\"finishedAt\":\"2026-09-01T00:02:00Z\",\"sets\":[]"),
    }) {
      mvc
        .perform(
          post("/api/v1/workouts/import")
            .header("Idempotency-Key", UUID.randomUUID())
            .with(user("alice"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalid)
        )
        .andExpect(status().isBadRequest());
    }
    assertThat(repository.count()).isZero();
  }
}
