package com.rufit.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 스트레칭·플랭크처럼 시간(초)으로 기록하는 세트를 저장하고, 종목의 기록 방식과 맞지 않는 세트는 거절합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DurationSetTests {

  static final String HAMSTRING = "96c626c9-013d-5085-a0fc-42b9f1a01625";
  static final String PLANK = "be98d421-1e9d-5d87-bd98-530361afd8e4";
  static final String SQUAT = "00000000-0000-4000-8000-000000000001";

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @Autowired
  JdbcTemplate jdbc;

  @BeforeEach
  void clear() {
    repository.deleteAll();
  }

  private String start(String owner) throws Exception {
    String response = mvc
      .perform(post("/api/v1/workouts").with(user(owner)).with(csrf()))
      .andExpect(status().isCreated())
      .andReturn()
      .getResponse()
      .getContentAsString();
    return JsonPath.read(response, "$.id");
  }

  private ResultActions edit(String owner, String id, long version, String sets) throws Exception {
    return mvc.perform(
      put("/api/v1/workouts/" + id + "/exercises")
        .with(user(owner))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"version\":" + version + ",\"sets\":[" + sets + "]}")
    );
  }

  @Test
  void catalogMarksStretchesAndPlanksAsTimed() throws Exception {
    mvc
      .perform(get("/api/v1/exercises/" + HAMSTRING).with(user("duration-catalog")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.bodyPart").value("STRETCH"))
      .andExpect(jsonPath("$.tracking").value("DURATION"));
    mvc
      .perform(get("/api/v1/exercises/" + PLANK).with(user("duration-catalog")))
      .andExpect(jsonPath("$.bodyPart").value("CORE"))
      .andExpect(jsonPath("$.tracking").value("DURATION"));
    mvc
      .perform(get("/api/v1/exercises/" + SQUAT).with(user("duration-catalog")))
      .andExpect(jsonPath("$.tracking").value("WEIGHT_REPS"));
    mvc
      .perform(get("/api/v1/exercises?bodyPart=STRETCH&size=50").with(user("duration-catalog")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.totalElements").value(12));
  }

  @Test
  void savesTimedSetsNextToWeightSetsAndKeepsThemOutOfVolume() throws Exception {
    String owner = "duration-alice";
    String id = start(owner);
    edit(
      owner,
      id,
      0,
      "{\"exerciseId\":\"" + SQUAT + "\",\"exerciseName\":\"스쿼트\",\"weight\":60,\"reps\":10}," +
      "{\"exerciseId\":\"" + HAMSTRING + "\",\"exerciseName\":\"햄스트링 스트레칭\",\"weight\":0,\"durationSeconds\":30}"
    )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.sets[0].reps").value(10))
      .andExpect(jsonPath("$.sets[0].durationSeconds").doesNotExist())
      .andExpect(jsonPath("$.sets[1].reps").doesNotExist())
      .andExpect(jsonPath("$.sets[1].durationSeconds").value(30));
    assertThat(
      jdbc.queryForObject(
        "select duration_seconds from workout_entries where workout_id=?::uuid and entry_order=1",
        Integer.class,
        id
      )
    ).isEqualTo(30);
    // 두 세트를 완료하고 끝내면 결과의 볼륨에는 스쿼트만 들어갑니다.
    for (int index = 0; index < 2; index++) mvc
      .perform(
        put("/api/v1/workouts/" + id + "/sets/" + index + "/completion")
          .with(user(owner))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":" + (1 + index) + ",\"completed\":true}")
      )
      .andExpect(status().isOk());
    mvc
      .perform(
        post("/api/v1/workouts/" + id + "/finish")
          .with(user(owner))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"version\":3}")
      )
      .andExpect(status().isOk());
    mvc
      .perform(get("/api/v1/workouts/" + id + "/summary").with(user(owner)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.completedSets").value(2))
      .andExpect(jsonPath("$.totalReps").value(10));
  }

  @Test
  void rejectsSetsThatDoNotMatchTheExerciseTracking() throws Exception {
    String owner = "duration-bob";
    String id = start(owner);
    // 스트레칭에 횟수, 스쿼트에 시간, 시간 세트에 무게, 둘 다·둘 다 없음은 모두 거절합니다.
    edit(owner, id, 0, "{\"exerciseId\":\"" + HAMSTRING + "\",\"exerciseName\":\"x\",\"weight\":0,\"reps\":10}")
      .andExpect(status().isBadRequest());
    edit(owner, id, 0, "{\"exerciseId\":\"" + SQUAT + "\",\"exerciseName\":\"x\",\"weight\":0,\"durationSeconds\":30}")
      .andExpect(status().isBadRequest());
    edit(owner, id, 0, "{\"exerciseId\":\"" + PLANK + "\",\"exerciseName\":\"x\",\"weight\":5,\"durationSeconds\":30}")
      .andExpect(status().isBadRequest());
    edit(owner, id, 0, "{\"exerciseId\":\"" + PLANK + "\",\"exerciseName\":\"x\",\"weight\":0,\"reps\":1,\"durationSeconds\":30}")
      .andExpect(status().isBadRequest());
    edit(owner, id, 0, "{\"exerciseName\":\"이름만 있는 세트\",\"weight\":0}")
      .andExpect(status().isBadRequest());
    edit(owner, id, 0, "{\"exerciseId\":\"" + PLANK + "\",\"exerciseName\":\"x\",\"weight\":0,\"durationSeconds\":3601}")
      .andExpect(status().isBadRequest());
  }

  @Test
  void databaseRejectsMixedRowsEvenWithoutTheService() {
    var entry = new Workout.Entry(UUID.fromString(PLANK), "플랭크", BigDecimal.ZERO, null, 45, "NORMAL", null);
    assertThat(entry.reps()).isNull();
    assertThat(entry.withRpe(8).durationSeconds()).isEqualTo(45);
    assertThatThrownBy(() -> new Workout.Entry(null, "플랭크", BigDecimal.ZERO, null, null, null, null)).isInstanceOf(
      IllegalArgumentException.class
    );
    String id = UUID.randomUUID().toString();
    jdbc.update("insert into workouts(id,owner_name,started_at,version) values(?::uuid,'duration-db',current_timestamp,0)", id);
    assertThatThrownBy(() ->
      jdbc.update(
        "insert into workout_entries(workout_id,entry_order,exercise_name,weight,reps,duration_seconds) values(?::uuid,0,'x',0,5,30)",
        id
      )
    ).hasMessageContaining("workout_entries_reps_or_duration");
    jdbc.update("delete from workouts where id=?::uuid", id);
  }
}
