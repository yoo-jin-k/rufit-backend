package com.rufit.auth;

import java.sql.*;
import java.time.*;
import java.util.*;
/*
 * 소셜 로그인 시작부터 제공자 응답 확인과 일회성 교환까지를 조율합니다.
 * PKCE·state·nonce 검증과 만료·재시도 처리는 인증 보안의 일부이므로 순서를 임의로 생략하지 않아야 합니다.
 */

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("social")
public class LoginAttempts {

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final SocialProviders providers;
  private final ProviderGateway gateway;
  private final TransientSecrets secrets;
  // 카카오 콘솔의 이메일 권한과 동의항목이 준비된 후에만 추가 동의를 요청합니다.
  @org.springframework.beans.factory.annotation.Value("${rufit.social.kakao.email-consent-enabled:false}")
  private boolean kakaoEmailConsentEnabled;
  private final AuthSessions sessions;
  private final AuthLimits limits;
  private final AccountMerge merger;

  public LoginAttempts(
    JdbcTemplate jdbc,
    Clock clock,
    PlatformTransactionManager manager,
    SocialProviders providers,
    ProviderGateway gateway,
    TransientSecrets secrets,
    AuthSessions sessions,
    AuthLimits limits,
    AccountMerge merger
  ) {
    this.jdbc = jdbc;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    this.providers = providers;
    this.gateway = gateway;
    this.secrets = secrets;
    this.sessions = sessions;
    this.limits = limits;
    this.merger = merger;
  }

  public AuthController.Started start(AuthController.Start input, String ip) {
    return startInternal(input,ip,null,null);
  }

  public AuthController.Started startLink(AuthController.Start input,String ip,String owner,UUID session) {
    return startInternal(input,ip,owner,session);
  }

  private AuthController.Started startInternal(AuthController.Start input, String ip,String owner,UUID session) {
    if (
      !AuthCrypto.secretFormat(input.codeChallenge()) || !"S256".equals(input.codeChallengeMethod())
    ) throw new AuthFailure(400, "INVALID_INPUT");
    var provider = providers.provider(input.provider());
    String returnUri = providers.returnUri(input.client(), input.returnRoute());
    limits.take("start-minute:" + ip, 20, 60);
    limits.take("start-hour:" + ip, 100, 3600);
    return tx.execute(status -> {
      if (session != null) sessions.requireAccount(session,owner);
      Instant now = clock.instant();
      String ipHash = AuthCrypto.hash(ip);
      jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,2))", ipHash);
      int active = jdbc.queryForObject(
        "select count(*) from auth_login_attempts where ip_hash=? and expires_at>? and status in ('PENDING','CLAIMED','READY')",
        Integer.class,
        ipHash,
        Timestamp.from(now)
      );
      if (active >= 30) {
        Instant next = jdbc
          .queryForObject(
            "select min(expires_at) from auth_login_attempts where ip_hash=? and expires_at>? and status in ('PENDING','CLAIMED','READY')",
            Timestamp.class,
            ipHash,
            Timestamp.from(now)
          )
          .toInstant();
        throw new AuthFailure(
          429,
          "RATE_LIMITED",
          Math.max(1, (Duration.between(now, next).toMillis() + 999) / 1000)
        );
      }
      UUID id = UUID.randomUUID();
      String state = AuthCrypto.secret(),
        nonce = AuthCrypto.secret(),
        verifier = AuthCrypto.secret();
      Instant expires = now.plusSeconds(300);
      jdbc.update(
        "insert into auth_login_attempts(id,provider,client,return_uri,ip_hash,challenge,state_hash,nonce_hash,verifier_cipher,status,expires_at) values(?,?,?,?,?,?,?,?,?,'PENDING',?)",
        id,
        input.provider(),
        input.client(),
        returnUri,
        ipHash,
        input.codeChallenge(),
        AuthCrypto.hash(state),
        AuthCrypto.hash(nonce),
        secrets.encrypt(id, verifier),
        Timestamp.from(expires)
      );
      if (session != null) jdbc.update("update auth_login_attempts set link_session_id=? where id=?",session,id);
      return new AuthController.Started(
        id,
        provider.authorize(state, nonce, AuthCrypto.challenge(verifier), session != null && kakaoEmailConsentEnabled),
        expires
      );
    });
  }

  public String callback(String providerName, String state, String code, String error) {
    if (
      !Set.of("google", "kakao").contains(providerName) ||
      !AuthCrypto.secretFormat(state) ||
      (code == null) == (error == null) ||
      (code != null && (code.isBlank() || code.length() > 2048)) ||
      (error != null && (error.isBlank() || error.length() > 128))
    ) throw new AuthFailure(400, "INVALID_INPUT");
    String name = providerName.toUpperCase(Locale.ROOT);
    // CLAIMED 상태를 먼저 커밋합니다. 외부 호출을 잠금 안에서 실행하거나 유실 뒤 다시 보내지 않습니다.
    Attempt attempt = tx.execute(status -> {
      var rows = jdbc.query(
        "select * from auth_login_attempts where state_hash=? for update",
        this::map,
        AuthCrypto.hash(state)
      );
      if (rows.isEmpty() || !rows.getFirst().provider().equals(name)) throw new AuthFailure(
        400,
        "INVALID_INPUT"
      );
      Attempt a = rows.getFirst();
      if (!clock.instant().isBefore(a.expires())) throw AuthFailure.proof();
      if (!a.status().equals("PENDING")) throw new AuthFailure(409, "LOGIN_ATTEMPT_CONFLICT");
      jdbc.update(
        "update auth_login_attempts set status=? where id=?",
        error == null ? "CLAIMED" : "FAILED",
        a.id()
      );
      if (error != null) jdbc.update(
        "update auth_login_attempts set verifier_cipher=null where id=?",
        a.id()
      );
      return a;
    });
    if (error != null) {
      if (!error.equals("access_denied")) throw AuthFailure.proof();
      return (
        attempt.returnUri() +
        "?" +
        SocialProviders.query(
          Map.of("attemptId", attempt.id().toString(), "error", "LOGIN_CANCELLED")
        )
      );
    }
    ProviderGateway.Identity identity;
    try {
      identity = gateway.exchange(
        providers.provider(name),
        code,
        secrets.decrypt(attempt.id(), attempt.cipher()),
        attempt.nonceHash()
      );
    } catch (AuthFailure failure) {
      tx.executeWithoutResult(s ->
        jdbc.update(
          "update auth_login_attempts set status='FAILED',verifier_cipher=null where id=? and status='CLAIMED'",
          attempt.id()
        )
      );
      throw failure;
    }
    String handoff = AuthCrypto.secret();
    tx.executeWithoutResult(status -> {
      Attempt current = jdbc
        .query("select * from auth_login_attempts where id=? for update", this::map, attempt.id())
        .getFirst();
      Instant now = clock.instant();
      if (
        !current.status().equals("CLAIMED") || !now.isBefore(current.expires())
      ) throw AuthFailure.proof();
      Instant expires = now.plusSeconds(60).isBefore(current.expires())
        ? now.plusSeconds(60)
        : current.expires();
      jdbc.update(
        "update auth_login_attempts set status='READY',verifier_cipher=null,handoff_hash=?,handoff_expires_at=?,identity_issuer=?,identity_subject=?,identity_email_cipher=? where id=?",
        AuthCrypto.hash(handoff),
        Timestamp.from(expires),
        identity.issuer(),
        identity.subject(),
        attempt.linkSession() != null && identity.email() != null
          ? secrets.encrypt(attempt.id(), identity.email()) : null,
        attempt.id()
      );
    });
    return (
      attempt.returnUri() +
      "?" +
      SocialProviders.query(Map.of("attemptId", attempt.id().toString(), "handoffCode", handoff))
    );
  }

  public SessionView exchange(AuthController.Exchange input, String ip) {
    return exchangeInternal(input,ip,null,null,false);
  }

  public void exchangeLink(AuthController.Exchange input,String ip,String owner,UUID session) {
    exchangeInternal(input,ip,owner,session,false);
  }

  public void exchangeMerge(AuthController.Exchange input,String ip,String owner,UUID session) {
    try { exchangeInternal(input,ip,owner,session,true); }
    catch (org.springframework.dao.DataIntegrityViolationException failure) {
      // 고유키·관계 충돌은 전체 롤백 후 데이터 내용을 노출하지 않는 오류로 안내합니다.
      throw AccountMerge.conflict();
    }
  }

  private SessionView exchangeInternal(AuthController.Exchange input, String ip,String owner,UUID session,boolean merge) {
    if (
      !AuthCrypto.secretFormat(input.handoffCode()) ||
      input.codeVerifier() == null ||
      !input.codeVerifier().matches("[A-Za-z0-9._~-]{43,128}")
    ) throw new AuthFailure(400, "INVALID_INPUT");
    ExchangeResult result = tx.execute(status -> {
      if (merge) merger.lock();
      var rows = jdbc.query(
        "select * from auth_login_attempts where id=? for update",
        this::map,
        input.attemptId()
      );
      if (rows.isEmpty()) return null;
      Attempt a = rows.getFirst();
      if (
        !AuthCrypto.same(a.handoffHash(), AuthCrypto.hash(input.handoffCode())) ||
        !AuthCrypto.same(a.challenge(), AuthCrypto.challenge(input.codeVerifier()))
      ) return null;
      Instant now = clock.instant();
      if (
        !now.isBefore(a.expires()) ||
        a.handoffExpires() == null ||
        !now.isBefore(a.handoffExpires())
      ) throw AuthFailure.proof();
      if (a.status().equals("CONSUMED")) throw new AuthFailure(409, "LOGIN_ATTEMPT_CONFLICT");
      if (!a.status().equals("READY")) throw AuthFailure.proof();
      // 연결용 교환은 시작한 세션에서만 소비할 수 있고 일반 로그인으로 전환할 수 없습니다.
      if (!Objects.equals(a.linkSession(),session)) throw AuthFailure.proof();
      SessionView view = null;
      if (session == null) view = sessions.open(a.provider(), a.issuer(), a.subject());
      else {
        UUID account=sessions.requireAccount(session,owner);
        String identityOwner=AuthCrypto.owner(a.issuer(),a.subject());
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",identityOwner);
        var existing=jdbc.query("select account_id from auth_identities where provider=? and issuer=? and subject=?",
          (rs,n)->rs.getObject(1,UUID.class),a.provider(),a.issuer(),a.subject());
        if (!existing.isEmpty() && !existing.getFirst().equals(account)) {
          if (merge) merger.merge(account,existing.getFirst(),session);
          else throw AuthFailure.verifiedLinkConflict(a.provider(), a.emailCipher() == null ? null : secrets.decrypt(a.id(), a.emailCipher()));
        }
        if (existing.isEmpty()) {
          if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from auth_identities where account_id=? and provider=?)",Boolean.class,account,a.provider())))
            throw AuthFailure.verifiedLinkConflict(a.provider(), a.emailCipher() == null ? null : secrets.decrypt(a.id(), a.emailCipher()));
          jdbc.update("insert into auth_identities(provider,issuer,subject,account_id) values(?,?,?,?)",a.provider(),a.issuer(),a.subject(),account);
        }
      }
      jdbc.update("update auth_login_attempts set status='CONSUMED' where id=?", a.id());
      return new ExchangeResult(view);
    });
    // 행 잠금/연결을 반환한 뒤 별도 카운터를 갱신해 연결 풀 교착을 피합니다.
    if (result == null) {
      limits.take("bad-handoff:" + input.attemptId() + ":" + ip, 5, 60);
      throw AuthFailure.proof();
    }
    return result.view();
  }

  private record ExchangeResult(SessionView view) {}

  private Attempt map(ResultSet rs, int row) throws SQLException {
    Timestamp handoff = rs.getTimestamp("handoff_expires_at");
    return new Attempt(
      rs.getObject("id", UUID.class),
      rs.getString("provider"),
      rs.getString("return_uri"),
      rs.getString("challenge"),
      rs.getString("nonce_hash"),
      rs.getString("verifier_cipher"),
      rs.getString("status"),
      rs.getTimestamp("expires_at").toInstant(),
      rs.getString("handoff_hash"),
      handoff == null ? null : handoff.toInstant(),
      rs.getString("identity_issuer"),
      rs.getString("identity_subject"),
      rs.getString("identity_email_cipher"),
      rs.getObject("link_session_id", UUID.class)
    );
  }

  private record Attempt(
    UUID id,
    String provider,
    String returnUri,
    String challenge,
    String nonceHash,
    String cipher,
    String status,
    Instant expires,
    String handoffHash,
    Instant handoffExpires,
    String issuer,
    String subject,
    String emailCipher,
    UUID linkSession
  ) {
    @Override
    public String toString() {
      return "Attempt[redacted]";
    }
  }
}
