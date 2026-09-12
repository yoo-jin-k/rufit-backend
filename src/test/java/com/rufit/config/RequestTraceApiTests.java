package com.rufit.config;

import com.rufit.common.RequestTraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class RequestTraceApiTests {
    @Autowired MockMvc mvc;
    @Test void authenticationAndValidationErrorsHaveDistinctServerRequestIds() throws Exception {
        String first=mvc.perform(get("/api/v1/workouts")).andExpect(status().isUnauthorized())
            .andReturn().getResponse().getHeader(RequestTraceFilter.HEADER);
        String second=mvc.perform(get("/api/v1/workouts").param("date","invalid").header(RequestTraceFilter.HEADER,first).with(user("trace-test")))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getHeader(RequestTraceFilter.HEADER);
        assertThat(UUID.fromString(first)).isNotEqualTo(UUID.fromString(second));
        assertThat(org.slf4j.MDC.get("requestId")).isNull();
    }
}
