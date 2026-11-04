package com.rufit.social;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 전용 PostgreSQL에서 공개 동의·계정 분리·재시도와 검색 입력을 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialDirectoryTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  SocialDirectoryService service;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM social_directory");
  }

  void save(String owner, String nickname, boolean visible, long version) throws Exception {
    mvc
      .perform(
        put("/api/v1/social/directory/me")
          .with(user(owner))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"nickname\":\"" +
              nickname +
              "\",\"discoverable\":" +
              visible +
              ",\"version\":" +
              version +
              "}"
          )
      )
      .andExpect(status().isOk());
  }

  @Test
  void searchRequiresExplicitConsentAndNeverExposesPrivateOwner() throws Exception {
    mvc
      .perform(get("/api/v1/social/directory/me").with(user("alice")))
      .andExpect(jsonPath("$.discoverable").value(false))
      .andExpect(jsonPath("$.version").value(0));
    save("alice", "루핏하나", true, 0);
    save("bob-private-account", "루핏둘", false, 0);
    save("carol-private-account", "루핏셋", true, 0);
    mvc
      .perform(get("/api/v1/social/directory/search").param("q", "루핏").with(user("alice")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items.length()").value(1))
      .andExpect(jsonPath("$.items[0].nickname").value("루핏셋"))
      .andExpect(jsonPath("$.items[0].memberId").isNotEmpty())
      .andExpect(jsonPath("$.items[0].ownerName").doesNotExist())
      .andExpect(jsonPath("$.items[0].bio").doesNotExist());
    save("carol-private-account", "루핏셋", false, 1);
    mvc
      .perform(get("/api/v1/social/directory/search").param("q", "루핏").with(user("alice")))
      .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void sameUpdateRetriesDoNotIncreaseVersionAndStaleChangesConflict() throws Exception {
    save("alice", "루핏하나", true, 0);
    save("alice", "루핏하나", true, 0);
    mvc
      .perform(get("/api/v1/social/directory/me").with(user("alice")))
      .andExpect(jsonPath("$.version").value(1));
    mvc
      .perform(
        put("/api/v1/social/directory/me")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"nickname\":\"다른닉네임\",\"discoverable\":false,\"version\":0}")
      )
      .andExpect(status().isConflict());
    mvc
      .perform(get("/api/v1/social/directory/me").with(user("bob")))
      .andExpect(jsonPath("$.memberId").isEmpty());
  }

  @Test
  void literalWildcardsAndPagingDoNotBroadenSearch() throws Exception {
    save("a", "루핏일반", true, 0);
    save("b", "루핏%회원", true, 0);
    mvc
      .perform(get("/api/v1/social/directory/search").param("q", "핏%").with(user("viewer")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items.length()").value(1))
      .andExpect(jsonPath("$.items[0].nickname").value("루핏%회원"));
    mvc
      .perform(
        get("/api/v1/social/directory/search")
          .param("q", "루핏")
          .param("size", "1")
          .with(user("viewer"))
      )
      .andExpect(jsonPath("$.items.length()").value(1))
      .andExpect(jsonPath("$.hasNext").value(true));
    mvc
      .perform(
        get("/api/v1/social/directory/search")
          .param("q", "루핏")
          .param("size", "51")
          .with(user("viewer"))
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  void anonymousAndInvalidWritesAreRejected() throws Exception {
    mvc
      .perform(get("/api/v1/social/directory/search").param("q", "루핏"))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        put("/api/v1/social/directory/me")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"nickname\":\" a \",\"discoverable\":true,\"version\":0}")
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  void simultaneousCreationReturnsOneStablePublicIdentity() throws Exception {
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<SocialDirectoryController.Settings> action = () -> {
        gate.await();
        return service.update(
          "concurrent-account",
          new SocialDirectoryController.Edit("동시회원", true, 0L)
        );
      };
      var first = pool.submit(action);
      var second = pool.submit(action);
      gate.countDown();
      var a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
      var b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
      org.assertj.core.api.Assertions.assertThat(a.memberId()).isEqualTo(b.memberId());
      org.assertj.core.api.Assertions.assertThat(a.version()).isEqualTo(1);
      org.assertj.core.api.Assertions.assertThat(b.version()).isEqualTo(1);
    }
  }
}
