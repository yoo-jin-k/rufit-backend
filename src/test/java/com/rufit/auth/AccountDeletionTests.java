package com.rufit.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rufit.social.SocialDirectoryController;
import com.rufit.social.SocialDirectoryService;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "social", "test" })
class AccountDeletionTests {

  static final Path KEY = SessionFoundationTests.key();
  static final String PASSWORD = "Rufit test password 2026!";

  @Autowired EmailAccounts emails;
  @Autowired AuthSessions sessions;
  @Autowired AccountDeletion deletion;
  @Autowired SocialDirectoryService directory;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @MockitoBean EmailDelivery mail;
  String token;

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry r) {
    r.add("rufit.social.transient-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    r.add("rufit.session.issuer", () -> "https://session.rufit.test");
    r.add("rufit.session.audience", () -> "rufit-api");
    r.add("rufit.session.key-id", () -> "deletion-test");
    r.add("rufit.session.private-key-path", () -> KEY.toString());
  }

  @BeforeEach
  void clear() {
    jdbc.update(
      "truncate auth_account_merges,auth_email_credentials,auth_email_requests,auth_login_attempts,auth_rate_events,auth_refresh_tokens,auth_sessions,auth_identities,auth_accounts"
    );
    jdbc.update("delete from social_reports");
    jdbc.update("delete from social_directory");
    jdbc.update("delete from workouts");
    jdbc.update("delete from user_profiles");
    doAnswer(call -> {
      token = call.getArgument(1);
      return null;
    })
      .when(mail)
      .send(anyString(), anyString(), anyString());
  }

  SessionView register(String email) {
    var request = emails.request(email, "REGISTER", null, null, "deletion-test");
    return emails.confirm(
      new EmailAccountController.Confirm(request.requestId(), token, PASSWORD),
      "REGISTER",
      null,
      null,
      "deletion-test"
    );
  }

  /** 세션의 계정 식별값이 곧 개인 데이터의 owner_name입니다. */
  String owner(SessionView session) {
    return session.account().id();
  }

  @Test
  void deletesAccountDataButKeepsReportsForRetention() throws Exception {
    var me = register("leave@example.test");
    var other = register("stay@example.test");
    String mine = owner(me), theirs = owner(other);
    UUID a = directory.update(mine, new SocialDirectoryController.Edit("떠나는회원", true, 0L)).memberId();
    UUID b = directory.update(theirs, new SocialDirectoryController.Edit("남는회원", true, 0L)).memberId();
    jdbc.update("insert into friendships values(?,?)", a.compareTo(b) < 0 ? a : b, a.compareTo(b) < 0 ? b : a);
    jdbc.update("insert into social_reports(id,reporter_id,target_id,reason) values(?,?,?,'SPAM')", UUID.randomUUID(), a, b);
    jdbc.update("insert into social_reports(id,reporter_id,target_id,reason) values(?,?,?,'OTHER')", UUID.randomUUID(), b, a);
    jdbc.update(
      "insert into workouts(id,owner_name,started_at) values(?,?,now())",
      UUID.randomUUID(),
      mine
    );
    jdbc.update(
      "insert into user_profiles(owner_name,creation_key,nickname,bio,version) values(?,?,'떠나는회원','',1)",
      mine,
      UUID.randomUUID()
    );

    mvc.perform(delete("/api/v1/account")).andExpect(status().isUnauthorized());
    mvc
      .perform(delete("/api/v1/account").header("Authorization", "Bearer " + me.accessToken()))
      .andExpect(status().isNoContent());

    // 계정·로그인 수단·세션이 모두 지워져 기존 토큰과 비밀번호로 다시 들어올 수 없습니다.
    assertThat(jdbc.queryForObject("select count(*) from auth_accounts where owner_key=?", Long.class, mine)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from auth_email_credentials where email='leave@example.test'", Long.class)).isZero();
    assertThatThrownBy(() -> sessions.authenticate(me.accessToken())).isInstanceOf(AuthFailure.class);
    assertThatThrownBy(() -> sessions.refresh(me.refreshToken())).isInstanceOf(AuthFailure.class);
    assertThatThrownBy(() -> emails.login("leave@example.test", PASSWORD, "login")).isInstanceOf(AuthFailure.class);

    // owner_name으로 저장한 개인 데이터가 어느 테이블에도 남지 않습니다.
    List<String> tables = jdbc.queryForList(
      "select table_name from information_schema.columns where table_schema=current_schema() and column_name='owner_name'",
      String.class
    );
    assertThat(tables).contains("workouts", "user_profiles", "social_directory", "measurements");
    for (String table : tables) assertThat(
      jdbc.queryForObject("select count(*) from " + table + " where owner_name=?", Long.class, mine)
    ).as(table).isZero();
    assertThat(jdbc.queryForObject("select count(*) from friendships", Long.class)).isZero();

    // 다른 회원은 그대로이고, 신고 기록은 탈퇴 시각과 함께 남습니다.
    assertThat(jdbc.queryForObject("select count(*) from social_directory where member_id=?", Long.class, b)).isOne();
    assertThat(jdbc.queryForObject("select count(*) from social_reports where member_deleted_at is not null", Long.class)).isEqualTo(2);
    assertThatCode(() -> sessions.authenticate(other.accessToken())).doesNotThrowAnyException();

    // 다시 부르면 이미 없는 계정이라 거절합니다.
    mvc
      .perform(delete("/api/v1/account").header("Authorization", "Bearer " + me.accessToken()))
      .andExpect(status().isUnauthorized());

    // 보관 기간이 지나면 신고 기록도 지웁니다.
    deletion.purgeReports();
    assertThat(jdbc.queryForObject("select count(*) from social_reports", Long.class)).isEqualTo(2);
    jdbc.update(
      "update social_reports set member_deleted_at=?",
      Timestamp.from(Instant.now().minus(AccountDeletion.REPORT_RETENTION).minus(1, ChronoUnit.DAYS))
    );
    deletion.purgeReports();
    assertThat(jdbc.queryForObject("select count(*) from social_reports", Long.class)).isZero();
  }
}
