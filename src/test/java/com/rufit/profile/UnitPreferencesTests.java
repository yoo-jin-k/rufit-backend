package com.rufit.profile;

import static org.assertj.core.api.Assertions.*;
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
class UnitPreferencesTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from unit_preferences");
  }

  private org.springframework.test.web.servlet.ResultActions save(String user, String body) throws Exception {
    return mvc.perform(
      put("/api/v1/preferences/units").with(user(user)).with(csrf()).contentType("application/json").content(body)
    );
  }

  @Test
  void defaultsToKgWithoutCreatingRowsAndKeepsOwnersSeparate() throws Exception {
    mvc
      .perform(get("/api/v1/preferences/units").with(user("unit-alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(0))
      .andExpect(jsonPath("$.weightUnit").value("KG"));
    assertThat(jdbc.queryForObject("select count(*) from unit_preferences", Long.class)).isZero();
    save("unit-alice", "{\"version\":0,\"weightUnit\":\"LB\",\"ownerName\":\"unit-bob\"}")
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(1))
      .andExpect(jsonPath("$.weightUnit").value("LB"));
    mvc
      .perform(get("/api/v1/preferences/units").with(user("unit-bob")))
      .andExpect(jsonPath("$.weightUnit").value("KG"));
  }

  @Test
  void staleVersionConflictsAndInvalidUnitIsRejected() throws Exception {
    save("unit-carol", "{\"version\":0,\"weightUnit\":\"LB\"}").andExpect(status().isOk());
    save("unit-carol", "{\"version\":0,\"weightUnit\":\"KG\"}").andExpect(status().isConflict());
    save("unit-carol", "{\"version\":1,\"weightUnit\":\"KG\"}")
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value(2));
    save("unit-carol", "{\"version\":2,\"weightUnit\":\"STONE\"}").andExpect(status().isBadRequest());
  }
}
