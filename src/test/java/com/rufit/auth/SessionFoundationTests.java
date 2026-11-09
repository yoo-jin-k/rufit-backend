package com.rufit.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
  properties = { "springdoc.api-docs.enabled=true", "rufit.auth.cleanup-delay-ms=3600000" }
)
@AutoConfigureMockMvc
@ActiveProfiles({ "social", "test" })
class SessionFoundationTests {

  static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");
  static final Path KEY = key();

  @Autowired
  AuthSessions sessions;

  @Autowired
  SessionTokens tokens;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  MockMvc mvc;

  @TestConfiguration
  static class Config {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  static Path key() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      Path file = Files.createTempFile("rufit-auth-test-", ".pem");
      Files.writeString(
        file,
        "-----BEGIN PRIVATE KEY-----\n" +
          Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) +
          "\n-----END PRIVATE KEY-----\n"
      );
      file.toFile().deleteOnExit();
      return file;
    } catch (Exception e) {
      throw new IllegalStateException("test key setup failed");
    }
  }

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry r) {
    r.add("rufit.social.transient-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    r.add("rufit.session.issuer", () -> "https://session.rufit.test");
    r.add("rufit.session.audience", () -> "rufit-api");
    r.add("rufit.session.key-id", () -> "test-key");
    r.add("rufit.session.private-key-path", () -> KEY.toString());
  }

  @BeforeEach
  void clear() {
    jdbc.update("truncate auth_account_merges,auth_email_credentials,auth_email_requests,auth_refresh_tokens,auth_sessions,auth_identities,auth_accounts");
  }

  @AfterAll
  static void removeKey() throws Exception {
    Files.deleteIfExists(KEY);
  }

  SessionView open(String subject) {
    return sessions.open("GOOGLE", "https://accounts.google.com", subject);
  }

  @Test
  void preservesLegacyOwnerAndStoresOnlyRefreshHash() throws Exception {
    var first = open("same-person");
    var again = open("same-person");
    var other = open("other-person");
    assertThat(first.account().id())
      .isEqualTo(AuthCrypto.owner("https://accounts.google.com", "same-person"))
      .hasSize(69);
    assertThat(first.account().isNewAccount()).isTrue();
    assertThat(again.account().isNewAccount()).isFalse();
    assertThat(again.account().id()).isEqualTo(first.account().id());
    assertThat(other.account().id()).isNotEqualTo(first.account().id());
    assertThat(first.account().profileStatus()).isEqualTo("MISSING");
    assertThat(first.accessTokenExpiresAt()).isEqualTo(NOW.plusSeconds(600));
    assertThat(first.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
    assertThat(jdbc.queryForList("select token_hash from auth_refresh_tokens", String.class))
      .contains(AuthCrypto.hash(first.refreshToken()))
      .doesNotContain(first.refreshToken());
    mvc
      .perform(get("/api/v1/me").header("Authorization", "Bearer " + first.accessToken()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.id").value(first.account().id()))
      .andExpect(header().doesNotExist("Set-Cookie"));
    UUID workout = UUID.randomUUID();
    jdbc.update(
      "insert into workouts(id,owner_name,started_at,version) values(?,?,?,0)",
      workout,
      first.account().id(),
      java.sql.Timestamp.from(NOW)
    );
    try {
      mvc
        .perform(
          get("/api/v1/workouts/" + workout).header(
            "Authorization",
            "Bearer " + again.accessToken()
          )
        )
        .andExpect(status().isOk());
      mvc
        .perform(
          get("/api/v1/workouts/" + workout).header(
            "Authorization",
            "Bearer " + other.accessToken()
          )
        )
        .andExpect(status().isNotFound());
    } finally {
      jdbc.update("delete from workouts where id=?", workout);
    }
  }

  /** 회원 연결 후 다른 제공자로 로그인해도 응답·토큰·갱신이 같은 원장을 가리켜야 합니다. */
  @Test
  void linkedProviderUsesStoredOwnerForResponseAndAuthentication() throws Exception {
    var original = open("linked-original");
    UUID account = jdbc.queryForObject(
      "select id from auth_accounts where owner_key=?", UUID.class, original.account().id()
    );
    jdbc.update(
      "insert into auth_identities(provider,issuer,subject,account_id) values(?,?,?,?)",
      "KAKAO", "https://kauth.kakao.com", "linked-kakao", account
    );
    var linked = sessions.open("KAKAO", "https://kauth.kakao.com", "linked-kakao");
    assertThat(linked.account().id()).isEqualTo(original.account().id());
    assertThat(linked.account().isNewAccount()).isFalse();
    assertThat(sessions.authenticate(linked.accessToken())).isEqualTo(original.account().id());
    assertThat(sessions.refresh(linked.refreshToken()).account().id())
      .isEqualTo(original.account().id());
    assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
      .isEqualTo(1);
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + linked.accessToken()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.id").value(original.account().id()));
  }

  @Test
  void concurrentFirstLoginCreatesOneAccountAndDistinctGrants() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var barrier = new CyclicBarrier(2);
      Callable<SessionView> action = () -> {
        barrier.await();
        return open("concurrent-person");
      };
      var a = executor.submit(action);
      var b = executor.submit(action);
      var one = a.get(10, TimeUnit.SECONDS);
      var two = b.get(10, TimeUnit.SECONDS);
      assertThat(one.account().id()).isEqualTo(two.account().id());
      assertThat(one.account().isNewAccount()).isNotEqualTo(two.account().isNewAccount());
      assertThat(one.sessionId()).isNotEqualTo(two.sessionId());
      assertThat(
        jdbc.queryForObject("select count(*) from auth_accounts", Integer.class)
      ).isEqualTo(1);
    }
  }

  @Test
  void revokedOrIdleExpiredGrantRejectsAlreadySignedToken() throws Exception {
    var view = open("revoke");
    jdbc.update(
      "update auth_sessions set revoked_at=? where id=?",
      java.sql.Timestamp.from(NOW),
      view.sessionId()
    );
    mvc
      .perform(get("/api/v1/me").header("Authorization", "Bearer " + view.accessToken()))
      .andExpect(status().isUnauthorized())
      .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    var expired = open("expired");
    jdbc.update(
      "update auth_sessions set idle_expires_at=? where id=?",
      java.sql.Timestamp.from(NOW),
      expired.sessionId()
    );
    mvc
      .perform(get("/api/v1/me").header("Authorization", "Bearer " + expired.accessToken()))
      .andExpect(status().isUnauthorized());
  }

  @Test
  void invalidTokensMissingSessionAndBasicNeverAuthenticate() throws Exception {
    var view = open("claims");
    UUID account = jdbc.queryForObject(
      "select id from auth_accounts where owner_key=?",
      UUID.class,
      view.account().id()
    );
    var bad = List.of(
      view.accessToken().substring(0, view.accessToken().lastIndexOf('.') + 1) + "AAAA",
      tokens.issue(account, view.sessionId(), NOW.minusSeconds(900), NOW.minusSeconds(31)),
      tokens.issue(account, UUID.randomUUID(), NOW, NOW.plusSeconds(600)),
      tokens.issue(UUID.randomUUID(), view.sessionId(), NOW, NOW.plusSeconds(600)),
      "a".repeat(4097)
    );
    for (String value : bad)
      mvc
        .perform(get("/api/v1/me").header("Authorization", "Bearer " + value))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    mvc
      .perform(get("/api/v1/me").header("Authorization", "Basic dGVzdDp0ZXN0"))
      .andExpect(status().isUnauthorized());
  }

  @Test
  void storageQueryFailureIs503AndCannotFallBackToJwtAlone() throws Exception {
    var view = open("db-failure");
    jdbc.execute("alter table auth_sessions rename to auth_sessions_unavailable");
    try {
      mvc
        .perform(get("/api/v1/me").header("Authorization", "Bearer " + view.accessToken()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
    } finally {
      jdbc.execute("alter table auth_sessions_unavailable rename to auth_sessions");
    }
    mvc
      .perform(get("/api/v1/me").header("Authorization", "Bearer " + view.accessToken()))
      .andExpect(status().isOk());
  }

  @Test
  void onlyExplicitSocialProfileExportsBearerAndNoCsrfEndpoint() throws Exception {
    var view = open("openapi");
    mvc
      .perform(get("/v3/api-docs").header("Authorization", "Bearer " + view.accessToken()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
      .andExpect(jsonPath("$.paths['/api/v1/csrf']").doesNotExist());
  }

  @Test
  void secretsUseCanonicalEncodingAndRedactedRepresentation() {
    assertThat(AuthCrypto.secretFormat(AuthCrypto.secret())).isTrue();
    assertThat(AuthCrypto.secretFormat("A".repeat(42) + "B")).isFalse();
    assertThat(open("safe-print").toString()).isEqualTo("SessionView[redacted]");
  }
}
