package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionRollbackTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @Autowired
  WorkoutService service;

  @Autowired
  JdbcTemplate jdbc;

  @MockitoSpyBean
  DraftOperations operations;

  @MockitoSpyBean
  RetiredWorkoutKeys retired;

  @BeforeEach
  void clean() {
    repository.deleteAll();
    jdbc.update("delete from retired_workout_keys");
  }

  private WorkoutController.View baseline() {
    var w = service.start("rollback-test");
    w = service.appendSet(
      w.id(),
      "rollback-test",
      new WorkoutController.SetEdit(
        w.version(),
        new WorkoutController.SetInput("Original", BigDecimal.TEN, 5)
      )
    );
    w = service.complete(
      w.id(),
      "rollback-test",
      0,
      new WorkoutController.Completion(w.version(), true, 90)
    );
    return service.updateNote(
      w.id(),
      "rollback-test",
      new WorkoutController.Note(w.version(), "Original note")
    );
  }

  @Test
  void failureAfterJpaFlushAndJdbcReceiptInsertRollsBackBothBeforeRetry() throws Exception {
    var original = baseline();
    UUID operationId = UUID.randomUUID();
    doAnswer(invocation -> {
      invocation.callRealMethod();
      throw new DataAccessResourceFailureException("simulated failure after receipt insert");
    })
      .when(operations)
      .remember(eq(original.id()), eq(operationId), anyLong());
    String body =
      "{\"operationId\":\"" +
      operationId +
      "\",\"version\":" +
      original.version() +
      ",\"sets\":[{\"set\":{\"exerciseName\":\"Changed\",\"weight\":25,\"reps\":8},\"completed\":false}],\"restSetIndex\":null,\"restEndsAt\":null,\"note\":\"Changed note\"}";
    mvc
      .perform(
        put("/api/v1/workouts/" + original.id() + "/draft")
          .with(user("rollback-test"))
          .with(csrf())
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isServiceUnavailable());
    var unchanged = service.get(original.id(), "rollback-test");
    assertThat(unchanged).isEqualTo(original);
    assertThat(
      jdbc.queryForObject(
        "select count(*) from workout_draft_operations where workout_id=?",
        Long.class,
        original.id()
      )
    ).isZero();
    doCallRealMethod().when(operations).remember(eq(original.id()), eq(operationId), anyLong());
    mvc
      .perform(
        put("/api/v1/workouts/" + original.id() + "/draft")
          .with(user("rollback-test"))
          .with(csrf())
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.replayed").value(false))
      .andExpect(jsonPath("$.appliedVersion").value(original.version() + 1))
      .andExpect(jsonPath("$.workout.note").value("Changed note"));
    mvc
      .perform(
        put("/api/v1/workouts/" + original.id() + "/draft")
          .with(user("rollback-test"))
          .with(csrf())
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.replayed").value(true))
      .andExpect(jsonPath("$.appliedVersion").value(original.version() + 1));
    assertThat(
      jdbc.queryForObject(
        "select count(*) from workout_draft_operations where workout_id=?",
        Long.class,
        original.id()
      )
    ).isEqualTo(1);
  }

  @Test
  void failedDeletionRollsBackRetirementMarkerAndKeepsCompletedRecord() throws Exception {
    var original = baseline();
    var finished = service.finish(original.id(), "rollback-test", original.version(), false);
    doAnswer(invocation -> {
      invocation.callRealMethod();
      throw new DataAccessResourceFailureException("simulated failure after retirement insert");
    })
      .when(retired)
      .retire(finished.id(), "rollback-test");
    String body = "{\"version\":" + finished.version() + ",\"confirmed\":true}";
    mvc
      .perform(
        post("/api/v1/workouts/" + finished.id() + "/delete")
          .with(user("rollback-test"))
          .with(csrf())
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isServiceUnavailable());
    assertThat(service.get(finished.id(), "rollback-test")).isEqualTo(finished);
    assertThat(
      jdbc.queryForObject(
        "select count(*) from retired_workout_keys where id=?",
        Long.class,
        finished.id()
      )
    ).isZero();
    doCallRealMethod().when(retired).retire(finished.id(), "rollback-test");
    mvc
      .perform(
        post("/api/v1/workouts/" + finished.id() + "/delete")
          .with(user("rollback-test"))
          .with(csrf())
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isNoContent());
    assertThat(repository.existsById(finished.id())).isFalse();
    assertThat(
      jdbc.queryForObject(
        "select count(*) from retired_workout_keys where id=?",
        Long.class,
        finished.id()
      )
    ).isEqualTo(1);
  }
}
