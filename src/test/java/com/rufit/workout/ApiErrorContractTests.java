package com.rufit.workout;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiErrorContractTests {

  @Autowired
  MockMvc mvc;

  @Test
  void invalidFieldsReturnMachineReadableCodesWithoutRejectedValues() throws Exception {
    mvc
      .perform(
        post("/api/v1/routines")
          .with(user("error-contract"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"title\":\"\",\"sets\":[]}")
      )
      .andExpect(status().isBadRequest())
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
      .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
      .andExpect(jsonPath("$.errors").isArray())
      .andExpect(jsonPath("$.errors[0].field").isNotEmpty())
      .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
  }

  @Test
  void malformedJsonAndInvalidUuidHaveSafeErrors() throws Exception {
    mvc
      .perform(
        post("/api/v1/routines")
          .with(user("error-contract"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{broken")
      )
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_JSON"))
      .andExpect(jsonPath("$.trace").doesNotExist());
    mvc
      .perform(get("/api/v1/workouts/not-a-uuid").with(user("error-contract")))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @Test
  void authenticationAndCsrfFailuresUseProblemJson() throws Exception {
    mvc
      .perform(get("/api/v1/workouts"))
      .andExpect(status().isUnauthorized())
      .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    mvc
      .perform(post("/api/v1/workouts").with(user("error-contract")))
      .andExpect(status().isForbidden())
      .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void absentResourcesAndUnsupportedMethodsUseSameContract() throws Exception {
    mvc
      .perform(
        get("/api/v1/workouts/00000000-0000-0000-0000-000000000000").with(user("error-contract"))
      )
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc
      .perform(delete("/api/v1/workouts").with(user("error-contract")).with(csrf()))
      .andExpect(status().isMethodNotAllowed())
      .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
  }
}
