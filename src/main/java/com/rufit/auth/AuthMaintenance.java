package com.rufit.auth;

import java.sql.Timestamp;
import java.time.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@Profile("social")
@EnableScheduling
class AuthMaintenance {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  AuthMaintenance(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** 오래된 비밀/카운터를 작은 묶음으로 정리합니다. 사용한 refresh 관계는 절대기한+30초까지 남깁니다. */
  @Scheduled(
    fixedDelayString = "${rufit.auth.cleanup-delay-ms:60000}",
    initialDelayString = "${rufit.auth.cleanup-delay-ms:60000}"
  )
  @Transactional
  public void purge() {
    Instant now = clock.instant();
    jdbc.update(
      "update auth_login_attempts set verifier_cipher=null where id in (select id from auth_login_attempts where expires_at<=? and verifier_cipher is not null limit 5000 for update skip locked)",
      Timestamp.from(now)
    );
    jdbc.update(
      "delete from auth_login_attempts where id in (select id from auth_login_attempts where expires_at<=? limit 5000 for update skip locked)",
      Timestamp.from(now.minus(Duration.ofDays(1)))
    );
    jdbc.update(
      "delete from auth_rate_events where id in (select id from auth_rate_events where occurred_at<=? limit 5000 for update skip locked)",
      Timestamp.from(now.minusSeconds(3600))
    );
    jdbc.update(
      "delete from auth_sessions where id in (select id from auth_sessions where absolute_expires_at<=? limit 5000 for update skip locked)",
      Timestamp.from(now.minusSeconds(30))
    );
  }
}
