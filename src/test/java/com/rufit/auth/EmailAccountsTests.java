package com.rufit.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Path;
import java.time.*;
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
@ActiveProfiles({"social","test"})
class EmailAccountsTests {
  static final Path KEY=SessionFoundationTests.key();
  static final String PASSWORD="Rufit test password 2026!";
  @Autowired EmailAccounts emails;
  @Autowired AuthSessions sessions;
  @Autowired LoginAttempts attempts;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired TransientSecrets transientSecrets;
  @MockitoBean EmailDelivery mail;
  String token;
  @DynamicPropertySource static void configuration(DynamicPropertyRegistry r) {
    r.add("rufit.social.transient-key",()->Base64.getEncoder().encodeToString(new byte[32]));
    r.add("rufit.session.issuer",()->"https://session.rufit.test");
    r.add("rufit.session.audience",()->"rufit-api");
    r.add("rufit.session.key-id",()->"email-test");
    r.add("rufit.session.private-key-path",()->KEY.toString());
  }
  @BeforeEach void clear() {
    jdbc.update("truncate auth_account_merges,auth_email_credentials,auth_email_requests,auth_login_attempts,auth_rate_events,auth_refresh_tokens,auth_sessions,auth_identities,auth_accounts");
    doAnswer(call->{token=call.getArgument(1);return null;}).when(mail).send(anyString(),anyString(),anyString());
  }
  SessionView register(String email) {
    var request=emails.request(email,"REGISTER",null,null,"email-test");
    return emails.confirm(new EmailAccountController.Confirm(request.requestId(),token,PASSWORD),"REGISTER",null,null,"email-test");
  }
  @Test void signupLoginAndOneTimeProof() throws Exception {
    var request=emails.request(" Person@Example.test ","REGISTER",null,null,"signup");
    var proof=new EmailAccountController.Confirm(request.requestId(),token,PASSWORD);
    var created=emails.confirm(proof,"REGISTER",null,null,"signup");
    var login=emails.login("PERSON@example.test",PASSWORD,"login");
    assertThat(login.account().id()).isEqualTo(created.account().id());
    assertThat(jdbc.queryForObject("select password_hash from auth_email_credentials",String.class)).doesNotContain(PASSWORD);
    assertThatThrownBy(()->emails.confirm(proof,"REGISTER",null,null,"signup")).isInstanceOf(AuthFailure.class);
    assertThatThrownBy(()->emails.login("person@example.test","wrong password","login")).isInstanceOf(AuthFailure.class);
    mvc.perform(get("/api/v1/account/login-methods").header("Authorization","Bearer "+login.accessToken()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("person@example.test"));
    mvc.perform(get("/api/v1/account/login-methods")).andExpect(status().isUnauthorized());
  }
  @Test void socialMemberCanAddEmailWithoutChangingOwner() {
    var original=sessions.open("KAKAO","https://kauth.kakao.com","email-link-person");
    var request=emails.request("linked@example.test","LINK",original.account().id(),original.sessionId(),"link");
    var proof=new EmailAccountController.Confirm(request.requestId(),token,PASSWORD);
    var otherSession=sessions.open("KAKAO","https://kauth.kakao.com","email-link-person");
    assertThatThrownBy(()->emails.confirm(proof,"LINK",original.account().id(),otherSession.sessionId(),"link")).isInstanceOf(AuthFailure.class);
    emails.confirm(proof,"LINK",original.account().id(),original.sessionId(),"link");
    assertThat(emails.login("linked@example.test",PASSWORD,"login").account().id()).isEqualTo(original.account().id());
    assertThat(emails.methods(original.account().id()).providers()).containsExactly("KAKAO");
  }
  @Test void resetRevokesExistingAccessAndRefresh() {
    var before=register("reset@example.test");
    var request=emails.request("reset@example.test","RESET",null,null,"reset");
    emails.confirm(new EmailAccountController.Confirm(request.requestId(),token,"New secure password 2026!"),"RESET",null,null,"reset");
    assertThatThrownBy(()->sessions.authenticate(before.accessToken())).isInstanceOf(AuthFailure.class);
    assertThatThrownBy(()->sessions.refresh(before.refreshToken())).isInstanceOf(AuthFailure.class);
    assertThatThrownBy(()->emails.login("reset@example.test",PASSWORD,"login")).isInstanceOf(AuthFailure.class);
    assertThat(emails.login("reset@example.test","New secure password 2026!","login").account().id()).isEqualTo(before.account().id());
  }
  @Test void expiredProofAndEmailConflictDoNotOverwriteCredentials() {
    var original=register("duplicate@example.test");
    var request=emails.request("duplicate@example.test","REGISTER",null,null,"duplicate");
    assertThatThrownBy(()->emails.confirm(new EmailAccountController.Confirm(request.requestId(),token,PASSWORD),"REGISTER",null,null,"duplicate")).isInstanceOf(AuthFailure.class);
    assertThat(emails.login("duplicate@example.test",PASSWORD,"login").account().id()).isEqualTo(original.account().id());
    var expired=emails.request("expired@example.test","REGISTER",null,null,"expired");
    jdbc.update("update auth_email_requests set expires_at=now()-interval '1 second' where id=?",expired.requestId());
    assertThatThrownBy(()->emails.confirm(new EmailAccountController.Confirm(expired.requestId(),token,PASSWORD),"REGISTER",null,null,"expired")).isInstanceOf(AuthFailure.class);
  }
  @Test void socialLinkCannotBeConsumedAsLoginOrByAnotherSession() {
    var original=register("social@example.test");
    String verifier=AuthCrypto.secret(), handoff=AuthCrypto.secret();
    UUID attempt=ready(original.sessionId(),verifier,handoff,"new-social");
    var exchange=new AuthController.Exchange(attempt,handoff,verifier);
    assertThatThrownBy(()->attempts.exchange(exchange,"proof")).isInstanceOf(AuthFailure.class);
    var other=emails.login("social@example.test",PASSWORD,"login");
    assertThatThrownBy(()->attempts.exchangeLink(exchange,"proof",other.account().id(),other.sessionId())).isInstanceOf(AuthFailure.class);
    attempts.exchangeLink(exchange,"proof",original.account().id(),original.sessionId());
    assertThat(sessions.open("GOOGLE","https://accounts.google.com","new-social").account().id()).isEqualTo(original.account().id());
    assertThatThrownBy(()->attempts.exchangeLink(exchange,"proof",original.account().id(),original.sessionId())).isInstanceOf(AuthFailure.class);
  }
  @Test void existingSocialAccountCannotBeMoved() {
    var original=register("conflict@example.test");
    var owner=sessions.open("GOOGLE","https://accounts.google.com","taken-social");
    String verifier=AuthCrypto.secret(),handoff=AuthCrypto.secret();
    UUID id=ready(original.sessionId(),verifier,handoff,"taken-social");
    assertThatThrownBy(()->attempts.exchangeLink(new AuthController.Exchange(id,handoff,verifier),"conflict",original.account().id(),original.sessionId())).isInstanceOf(AuthFailure.class);
    assertThat(sessions.open("GOOGLE","https://accounts.google.com","taken-social").account().id()).isEqualTo(owner.account().id());
  }
  @Test void kakaoEmailHintRequiresMatchingSessionAndProof() {
    var original=register("hint@example.test");
    var other=register("other-hint@example.test");
    var existing=sessions.open("KAKAO","https://kauth.kakao.com","987654321");
    String verifier=AuthCrypto.secret(),handoff=AuthCrypto.secret();
    UUID id=ready(original.sessionId(),verifier,handoff,"987654321");
    jdbc.update("update auth_login_attempts set provider='KAKAO',identity_issuer='https://kauth.kakao.com',identity_email_cipher=? where id=?",
      transientSecrets.encrypt(id,"kakao@example.test"),id);
    var exchange=new AuthController.Exchange(id,handoff,verifier);
    assertThatThrownBy(()->attempts.exchangeLink(exchange,"wrong-session",other.account().id(),other.sessionId()))
      .isInstanceOfSatisfying(AuthFailure.class,e->assertThat(e.verifiedKakaoEmail()).isNull());
    assertThatThrownBy(()->attempts.exchangeLink(new AuthController.Exchange(id,handoff,AuthCrypto.secret()),"wrong-proof",original.account().id(),original.sessionId()))
      .isInstanceOfSatisfying(AuthFailure.class,e->assertThat(e.verifiedKakaoEmail()).isNull());
    assertThatThrownBy(()->attempts.exchangeLink(exchange,"valid-proof",original.account().id(),original.sessionId()))
      .isInstanceOfSatisfying(AuthFailure.class,e->assertThat(e.verifiedKakaoEmail()).isEqualTo("kakao@example.test"));
    assertThat(sessions.open("KAKAO","https://kauth.kakao.com","987654321").account().id()).isEqualTo(existing.account().id());
  }
  @Test @org.springframework.transaction.annotation.Transactional
  void mergePreservesPostsPhotosWorkoutsFriendsAndRevokesSource() {
    var target=sessions.open("KAKAO","https://kauth.kakao.com","merge-target");
    var source=sessions.open("GOOGLE","https://accounts.google.com","merge-source");
    String to=target.account().id();
    String from=source.account().id();
    UUID a=UUID.randomUUID(),b=UUID.randomUUID(),friend=UUID.randomUUID(),post=UUID.randomUUID(),workout=UUID.randomUUID();
    jdbc.update("insert into social_directory(owner_name,member_id,nickname) values(?,?,'대상'),(?,?,'원본'),(?,?,'친구')",to,a,from,b,"test-friend-"+friend,friend);
    for(UUID m:List.of(a,b)) jdbc.update("insert into friendships values(least(?::uuid,?::uuid),greatest(?::uuid,?::uuid))",m,friend,m,friend);
    jdbc.update("insert into journal_posts(id,member_id,source_id,memo,workout_date,summary,visibility,version,content_hash) values(?,?,'test-source','보존할 메모',current_date,'운동','PRIVATE',1,?)",post,b,"a".repeat(64));
    jdbc.update("insert into journal_photos values(?,0,?)",post,new byte[]{1,2,3});
    jdbc.update("insert into workouts(id,owner_name,started_at,finished_at) values(?,?,now(),now())",workout,from);
    String verifier=AuthCrypto.secret(),handoff=AuthCrypto.secret();
    UUID attempt=ready(target.sessionId(),verifier,handoff,"merge-source");
    var exchange=new AuthController.Exchange(attempt,handoff,verifier);
    attempts.exchangeMerge(exchange,"merge",target.account().id(),target.sessionId());
    assertThat(jdbc.queryForObject("select member_id from journal_posts where id=?",UUID.class,post)).isEqualTo(a);
    assertThat(jdbc.queryForObject("select content from journal_photos where post_id=?",byte[].class,post)).containsExactly(1,2,3);
    assertThat(jdbc.queryForObject("select owner_name from workouts where id=?",String.class,workout)).isEqualTo(to);
    assertThat(jdbc.queryForObject("select count(*) from friendships where first_id=? or second_id=?",Integer.class,a,a)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select revoked_at is not null from auth_sessions where id=?",Boolean.class,source.sessionId())).isTrue();
    assertThat(sessions.open("GOOGLE","https://accounts.google.com","merge-source").account().id()).isEqualTo(target.account().id());
    assertThatThrownBy(()->attempts.exchangeMerge(exchange,"repeat",target.account().id(),target.sessionId())).isInstanceOf(AuthFailure.class);
  }

  /** 두 소셜 계정을 통합한 뒤 이메일을 추가해도 세 로그인 방식은 같은 소유자를 유지합니다. */
  @Test void mergedSocialAccountCanAlsoLinkEmail() {
    var kakao=sessions.open("KAKAO","https://kauth.kakao.com","three-method-kakao");
    sessions.open("GOOGLE","https://accounts.google.com","three-method-google");
    String verifier=AuthCrypto.secret(),handoff=AuthCrypto.secret();
    UUID attempt=ready(kakao.sessionId(),verifier,handoff,"three-method-google");
    attempts.exchangeMerge(new AuthController.Exchange(attempt,handoff,verifier),"three-merge",kakao.account().id(),kakao.sessionId());
    var request=emails.request("three-method@example.test","LINK",kakao.account().id(),kakao.sessionId(),"three-link");
    emails.confirm(new EmailAccountController.Confirm(request.requestId(),token,PASSWORD),"LINK",kakao.account().id(),kakao.sessionId(),"three-link");
    assertThat(emails.methods(kakao.account().id()).providers()).containsExactlyInAnyOrder("KAKAO","GOOGLE");
    assertThat(emails.methods(kakao.account().id()).email()).isEqualTo("three-method@example.test");
    assertThat(emails.login("three-method@example.test",PASSWORD,"three-login").account().id()).isEqualTo(kakao.account().id());
    assertThat(sessions.open("GOOGLE","https://accounts.google.com","three-method-google").account().id()).isEqualTo(kakao.account().id());
    assertThat(sessions.open("KAKAO","https://kauth.kakao.com","three-method-kakao").account().id()).isEqualTo(kakao.account().id());
  }

  @Test void mergeRejectsWrongProofAndDuplicateProviders() {
    var target=sessions.open("KAKAO","https://kauth.kakao.com","merge-conflict-target");
    var source=sessions.open("GOOGLE","https://accounts.google.com","merge-conflict-source");
    jdbc.update("insert into auth_identities(provider,issuer,subject,account_id) select 'KAKAO','https://kauth.kakao.com','another-kakao',id from auth_accounts where owner_key=?",source.account().id());
    String verifier=AuthCrypto.secret(),handoff=AuthCrypto.secret();
    UUID attempt=ready(target.sessionId(),verifier,handoff,"merge-conflict-source");
    assertThatThrownBy(()->attempts.exchangeMerge(new AuthController.Exchange(attempt,handoff,AuthCrypto.secret()),"bad-merge",target.account().id(),target.sessionId())).isInstanceOf(AuthFailure.class);
    assertThatThrownBy(()->attempts.exchangeMerge(new AuthController.Exchange(attempt,handoff,verifier),"conflict-merge",target.account().id(),target.sessionId()))
      .isInstanceOfSatisfying(AuthFailure.class,e->assertThat(e.code()).isEqualTo("ACCOUNT_MERGE_CONFLICT"));
    assertThat(sessions.open("GOOGLE","https://accounts.google.com","merge-conflict-source").account().id()).isEqualTo(source.account().id());
    assertThat(jdbc.queryForObject("select count(*) from auth_account_merges",Integer.class)).isZero();
  }

  @Test void overlappingMeasurementsRollBackEntireMerge() {
    var target=sessions.open("KAKAO","https://kauth.kakao.com","rollback-target");
    var source=sessions.open("GOOGLE","https://accounts.google.com","rollback-source");
    UUID workout=UUID.randomUUID();
    try {
      for(String owner:List.of(target.account().id(),source.account().id()))
        jdbc.update("insert into measurements(owner_name,kind,record_date,version,weight) values(?,'weight',current_date,1,70)",owner);
      jdbc.update("insert into workouts(id,owner_name,started_at,finished_at) values(?,?,now(),now())",workout,source.account().id());
      String verifier=AuthCrypto.secret(),handoff=AuthCrypto.secret();
      UUID attempt=ready(target.sessionId(),verifier,handoff,"rollback-source");
      assertThatThrownBy(()->attempts.exchangeMerge(new AuthController.Exchange(attempt,handoff,verifier),"overlap",target.account().id(),target.sessionId()))
        .isInstanceOfSatisfying(AuthFailure.class,e->assertThat(e.code()).isEqualTo("ACCOUNT_MERGE_CONFLICT"));
      assertThat(jdbc.queryForObject("select owner_name from workouts where id=?",String.class,workout)).isEqualTo(source.account().id());
      assertThat(jdbc.queryForObject("select count(*) from auth_account_merges",Integer.class)).isZero();
      assertThat(jdbc.queryForObject("select status from auth_login_attempts where id=?",String.class,attempt)).isEqualTo("READY");
    } finally {
      jdbc.update("delete from measurements where owner_name in (?,?)",target.account().id(),source.account().id());
      jdbc.update("delete from workouts where id=?",workout);
    }
  }

  private UUID ready(UUID session,String verifier,String handoff,String subject) {
    UUID id=UUID.randomUUID();
    jdbc.update("insert into auth_login_attempts(id,provider,client,return_uri,ip_hash,challenge,state_hash,nonce_hash,status,expires_at,handoff_hash,handoff_expires_at,identity_issuer,identity_subject,link_session_id) values(?,'GOOGLE','IOS','rufit://auth/callback',?,?,?,?,'READY',now()+interval '5 minutes',?,now()+interval '1 minute','https://accounts.google.com',?,?)",
      id,AuthCrypto.hash("test"),AuthCrypto.challenge(verifier),AuthCrypto.hash(AuthCrypto.secret()),AuthCrypto.hash(AuthCrypto.secret()),AuthCrypto.hash(handoff),subject,session);
    return id;
  }
}
