package com.rufit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RufitBackendApplicationTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  org.springframework.context.ApplicationContext context;

  @Test
  void migrationRunsAgainstDatabase() {
    assertThat(
      jdbc.queryForObject(
        "select value from application_metadata where name='application'",
        String.class
      )
    ).isEqualTo("rufit-backend");
  }

  @Test
  void healthIsPublicWithoutDatabaseDetails() throws Exception {
    mvc
      .perform(get("/actuator/health"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value("UP"))
      .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  void businessApiIsNotPublic() throws Exception {
    mvc.perform(get("/api/v1/workouts")).andExpect(status().isForbidden());
  }

  @Test
  void probesExposeOnlyStatusAndDoNotOpenOtherActuatorPaths() throws Exception {
    for (String path : new String[] { "/actuator/health/liveness", "/actuator/health/readiness" })
      mvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}"))
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(jsonPath("$.details").doesNotExist());
    mvc.perform(get("/actuator/health/db")).andExpect(status().isForbidden());
    mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
  }

  @Test
  void refusingTrafficChangesReadinessWithoutChangingLiveness() throws Exception {
    try {
      org.springframework.boot.availability.AvailabilityChangeEvent.publish(
        context,
        org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC
      );
      mvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
      mvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    } finally {
      org.springframework.boot.availability.AvailabilityChangeEvent.publish(
        context,
        org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC
      );
    }
  }
}
