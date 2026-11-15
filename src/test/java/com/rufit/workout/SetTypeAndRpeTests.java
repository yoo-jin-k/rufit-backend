package com.rufit.workout;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 세트 종류(일반·웜업·드롭·실패)와 운동 자각도(RPE 5~10)를 저장하고 그대로 돌려줍니다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SetTypeAndRpeTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  WorkoutRepository repository;

  @BeforeEach
  void clear() {
    repository.deleteAll();
  }

  private String start() throws Exception {
    String response = mvc
      .perform(post("/api/v1/workouts").with(user("set-alice")).with(csrf()))
      .andExpect(status().isCreated())
      .andReturn()
      .getResponse()
      .getContentAsString();
    return JsonPath.read(response, "$.id");
  }

  private ResultActions edit(String id, String sets) throws Exception {
    return mvc.perform(
      put("/api/v1/workouts/" + id + "/exercises")
        .with(user("set-alice"))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"version\":0,\"sets\":[" + sets + "]}")
    );
  }

  @Test
  void savesSetTypeAndRpeAndDefaultsOlderRequestsToNormalWithoutRpe() throws Exception {
    String id = start();
    edit(
      id,
      "{\"exerciseName\":\"스쿼트\",\"weight\":40,\"reps\":10,\"setType\":\"WARMUP\"}," +
      "{\"exerciseName\":\"스쿼트\",\"weight\":80,\"reps\":5,\"setType\":\"FAILURE\",\"rpe\":10}," +
      "{\"exerciseName\":\"스쿼트\",\"weight\":70,\"reps\":8}"
    )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.sets[0].setType").value("WARMUP"))
      .andExpect(jsonPath("$.sets[0].rpe").doesNotExist())
      .andExpect(jsonPath("$.sets[1].setType").value("FAILURE"))
      .andExpect(jsonPath("$.sets[1].rpe").value(10))
      .andExpect(jsonPath("$.sets[2].setType").value("NORMAL"))
      .andExpect(jsonPath("$.sets[2].rpe").doesNotExist());
    mvc
      .perform(get("/api/v1/workouts/" + id).with(user("set-alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.sets[1].setType").value("FAILURE"))
      .andExpect(jsonPath("$.sets[1].rpe").value(10));
  }

  @Test
  void rejectsUnknownSetTypeAndRpeOutsideFiveToTen() throws Exception {
    String id = start();
    edit(id, "{\"exerciseName\":\"스쿼트\",\"weight\":40,\"reps\":10,\"setType\":\"SUPER\"}")
      .andExpect(status().isBadRequest());
    edit(id, "{\"exerciseName\":\"스쿼트\",\"weight\":40,\"reps\":10,\"rpe\":4}")
      .andExpect(status().isBadRequest());
    edit(id, "{\"exerciseName\":\"스쿼트\",\"weight\":40,\"reps\":10,\"rpe\":11}")
      .andExpect(status().isBadRequest());
  }

  @Test
  void duplicatedSetKeepsTypeButClearsRpe() {
    var source = new Workout.Entry(null, "스쿼트", new java.math.BigDecimal("50"), 5, "DROP", 9);
    var w = new Workout(java.util.UUID.randomUUID(), "set-alice", java.time.Instant.parse("2026-11-15T00:00:00Z"));
    w.replaceEntries(java.util.List.of(source));
    w.duplicateSet(0);
    var copy = w.entries().get(1);
    org.assertj.core.api.Assertions.assertThat(copy.setType()).isEqualTo("DROP");
    org.assertj.core.api.Assertions.assertThat(copy.rpe()).isNull();
  }
}
