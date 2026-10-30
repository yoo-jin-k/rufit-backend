package com.rufit.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rufit.workout.WorkoutService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatabaseAvailabilityApiTests {

  @Autowired
  MockMvc mvc;

  @MockitoSpyBean
  WorkoutService service;

  @Test
  void unavailableReadsReturnExplicitSanitizedServiceError() throws Exception {
    for (RuntimeException failure : new RuntimeException[] {
      new CannotCreateTransactionException("diagnostic-marker"),
      new DataAccessResourceFailureException("diagnostic-marker"),
    }) {
      doThrow(failure).when(service).active("db-outage-test");
      var response = mvc
        .perform(get("/api/v1/workouts/active").with(user("db-outage-test")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andReturn()
        .getResponse();
      assertThat(response.getContentAsString()).doesNotContain("diagnostic-marker");
      assertThat(UUID.fromString(response.getHeader(RequestTraceFilter.HEADER))).isNotNull();
    }
  }

  @Test
  void unavailableWritesUseSameContractAndDoNotClaimSuccess() throws Exception {
    UUID id = UUID.randomUUID();
    doThrow(new CannotCreateTransactionException("diagnostic-marker"))
      .when(service)
      .saveDraft(eq(id), eq("db-outage-test"), any());
    mvc
      .perform(
        put("/api/v1/workouts/" + id + "/draft")
          .with(user("db-outage-test"))
          .with(csrf())
          .contentType("application/json")
          .content(
            "{\"operationId\":\"" +
              UUID.randomUUID() +
              "\",\"version\":0,\"sets\":[],\"restSetIndex\":null,\"restEndsAt\":null}"
          )
      )
      .andExpect(status().isServiceUnavailable())
      .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
  }
}
