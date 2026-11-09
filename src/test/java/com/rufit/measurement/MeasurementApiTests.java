package com.rufit.measurement;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeasurementApiTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from measurements");
  }

  @Test
  void ownershipVersionAndReplay() throws Exception {
    mvc
      .perform(
        put("/api/v1/measurements/weight/2026-01-01")
          .with(user("alice"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"version\":0,\"weight\":70.25}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(1));
    mvc
      .perform(
        put("/api/v1/measurements/weight/2026-01-01")
          .with(user("alice"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"version\":0,\"weight\":70.25}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(1));
    mvc
      .perform(
        put("/api/v1/measurements/weight/2026-01-01")
          .with(user("alice"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"version\":0,\"weight\":71}")
      )
      .andExpect(status().isConflict());
    mvc
      .perform(
        put("/api/v1/measurements/weight/2026-01-01")
          .with(user("alice"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"version\":1,\"weight\":71}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(2));
    mvc
      .perform(get("/api/v1/measurements/weight").with(user("bob")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items.length()").value(0));
    mvc
      .perform(get("/api/v1/measurements/weight").with(user("alice")))
      .andExpect(jsonPath("$.items[0].weight").value(71));
  }

  @Test
  void strengthAndValidation() throws Exception {
    mvc
      .perform(
        put("/api/v1/measurements/strength/2026-01-02")
          .with(user("alice"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"version\":0,\"bench\":80,\"squat\":100.5,\"deadlift\":120}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.squat").value(100.5));
    for (String body : new String[] {
      "{\"version\":0,\"weight\":0}",
      "{\"version\":0,\"weight\":700.01}",
      "{\"version\":0,\"weight\":60.001}",
      "{\"weight\":60}",
      "{\"version\":0,\"weight\":60,\"bench\":5}",
    })
      mvc
        .perform(
          put("/api/v1/measurements/weight/2026-01-02")
            .with(user("alice"))
            .with(csrf())
            .contentType("application/json")
            .content(body)
        )
        .andExpect(status().isBadRequest());
    mvc
      .perform(
        put("/api/v1/measurements/weight/2999-01-01")
          .with(user("alice"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"version\":0,\"weight\":60}")
      )
      .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/measurements/weight")).andExpect(status().isUnauthorized());
    mvc
      .perform(get("/api/v1/measurements/invalid").with(user("alice")))
      .andExpect(status().isBadRequest());
    mvc
      .perform(get("/api/v1/measurements/weight?size=101").with(user("alice")))
      .andExpect(status().isBadRequest());
  }
}
