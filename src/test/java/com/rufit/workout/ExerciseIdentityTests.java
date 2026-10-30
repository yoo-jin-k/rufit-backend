package com.rufit.workout;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExerciseIdentityTests {

  @Autowired
  MockMvc mvc;

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
      request.with(user(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json)
    );
  }

  private String result(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  private String id(String json) {
    return JsonPath.read(json, "$.id");
  }

  private Number version(String json) {
    return JsonPath.read(json, "$.version");
  }

  private String set(String catalogId, int weight) {
    return (
      "{\"exerciseId\":\"" +
      catalogId +
      "\",\"exerciseName\":\"입력한 별칭\",\"weight\":" +
      weight +
      ",\"reps\":10}"
    );
  }

  @Test
  void catalogRenameAndArchivePreserveRecordedNameAndIdentityHistory() throws Exception {
    String exercise = result(
      call(
        post("/api/v1/exercises"),
        "alice",
        "{\"name\":\"원래 이름\",\"bodyPart\":\"LEGS\",\"equipment\":\"OTHER\"}"
      ).andExpect(status().isCreated())
    );
    String exerciseId = id(exercise);
    String workout = result(
      call(post("/api/v1/workouts"), "alice", "").andExpect(status().isCreated())
    );
    String workoutId = id(workout);
    workout = result(
      call(
        post("/api/v1/workouts/" + workoutId + "/sets"),
        "alice",
        "{\"version\":" + version(workout) + ",\"set\":" + set(exerciseId, 40) + "}"
      )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sets[0].exerciseName").value("원래 이름"))
    );
    workout = result(
      call(
        put("/api/v1/workouts/" + workoutId + "/sets/0/completion"),
        "alice",
        "{\"version\":" + version(workout) + ",\"completed\":true,\"restSeconds\":0}"
      ).andExpect(status().isOk())
    );
    workout = result(
      call(
        post("/api/v1/workouts/" + workoutId + "/finish"),
        "alice",
        "{\"version\":" + version(workout) + "}"
      ).andExpect(status().isOk())
    );
    exercise = result(
      call(
        put("/api/v1/exercises/" + exerciseId),
        "alice",
        "{\"version\":" +
          version(exercise) +
          ",\"name\":\"새 이름\",\"bodyPart\":\"LEGS\",\"equipment\":\"OTHER\"}"
      ).andExpect(status().isOk())
    );
    mvc
      .perform(
        get("/api/v1/workouts/exercise-history").param("exerciseId", exerciseId).with(user("alice"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.exerciseName").value("새 이름"))
      .andExpect(jsonPath("$.items[0].sets[0].exerciseName").value("원래 이름"));
    call(
      post("/api/v1/exercises/" + exerciseId + "/archive"),
      "alice",
      "{\"version\":" + version(exercise) + "}"
    ).andExpect(status().isOk());
    call(
      put("/api/v1/workouts/" + workoutId + "/history"),
      "alice",
      "{\"version\":" + version(workout) + ",\"sets\":[" + set(exerciseId, 50) + "]}"
    )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.sets[0].exerciseName").value("원래 이름"))
      .andExpect(jsonPath("$.sets[0].exerciseId").value(exerciseId));
    String fresh = result(
      call(post("/api/v1/workouts"), "alice", "").andExpect(status().isCreated())
    );
    call(
      post("/api/v1/workouts/" + id(fresh) + "/sets"),
      "alice",
      "{\"version\":" + version(fresh) + ",\"set\":" + set(exerciseId, 40) + "}"
    ).andExpect(status().isBadRequest());
  }

  @Test
  void privateCatalogCannotBeUsedByOthersAndRoutineCopiesPublicIdentity() throws Exception {
    String privateExercise = result(
      call(
        post("/api/v1/exercises"),
        "bob",
        "{\"name\":\"개인 종목\",\"bodyPart\":\"LEGS\",\"equipment\":\"OTHER\"}"
      ).andExpect(status().isCreated())
    );
    String privateId = id(privateExercise);
    call(
      post("/api/v1/routines"),
      "alice",
      "{\"title\":\"거절될 루틴\",\"sets\":[" + set(privateId, 10) + "]}"
    ).andExpect(status().isNotFound());
    mvc
      .perform(
        get("/api/v1/workouts/exercise-history").param("exerciseId", privateId).with(user("alice"))
      )
      .andExpect(status().isNotFound());
    String publicId = "00000000-0000-4000-8000-000000000001";
    String routine = result(
      call(
        post("/api/v1/routines"),
        "alice",
        "{\"title\":\"공용 종목\",\"sets\":[" + set(publicId, 40) + "]}"
      )
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sets[0].exerciseName").value("스쿼트"))
    );
    call(post("/api/v1/routines/" + id(routine) + "/start"), "alice", "")
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.sets[0].exerciseId").value(publicId));
    mvc
      .perform(
        get("/api/v1/workouts/exercise-history")
          .param("exerciseId", publicId)
          .param("exerciseName", "스쿼트")
          .with(user("alice"))
      )
      .andExpect(status().isBadRequest());
  }
}
