package com.rufit.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 이메일 소유 증명, 자격 저장 및 세션 발급을 담당합니다. 회원 기록의 소유키는 변경하지 않습니다. */
@Service
@Profile("social")
public class EmailAccounts {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final AuthSessions sessions;
  private final AuthLimits limits;
  private final EmailDelivery mail;
  private final TransactionTemplate tx;
  // 개별 salt 16바이트와 SHA-256 600,000회로 원문을 복원할 수 없는 해시를 저장합니다.
  private final Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder("", 16, 600000,
    Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
  private final String dummyHash;

  public EmailAccounts(JdbcTemplate jdbc, Clock clock, AuthSessions sessions,
      AuthLimits limits, EmailDelivery mail, PlatformTransactionManager manager) {
    this.jdbc=jdbc; this.clock=clock; this.sessions=sessions; this.limits=limits; this.mail=mail;
    tx=new TransactionTemplate(manager);
    dummyHash=encoder.encode(AuthCrypto.secret());
  }

  static String email(String value) {
    if (value == null) throw new AuthFailure(400,"INVALID_INPUT");
    String result=value.strip().toLowerCase(Locale.ROOT);
    if (result.length()>254 || !result.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}"))
      throw new AuthFailure(400,"INVALID_INPUT");
    return result;
  }

  private void password(String value) {
    if (value==null || value.length()<15 || value.length()>128 || value.isBlank()
      || Set.of("password123456789", "123456789012345", "1234567890123456", "qwertyuiop123456").contains(value.toLowerCase(Locale.ROOT)))
      throw new AuthFailure(400,"INVALID_INPUT");
  }

  public Requested request(String rawEmail, String purpose, String owner, UUID session, String ip) {
    String email=email(rawEmail);
    limits.take("email-request:"+email,3,900);
    limits.take("email-request-ip:"+ip,20,3600);
    UUID id=UUID.randomUUID();
    String token=AuthCrypto.secret();
    Instant expires=clock.instant().plusSeconds(600);
    tx.executeWithoutResult(status -> {
      if (purpose.equals("LINK")) sessions.requireAccount(session,owner);
      jdbc.update("insert into auth_email_requests(id,email,purpose,token_hash,session_id,expires_at) values(?,?,?,?,?,?)",
        id,email,purpose,AuthCrypto.hash(token),session,Timestamp.from(expires));
    });
    // 계정 존재 여부를 HTTP 응답에 노출하지 않고 해당 이메일로만 인증값을 보냅니다.
    try { mail.send(email,token,purpose); }
    catch (AuthFailure error) {
      jdbc.update("update auth_email_requests set consumed=true where id=?",id);
      throw error;
    }
    return new Requested(id,expires);
  }

  public SessionView confirm(EmailAccountController.Confirm input, String purpose,
      String owner, UUID session, String ip) {
    password(input.password());
    if (!AuthCrypto.secretFormat(input.verificationToken())) throw new AuthFailure(400,"INVALID_INPUT");
    limits.take("email-proof:"+input.requestId()+":"+ip,5,60);
    String hash=encoder.encode(input.password());
    return tx.execute(status -> {
      var rows=jdbc.query("select email,purpose,token_hash,session_id,expires_at,consumed from auth_email_requests where id=? for update",
        (rs,n)->new Proof(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class),rs.getTimestamp(5).toInstant(),rs.getBoolean(6)),input.requestId());
      if (rows.isEmpty()) throw new AuthFailure(401,"INVALID_EMAIL_PROOF");
      Proof proof=rows.getFirst();
      if (!proof.purpose.equals(purpose) || !AuthCrypto.same(proof.hash,AuthCrypto.hash(input.verificationToken()))
        || !clock.instant().isBefore(proof.expires)) throw new AuthFailure(401,"INVALID_EMAIL_PROOF");
      if (proof.consumed) throw new AuthFailure(409,"LOGIN_METHOD_CONFLICT");
      if (purpose.equals("LINK") && !Objects.equals(proof.session,session)) throw AuthFailure.proof();
      // 같은 이메일의 신규 가입과 연결을 잠금으로 직렬화합니다.
      jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,7))",proof.email);
      var existing=credentials(proof.email);
      UUID account;
      String actualOwner=owner;
      if (purpose.equals("REGISTER")) {
        if (!existing.isEmpty()) throw new AuthFailure(409,"LOGIN_METHOD_CONFLICT");
        account=UUID.randomUUID(); actualOwner="email:"+account;
        jdbc.update("insert into auth_accounts(id,owner_key,created_at) values(?,?,?)",account,actualOwner,Timestamp.from(clock.instant()));
      } else if (purpose.equals("LINK")) {
        account=sessions.requireAccount(session,owner);
        if (!existing.isEmpty() || Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from auth_email_credentials where account_id=?)",Boolean.class,account)))
          throw new AuthFailure(409,"LOGIN_METHOD_CONFLICT");
      } else {
        if (existing.isEmpty()) throw new AuthFailure(401,"INVALID_EMAIL_PROOF");
        account=existing.getFirst().id;
        jdbc.update("update auth_email_credentials set password_hash=?,updated_at=? where account_id=?",hash,Timestamp.from(clock.instant()),account);
        jdbc.update("update auth_sessions set revoked_at=coalesce(revoked_at,?) where account_id=?",Timestamp.from(clock.instant()),account);
      }
      if (!purpose.equals("RESET")) jdbc.update("insert into auth_email_credentials(account_id,email,password_hash,updated_at) values(?,?,?,?)",
        account,proof.email,hash,Timestamp.from(clock.instant()));
      // 오래된 인증값으로 다시 변경하지 못하도록 같은 이메일의 미사용 요청도 소비합니다.
      jdbc.update("update auth_email_requests set consumed=true where email=?",proof.email);
      return purpose.equals("REGISTER") ? sessions.openAccount(account,actualOwner,true) : null;
    });
  }

  public SessionView login(String rawEmail,String password,String ip) {
    String email=email(rawEmail);
    if (password==null || password.length()>128) throw new AuthFailure(400,"INVALID_INPUT");
    limits.take("email-login:"+email+":"+ip,5,60);
    limits.take("email-login-ip:"+ip,60,60);
    var rows=credentials(email);
    Credential credential=rows.isEmpty()?null:rows.getFirst();
    boolean matches=encoder.matches(password,credential==null?dummyHash:credential.hash);
    if (credential==null || !matches) throw new AuthFailure(401,"INVALID_CREDENTIALS");
    return tx.execute(status -> {
      // 비밀번호 재설정과 경합하면 이전 비밀번호로 새 세션을 발급하지 않습니다.
      var current=jdbc.query("select password_hash from auth_email_credentials where account_id=? for update",(rs,n)->rs.getString(1),credential.id);
      if (current.isEmpty() || !current.getFirst().equals(credential.hash)) throw new AuthFailure(401,"INVALID_CREDENTIALS");
      return sessions.openAccount(credential.id,credential.owner,false);
    });
  }

  public Methods methods(String owner) {
    List<String> emails=jdbc.query("select e.email from auth_email_credentials e join auth_accounts a on a.id=e.account_id where a.owner_key=?",(rs,n)->rs.getString(1),owner);
    List<String> providers=jdbc.query("select distinct i.provider from auth_identities i join auth_accounts a on a.id=i.account_id where a.owner_key=? order by i.provider",(rs,n)->rs.getString(1),owner);
    return new Methods(emails.isEmpty()?null:emails.getFirst(),providers);
  }
  private List<Credential> credentials(String email) {
    return jdbc.query("select a.id,a.owner_key,e.password_hash from auth_email_credentials e join auth_accounts a on a.id=e.account_id where e.email=?",
      (rs,n)->new Credential(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)),email);
  }
  public record Requested(UUID requestId,Instant expiresAt) {}
  public record Methods(String email,List<String> providers) {}
  private record Credential(UUID id,String owner,String hash) { public String toString(){return "Credential[redacted]";} }
  private record Proof(String email,String purpose,String hash,UUID session,Instant expires,boolean consumed) { public String toString(){return "Proof[redacted]";} }
}
