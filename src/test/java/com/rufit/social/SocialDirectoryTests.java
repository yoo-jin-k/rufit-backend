package com.rufit.social;

import static org.assertj.core.api.Assertions.assertThat;
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
      .andExpect(jsonPath("$.items[0].bio").isEmpty())
      .andExpect(jsonPath("$.items[0].avatar").isEmpty());
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

  /** 대소문자·앞뒤 공백만 다른 닉네임은 같은 닉네임으로 보고, 본인 닉네임은 계속 사용할 수 있어야 합니다. */
  @Test
  void nicknamesAreUniqueIgnoringCaseAndSurroundingSpaces() throws Exception {
    save("alice", "Rufit", true, 0);
    mvc
      .perform(
        get("/api/v1/social/directory/nickname-availability")
          .param("nickname", "  rufit ")
          .with(user("bob"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.nickname").value("rufit"))
      .andExpect(jsonPath("$.available").value(false));
    mvc
      .perform(
        get("/api/v1/social/directory/nickname-availability")
          .param("nickname", "RUFIT")
          .with(user("alice"))
      )
      .andExpect(jsonPath("$.available").value(true));
    mvc
      .perform(
        get("/api/v1/social/directory/nickname-availability")
          .param("nickname", "새닉네임")
          .with(user("bob"))
      )
      .andExpect(jsonPath("$.available").value(true));
    mvc
      .perform(
        get("/api/v1/social/directory/nickname-availability").param("nickname", " a ").with(user("bob"))
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        put("/api/v1/social/directory/me")
          .with(user("bob"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"nickname\":\" RUFIT \",\"discoverable\":true,\"version\":0}")
      )
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("NICKNAME_TAKEN"));
    // 본인은 대소문자만 바꿔 다시 저장할 수 있습니다.
    save("alice", "RUFIT", true, 1);
  }

  /** DB 규칙도 같은 기준으로 중복을 막아 서비스 검사를 우회한 저장을 거부해야 합니다. */
  @Test
  void databaseRejectsDuplicateNicknames() {
    jdbc.update(
      "INSERT INTO social_directory(owner_name,member_id,nickname) VALUES ('a',gen_random_uuid(),'루핏회원')"
    );
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
      jdbc.update(
        "INSERT INTO social_directory(owner_name,member_id,nickname) VALUES ('b',gen_random_uuid(),' 루핏회원 ')"
      )
    ).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  static String pngDataUri(int width, int height) throws Exception {
    var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var out = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", out);
    return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(out.toByteArray());
  }

  void saveProfile(String owner, String body, int status) throws Exception {
    mvc
      .perform(
        put("/api/v1/social/directory/me/profile")
          .with(user(owner))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(body)
      )
      .andExpect(status().is(status));
  }

  /** 공개 프로필은 저장한 사람만, 작은 사진으로 검색·친구 화면에 보입니다. */
  @Test
  void publicProfileAppearsInSearchWithSmallAvatarAndCanBeCleared() throws Exception {
    saveProfile("alice", "{\"bio\":\"안녕\",\"avatar\":null}", 404);
    save("alice", "루핏하나", true, 0);
    save("carol", "루핏셋", true, 0);
    saveProfile("carol", "{\"bio\":\"  스쿼트 좋아요  \",\"avatar\":\"" + pngDataUri(800, 400) + "\"}", 200);
    saveProfile("carol", "{\"bio\":\"" + "가".repeat(81) + "\",\"avatar\":null}", 400);
    saveProfile("carol", "{\"bio\":\"x\",\"avatar\":\"data:text/plain;base64,AAAA\"}", 400);
    var result = mvc
      .perform(get("/api/v1/social/directory/search").param("q", "루핏셋").with(user("alice")))
      .andExpect(jsonPath("$.items[0].bio").value("스쿼트 좋아요"))
      .andExpect(jsonPath("$.items[0].avatar").value(org.hamcrest.Matchers.startsWith("data:image/jpeg;base64,")))
      .andReturn();
    String avatar = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.items[0].avatar");
    var image = javax.imageio.ImageIO.read(
      new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(avatar.substring(avatar.indexOf(',') + 1)))
    );
    assertThat(Math.max(image.getWidth(), image.getHeight())).isEqualTo(160);
    mvc
      .perform(get("/api/v1/social/directory/me").with(user("carol")))
      .andExpect(jsonPath("$.bio").value("스쿼트 좋아요"))
      .andExpect(jsonPath("$.hasAvatar").value(true));
    saveProfile("carol", "{\"bio\":\"\",\"avatar\":null}", 200);
    mvc
      .perform(get("/api/v1/social/directory/search").param("q", "루핏셋").with(user("alice")))
      .andExpect(jsonPath("$.items[0].bio").isEmpty())
      .andExpect(jsonPath("$.items[0].avatar").isEmpty());
  }
}
