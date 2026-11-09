package com.rufit.workout;

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
class WorkoutNoteTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  private Workout start() {
    Workout w = new Workout(UUID.randomUUID(), "alice", Instant.now());
    w.appendSet(new Workout.Entry("Squat", BigDecimal.TEN, 10));
    w.completeSet(0, true, 0, Instant.now());
    return repository.saveAndFlush(w);
  }

  @Test
  void notesPersistThroughCompletionAndCanBeCorrectedAndClearedWithVersionChecks()
    throws Exception {
    Workout w = start();
    String saved = mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/note")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + w.version() + ",\"note\":\"첫 줄\\n두 번째 줄\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.note").value("첫 줄\n두 번째 줄"))
      .andReturn()
      .getResponse()
      .getContentAsString();
    mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/note")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + w.version() + ",\"note\":\"stale\"}")
      )
      .andExpect(status().isConflict());
    String finished = mvc
      .perform(
        post("/api/v1/workouts/" + w.id() + "/finish")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + JsonPath.read(saved, "$.version") + "}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.note").value("첫 줄\n두 번째 줄"))
      .andReturn()
      .getResponse()
      .getContentAsString();
    mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/note")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + JsonPath.read(finished, "$.version") + ",\"note\":\"\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.note").value(""));
    mvc
      .perform(get("/api/v1/workouts/" + w.id()).with(user("alice")))
      .andExpect(jsonPath("$.note").value(""));
  }

  @Test
  void draftNoteIsAtomicAndRetryDoesNotOverwriteLaterNoteAndOmissionPreservesIt() throws Exception {
    Workout w = start();
    UUID operation = UUID.randomUUID();
    String body =
      "{\"operationId\":\"" +
      operation +
      "\",\"version\":" +
      w.version() +
      ",\"sets\":[],\"note\":\"draft note\"}";
    String first = mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/draft")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.workout.note").value("draft note"))
      .andReturn()
      .getResponse()
      .getContentAsString();
    String edited = mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/note")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"version\":" +
              JsonPath.read(first, "$.workout.version") +
              ",\"note\":\"later note\"}"
          )
      )
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/draft")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body)
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.replayed").value(true))
      .andExpect(jsonPath("$.workout.note").value("later note"));
    mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/draft")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"operationId\":\"" +
              UUID.randomUUID() +
              "\",\"version\":" +
              JsonPath.read(edited, "$.version") +
              ",\"sets\":[]}"
          )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.workout.note").value("later note"));
  }

  @Test
  void otherOwnerOversizedNullAndCancelledNotesAreRejected() throws Exception {
    Workout w = start();
    for (String note : new String[] { "null", "\"" + "a".repeat(2001) + "\"" })
      mvc
        .perform(
          put("/api/v1/workouts/" + w.id() + "/note")
            .with(user("alice"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + w.version() + ",\"note\":" + note + "}")
        )
        .andExpect(status().isBadRequest());
    mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/note")
          .with(user("bob"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + w.version() + ",\"note\":\"secret\"}")
      )
      .andExpect(status().isNotFound());
    String cancelled = mvc
      .perform(
        post("/api/v1/workouts/" + w.id() + "/cancel")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + w.version() + ",\"confirmed\":true}")
      )
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    mvc
      .perform(
        put("/api/v1/workouts/" + w.id() + "/note")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"version\":" + JsonPath.read(cancelled, "$.version") + ",\"note\":\"secret\"}"
          )
      )
      .andExpect(status().isConflict());
  }
}
