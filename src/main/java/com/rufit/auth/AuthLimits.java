package com.rufit.auth;

import java.sql.Timestamp;
import java.time.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("social")
public class AuthLimits {

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public AuthLimits(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.clock = clock;
    transaction = new TransactionTemplate(manager);
    // 실패 요청의 카운터도 남기며 업무/시도 트랜잭션 롤백과 분리합니다.
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public void take(String bucket, int limit, int seconds) {
    transaction.executeWithoutResult(status -> {
      String key = AuthCrypto.hash(bucket);
      Instant now = clock.instant();
      jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,1))", key);
      jdbc.update(
        "delete from auth_rate_events where bucket_hash=? and occurred_at<=?",
        key,
        Timestamp.from(now.minusSeconds(seconds))
      );
      var times = jdbc.query(
        "select occurred_at from auth_rate_events where bucket_hash=? order by occurred_at",
        (rs, n) -> rs.getTimestamp(1).toInstant(),
        key
      );
      if (times.size() >= limit) {
        long millis = Duration.between(
          now,
          times.get(times.size() - limit).plusSeconds(seconds)
        ).toMillis();
        throw new AuthFailure(429, "RATE_LIMITED", Math.max(1, (millis + 999) / 1000));
      }
      jdbc.update(
        "insert into auth_rate_events(bucket_hash,occurred_at) values(?,?)",
        key,
        Timestamp.from(now)
      );
    });
  }
}
