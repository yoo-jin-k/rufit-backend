package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OfflineWorkoutImportTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @Autowired
  JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  private static final String START = "2025-01-02T01:02:03.123Z";
  private static final String END = "2025-01-02T02:02:03.456Z";

  private String body(boolean finished) {
    return """
    {"startedAt":"%s","finishedAt":%s,"sets":[
      {"set":{"exerciseName":"스쿼트","weight":1,"reps":2},"completed":true},
      {"set":{"exerciseName":"스쿼트","weight":2,"reps":3},"completed":%s}],
     "note":"오프라인 메모","restSetIndex":null,"restEndsAt":null,"confirmed":true}
    """.formatted(START, finished ? '"' + END + '"' : "null", finished);
  }

  private MockHttpServletResponse send(UUID key, String owner, String body) throws Exception {
    return mvc
      .perform(
        post("/api/v1/workouts/import")
          .with(user(owner))
          .with(csrf())
          .header("Idempotency-Key", key)
          .contentType(MediaType.APPLICATION_JSON)
          .content(body)
      )
      .andReturn()
      .getResponse();
  }

  @Test
  void importsActiveSetsNoteAndOriginalStartTogetherAndSeparatesOwners() throws Exception {
    UUID id = UUID.randomUUID();
    var response = send(id, "alice", body(false));
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(
      (String) JsonPath.read(response.getContentAsString(), "$.workout.startedAt")
    ).isEqualTo(START);
    assertThat(
      (List<Integer>) JsonPath.read(response.getContentAsString(), "$.workout.completedSetIndexes")
    ).containsExactly(0);
    assertThat(
      (Integer) JsonPath.read(response.getContentAsString(), "$.workout.version")
    ).isZero();
    mvc
      .perform(get("/api/v1/workouts/active").with(user("alice")))
      .andExpect(jsonPath("$.id").value(id.toString()))
      .andExpect(jsonPath("$.sets[1].reps").value(3))
      .andExpect(jsonPath("$.note").value("오프라인 메모"));
    mvc.perform(get("/api/v1/workouts/" + id).with(user("bob"))).andExpect(status().isNotFound());
    assertThat(send(id, "bob", body(false)).getStatus()).isEqualTo(409);
    assertThat(send(UUID.randomUUID(), "bob", body(false)).getStatus()).isEqualTo(200);
  }

  @Test
  void explicitZeroPauseFieldsPreserveOriginalImportReceiptCompatibility() throws Exception {
    UUID id = UUID.randomUUID();
    String original = body(false);
    assertThat(send(id, "alice", original).getStatus()).isEqualTo(200);
    String extended = original.replace(
      "\"confirmed\":true",
      "\"confirmed\":true,\"pausedAt\":null,\"pausedDurationMs\":0"
    );
    var replay = send(id, "alice", extended);
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat((Boolean) JsonPath.read(replay.getContentAsString(), "$.replayed")).isTrue();
  }

  @Test
  void importsFinishedHistoryAlongsideActiveWorkoutWithoutDroppingAnySets() throws Exception {
    var active = repository.saveAndFlush(new Workout(UUID.randomUUID(), "alice", Instant.now()));
    UUID id = UUID.randomUUID();
    assertThat(send(id, "alice", body(true)).getStatus()).isEqualTo(200);
    mvc
      .perform(get("/api/v1/workouts/" + id).with(user("alice")))
      .andExpect(jsonPath("$.finishedAt").value(END))
      .andExpect(jsonPath("$.completedSetIndexes.length()").value(2));
    mvc
      .perform(get("/api/v1/workouts").with(user("alice")).param("date", "2025-01-02"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items[0].id").value(id.toString()))
      .andExpect(jsonPath("$.totalElements").value(1));
    mvc
      .perform(get("/api/v1/workouts/active").with(user("alice")))
      .andExpect(jsonPath("$.id").value(active.id().toString()));
    assertThat(send(UUID.randomUUID(), "alice", body(false)).getStatus()).isEqualTo(409);
    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void retriesReturnReceiptAndLatestStateWithoutOverwritingAndChangedPayloadIsRejected()
    throws Exception {
    UUID id = UUID.randomUUID();
    String request = body(false);
    assertThat(send(id, "alice", request).getStatus()).isEqualTo(200);
    mvc
      .perform(
        put("/api/v1/workouts/" + id + "/note")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":0,\"note\":\"서버에서 새로 수정\"}")
      )
      .andExpect(status().isOk());
    var replay = send(id, "alice", request);
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat((Boolean) JsonPath.read(replay.getContentAsString(), "$.replayed")).isTrue();
    assertThat((Integer) JsonPath.read(replay.getContentAsString(), "$.importedVersion")).isZero();
    assertThat((String) JsonPath.read(replay.getContentAsString(), "$.workout.note")).isEqualTo(
      "서버에서 새로 수정"
    );
    for (String changed : List.of(
      request.replace("오프라인 메모", "다른 입력"),
      request.replace("\"weight\":1", "\"weight\":9"),
      request.replace(START, "2025-01-01T01:02:03Z"),
      request.replace("\"completed\":false", "\"completed\":true"),
      body(true)
    ))
      assertThat(send(id, "alice", changed).getStatus()).isEqualTo(409);
    mvc
      .perform(
        post("/api/v1/workouts").with(user("alice")).with(csrf()).header("Idempotency-Key", id)
      )
      .andExpect(status().isConflict());
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void validatesTimesConfirmationAndCompletedSetsBeforeCreatingAnything() throws Exception {
    String finished = body(true);
    for (String invalid : List.of(
      finished.replace(START, "1900-01-01T00:00:00Z"),
      finished.replace(START, Instant.now().plusSeconds(3600).toString()),
      finished.replace(END, "2025-01-01T00:00:00Z"),
      finished.replace(END, Instant.now().plusSeconds(3600).toString()),
      finished.replace("\"confirmed\":true", "\"confirmed\":false"),
      finished.replace("\"weight\":1", "\"weight\":-1"),
      finished.replace("\"reps\":2", "\"reps\":0"),
      finished.replace("\"note\":\"오프라인 메모\"", "\"note\":null")
    ))
      assertThat(send(UUID.randomUUID(), "alice", invalid).getStatus()).isEqualTo(400);
    assertThat(
      send(
        UUID.randomUUID(),
        "alice",
        finished.replace("\"completed\":true", "\"completed\":false")
      ).getStatus()
    ).isEqualTo(400);
    assertThat(
      send(
        UUID.randomUUID(),
        "alice",
        body(false).replace("\"finishedAt\":null", "\"finishedAt\":\"" + END + "\"")
      ).getStatus()
    ).isEqualTo(409);
    assertThat(repository.count()).isZero();
  }

  @Test
  void expiresRestWithoutRestartingAndRejectsInvalidTargetsOrFinishedRest() throws Exception {
    String rest = body(false)
      .replace("\"restSetIndex\":null", "\"restSetIndex\":0")
      .replace("\"restEndsAt\":null", "\"restEndsAt\":\"2025-01-02T01:10:00Z\"");
    UUID id = UUID.randomUUID();
    assertThat(send(id, "alice", rest).getStatus()).isEqualTo(200);
    mvc
      .perform(get("/api/v1/workouts/" + id).with(user("alice")))
      .andExpect(jsonPath("$.restEndsAt").isEmpty());
    repository.deleteAll();
    assertThat(
      send(
        UUID.randomUUID(),
        "alice",
        rest.replace("\"restSetIndex\":0", "\"restSetIndex\":1")
      ).getStatus()
    ).isEqualTo(400);
    assertThat(
      send(
        UUID.randomUUID(),
        "alice",
        rest.replace("2025-01-02T01:10:00Z", Instant.now().plusSeconds(7200).toString())
      ).getStatus()
    ).isEqualTo(400);
    assertThat(
      send(
        UUID.randomUUID(),
        "alice",
        rest.replace("\"finishedAt\":null", "\"finishedAt\":\"" + END + "\"")
      ).getStatus()
    ).isEqualTo(400);
    assertThat(repository.count()).isZero();
  }

  @Test
  void unknownCatalogAndEndpointKeyReuseCannotCreatePartialRecords() throws Exception {
    String unknown = body(false).replace(
      "\"exerciseName\":\"스쿼트\"",
      "\"exerciseId\":\"" + UUID.randomUUID() + "\",\"exerciseName\":\"스쿼트\""
    );
    assertThat(send(UUID.randomUUID(), "alice", unknown).getStatus()).isEqualTo(404);
    assertThat(repository.count()).isZero();
    UUID normal = UUID.randomUUID();
    mvc
      .perform(
        post("/api/v1/workouts").with(user("alice")).with(csrf()).header("Idempotency-Key", normal)
      )
      .andExpect(status().isCreated());
    assertThat(send(normal, "alice", body(false)).getStatus()).isEqualTo(409);
    mvc
      .perform(
        post("/api/v1/workouts/import")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body(true))
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        post("/api/v1/workouts/import")
          .with(user("alice"))
          .header("Idempotency-Key", UUID.randomUUID())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body(true))
      )
      .andExpect(status().isForbidden());
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void deletedImportCannotBeResurrectedByReplay() throws Exception {
    UUID id = UUID.randomUUID();
    assertThat(send(id, "alice", body(true)).getStatus()).isEqualTo(200);
    mvc
      .perform(
        post("/api/v1/workouts/" + id + "/delete")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":0,\"confirmed\":true}")
      )
      .andExpect(status().isNoContent());
    assertThat(send(id, "alice", body(true)).getStatus()).isEqualTo(410);
    assertThat(send(id, "bob", body(true)).getStatus()).isEqualTo(409);
    assertThat(repository.count()).isZero();
  }

  @Test
  void simultaneousSameImportCreatesOnlyOneRecordAndRetryConfirmsIt() throws Exception {
    UUID id = UUID.randomUUID();
    String request = body(true);
    CountDownLatch ready = new CountDownLatch(5);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<MockHttpServletResponse>>();
      for (int i = 0; i < 5; i++) futures.add(
        pool.submit(() -> {
          ready.countDown();
          if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
          return send(id, "alice", request);
        })
      );
      for (var future : futures)
        assertThat(future.get(20, TimeUnit.SECONDS).getStatus()).isIn(200, 409);
    }
    assertThat(repository.count()).isEqualTo(1);
    assertThat(
      jdbc.queryForObject("select count(*) from workout_entries where workout_id=?", Long.class, id)
    ).isEqualTo(2);
    var replay = send(id, "alice", request);
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat((Boolean) JsonPath.read(replay.getContentAsString(), "$.replayed")).isTrue();
  }

  @Test
  void concurrentDifferentActiveImportsCommitOnlyOneAndLeaveNoOrphanSets() throws Exception {
    CountDownLatch ready = new CountDownLatch(5);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<MockHttpServletResponse>>();
      for (int i = 0; i < 5; i++) futures.add(
        pool.submit(() -> {
          ready.countDown();
          if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
          return send(UUID.randomUUID(), "alice", body(false));
        })
      );
      int saved = 0;
      for (var future : futures) {
        int status = future.get(20, TimeUnit.SECONDS).getStatus();
        assertThat(status).isIn(200, 409);
        if (status == 200) saved++;
      }
      assertThat(saved).isEqualTo(1);
    }
    assertThat(repository.count()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from workout_entries", Long.class)).isEqualTo(
      2
    );
  }
}
