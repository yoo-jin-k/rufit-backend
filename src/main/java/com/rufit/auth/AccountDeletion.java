package com.rufit.auth;

import java.time.Clock;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴를 처리합니다. 계정·로그인 수단·세션과 운동 기록, 프로필, 오운완, 소셜 관계를 한 번에 지웁니다.
 * 신고 기록만 부정 이용 방지를 위해 탈퇴 시각을 남겨 두고 6개월 뒤 지웁니다.
 */
@Service
@Profile("social")
public class AccountDeletion {

  /** 탈퇴한 회원이 관련된 신고 기록을 남겨 두는 기간입니다. 개인정보처리방침의 보유 기간과 같아야 합니다. */
  static final Duration REPORT_RETENTION = Duration.ofDays(183);

  // owner_name으로 묶인 개인 데이터입니다. 운동·루틴을 먼저 지워야 직접 만든 종목을 지울 수 있습니다.
  private static final List<String> OWNER_TABLES = List.of(
    "workouts",
    "routines",
    "retired_workout_keys",
    "retired_routine_keys",
    "exercise_favorites",
    "exercise_rest_preferences",
    "rest_preferences",
    "unit_preferences",
    "measurements",
    "profile_goals",
    "user_profiles",
    "exercises"
  );

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public AccountDeletion(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional
  public void delete(String owner) {
    List<UUID> accounts = jdbc.query(
      "select id from auth_accounts where owner_key=? for update",
      (rs, n) -> rs.getObject(1, UUID.class),
      owner
    );
    if (accounts.isEmpty()) throw new AuthFailure(401, "SESSION_EXPIRED");
    // 이 계정에 합쳐진 예전 계정들도 함께 지웁니다(합치기가 여러 번 이어졌을 수 있습니다).
    List<UUID> all = new ArrayList<>(accounts);
    for (int i = 0; i < all.size(); i++) {
      for (UUID source : jdbc.query(
        "select source_account from auth_account_merges where target_account=?",
        (rs, n) -> rs.getObject(1, UUID.class),
        all.get(i)
      )) if (!all.contains(source)) all.add(source);
    }
    List<String> owners = new ArrayList<>();
    for (UUID id : all) owners.add(
      jdbc.queryForObject("select owner_key from auth_accounts where id=?", String.class, id)
    );

    Timestamp now = Timestamp.from(clock.instant());
    for (String name : owners) {
      List<UUID> members = jdbc.query(
        "select member_id from social_directory where owner_name=?",
        (rs, n) -> rs.getObject(1, UUID.class),
        name
      );
      for (UUID member : members) jdbc.update(
        "update social_reports set member_deleted_at=coalesce(member_deleted_at,?) where reporter_id=? or target_id=?",
        now,
        member,
        member
      );
      // 소셜 회원 행을 지우면 프로필·친구·신청·차단·좋아요·응원·알림·오운완과 사진이 함께 지워집니다.
      jdbc.update("delete from social_directory where owner_name=?", name);
      for (String table : OWNER_TABLES) jdbc.update("delete from " + table + " where owner_name=?", name);
    }

    for (UUID id : all) {
      List<String> emails = jdbc.query(
        "select email from auth_email_credentials where account_id=?",
        (rs, n) -> rs.getString(1),
        id
      );
      for (String email : emails) jdbc.update("delete from auth_email_requests where email=?", email);
      jdbc.update("delete from auth_email_credentials where account_id=?", id);
      jdbc.update("delete from auth_identities where account_id=?", id);
      // 세션을 지우면 갱신 토큰도 함께 지워지고, 남은 접근 토큰은 다음 요청부터 거절됩니다.
      jdbc.update("delete from auth_sessions where account_id=?", id);
    }
    for (UUID id : all) jdbc.update(
      "delete from auth_account_merges where source_account=? or target_account=?",
      id,
      id
    );
    for (UUID id : all) jdbc.update("delete from auth_accounts where id=?", id);
  }

  /** 보관 기간이 지난 탈퇴 회원의 신고 기록을 지웁니다. */
  @Scheduled(fixedDelayString = "${rufit.account.report-purge-delay-ms:3600000}")
  public void purgeReports() {
    jdbc.update(
      "delete from social_reports where member_deleted_at<=?",
      Timestamp.from(clock.instant().minus(REPORT_RETENTION))
    );
  }
}
