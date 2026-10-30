package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SetDuplicationApiTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @Autowired
  JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from workouts");
    jdbc.update("delete from routines");
    jdbc.update("delete from exercises where owner_name is not null");
  }

  private ResultActions call(MockHttpServletRequestBuilder request, String owner, String json)
    throws Exception {
    return mvc.perform(
      request.with(user(owner)).with(csrf()).contentType("application/json").content(json)
    );
  }

  private String result(ResultActions action) throws Exception {
    return action.andReturn().getResponse().getContentAsString();
  }

  private String path(Workout w) {
    return "/api/v1/workouts/" + w.id();
  }

  private Workout saved(UUID exerciseId) {
    Instant start = Instant.now()
      .minusSeconds(60)
      .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    Workout w = Workout.fromRoutine(
      UUID.randomUUID(),
      "alice",
      start,
      UUID.randomUUID(),
      List.of(
        new Workout.Entry(exerciseId, "원래 이름", BigDecimal.ONE, 2),
        new Workout.Entry("덤벨 컬", BigDecimal.TEN, 10),
        new Workout.Entry("벤치프레스", new BigDecimal("20.25"), 8)
      )
    );
    w.completeSet(0, true, 0, start);
    w.completeSet(2, true, 300, start);
    w.updateNote("원본 보존");
    w.pauseTimer(start.plusSeconds(1));
    w.resumeTimer(start.plusSeconds(4));
    w.pauseTimer(start.plusSeconds(10));
    return repository.saveAndFlush(w);
  }

  @Test
  void duplicatesArchivedSnapshotThenEditsAndRemovesOnlyCopyWhileKeepingOtherState()
    throws Exception {
    String exercise = result(
      call(
        post("/api/v1/exercises"),
        "alice",
        "{\"name\":\"원래 이름\",\"bodyPart\":\"LEGS\",\"equipment\":\"OTHER\"}"
      ).andExpect(status().isCreated())
    );
    String exerciseId = JsonPath.read(exercise, "$.id");
    Workout w = saved(UUID.fromString(exerciseId));
    String base = path(w);
    String before = result(mvc.perform(get(base).with(user("alice"))).andExpect(status().isOk()));
    call(
      put("/api/v1/exercises/" + exerciseId),
      "alice",
      "{\"version\":0,\"name\":\"바뀐 이름\",\"bodyPart\":\"LEGS\",\"equipment\":\"OTHER\"}"
    ).andExpect(status().isOk());
    call(
      post("/api/v1/exercises/" + exerciseId + "/archive"),
      "alice",
      "{\"version\":1}"
    ).andExpect(status().isOk());
    call(post(base + "/sets/0/duplicate"), "alice", "{\"version\":0}")
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(1))
      .andExpect(jsonPath("$.sets.length()").value(4))
      .andExpect(jsonPath("$.sets[1].exerciseName").value("원래 이름"))
      .andExpect(jsonPath("$.sets[1].exerciseId").value(exerciseId))
      .andExpect(jsonPath("$.sets[1].weight").value(1))
      .andExpect(jsonPath("$.sets[1].reps").value(2))
      .andExpect(jsonPath("$.completedSetIndexes").value(org.hamcrest.Matchers.contains(0, 3)))
      .andExpect(jsonPath("$.restSetIndex").value(3))
      .andExpect(jsonPath("$.pausedDurationMs").value(3000));
    call(
      put(base + "/sets/1"),
      "alice",
      "{\"version\":1,\"set\":{\"exerciseId\":\"" +
        exerciseId +
        "\",\"exerciseName\":\"입력한 별칭\",\"weight\":2,\"reps\":3}}"
    )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.sets[0].weight").value(1))
      .andExpect(jsonPath("$.sets[1].weight").value(2))
      .andExpect(jsonPath("$.sets[0].reps").value(2));
    call(post(base + "/sets/1/remove"), "alice", "{\"version\":2}").andExpect(status().isOk());
    String after = result(
      mvc
        .perform(get(base).with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
    );
    Map<String, Object> original = JsonPath.read(before, "$");
    Map<String, Object> restored = JsonPath.read(after, "$");
    original.remove("version");
    restored.remove("version");
    assertThat(restored).isEqualTo(original);
  }

  @Test
  void validatesAuthenticationOwnershipInputStaleVersionClosedStateAndCapacity() throws Exception {
    Workout w = saved(null);
    String duplicate = path(w) + "/sets/0/duplicate";
    mvc
      .perform(
        post(duplicate).with(csrf()).contentType("application/json").content("{\"version\":0}")
      )
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        post(duplicate)
          .with(user("alice"))
          .contentType("application/json")
          .content("{\"version\":0}")
      )
      .andExpect(status().isForbidden());
    call(post(duplicate), "bob", "{\"version\":0}").andExpect(status().isNotFound());
    call(
      post("/api/v1/workouts/" + UUID.randomUUID() + "/sets/0/duplicate"),
      "alice",
      "{\"version\":0}"
    ).andExpect(status().isNotFound());
    for (String body : List.of(
      "{}",
      "{\"version\":null}",
      "{\"version\":-1}",
      "{\"version\":0.5}",
      "{\"version\":\"0\"}"
    ))
      call(post(duplicate), "alice", body).andExpect(status().isBadRequest());
    for (String index : List.of("-1", "3", "not-a-number", "2147483648"))
      call(post(path(w) + "/sets/" + index + "/duplicate"), "alice", "{\"version\":0}").andExpect(
        status().isBadRequest()
      );
    call(post(duplicate), "alice", "{\"version\":0}").andExpect(status().isOk());
    call(post(duplicate), "alice", "{\"version\":0}")
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    call(post(path(w) + "/sets/999/duplicate"), "alice", "{\"version\":0}").andExpect(
      status().isConflict()
    );
    call(
      post(path(w) + "/finish"),
      "alice",
      "{\"version\":1,\"discardIncomplete\":true}"
    ).andExpect(status().isOk());
    call(post(duplicate), "alice", "{\"version\":2}").andExpect(status().isConflict());
    Workout full = new Workout(UUID.randomUUID(), "capacity", Instant.now());
    full.replaceEntries(Collections.nCopies(300, new Workout.Entry("스쿼트", BigDecimal.ONE, 2)));
    repository.saveAndFlush(full);
    call(post(path(full) + "/sets/0/duplicate"), "capacity", "{\"version\":0}").andExpect(
      status().isBadRequest()
    );
    mvc
      .perform(get(path(full)).with(user("capacity")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(0))
      .andExpect(jsonPath("$.sets.length()").value(300));
  }

  @Test
  void simultaneousDuplicateRequestsCommitOnlyOnceAndKeepRestSourceInPostgres() throws Exception {
    Workout w = saved(null);
    CountDownLatch ready = new CountDownLatch(2);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      List<Future<Integer>> responses = new ArrayList<>();
      for (int i = 0; i < 2; i++) responses.add(
        executor.submit(() -> {
          ready.countDown();
          if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(
            "Requests did not start"
          );
          return call(post(path(w) + "/sets/0/duplicate"), "alice", "{\"version\":0}")
            .andReturn()
            .getResponse()
            .getStatus();
        })
      );
      assertThat(
        List.of(
          responses.get(0).get(20, TimeUnit.SECONDS),
          responses.get(1).get(20, TimeUnit.SECONDS)
        )
      ).containsExactlyInAnyOrder(200, 409);
    }
    mvc
      .perform(get(path(w)).with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(1))
      .andExpect(jsonPath("$.sets.length()").value(4))
      .andExpect(jsonPath("$.completedSetIndexes").value(org.hamcrest.Matchers.contains(0, 3)))
      .andExpect(jsonPath("$.restSetIndex").value(3))
      .andExpect(jsonPath("$.sets[3].exerciseName").value("벤치프레스"));
  }
}
