package com.rufit.auth;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"springdoc.api-docs.enabled=true","rufit.auth.cleanup-delay-ms=3600000"})
@ActiveProfiles({"social","test"})
class LoginHttpTests {
    static final MutableClock CLOCK=new MutableClock();
    static final FakeOAuthServer PROVIDER=new FakeOAuthServer(CLOCK,0);
    static final Path KEY=SessionFoundationTests.key();
    static final AtomicInteger API_PORT=new AtomicInteger();
    static final HttpClient HTTP=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthMaintenance maintenance;
    @TestConfiguration static class Config {@Bean @Primary Clock testClock(){return CLOCK;}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("rufit.session.issuer",()->"https://session.rufit.test");r.add("rufit.session.audience",()->"rufit-api");
        r.add("rufit.session.key-id",()->"http-test-key");r.add("rufit.session.private-key-path",()->KEY.toString());
        r.add("rufit.social.transient-key",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("rufit.social.test-provider-base-url",PROVIDER::base);
        for(String p:List.of("google","kakao")) {
            r.add("rufit.social."+p+".client-id",()->"fixture-client");r.add("rufit.social."+p+".client-secret",()->"fixture-secret");
            r.add("rufit.social."+p+".callback-uri",()->"http://127.0.0.1:"+API_PORT.get()+"/api/v1/auth/callback/"+p);
        }
        r.add("rufit.social.ios-return-uri",()->"rufit://auth/callback");r.add("rufit.social.android-return-uri",()->"rufit://auth/callback");
    }
    @BeforeEach void reset(){API_PORT.set(port);CLOCK.now=SessionFoundationTests.NOW;PROVIDER.reset();jdbc.update("truncate auth_login_attempts,auth_rate_events,auth_refresh_tokens,auth_sessions,auth_identities,auth_accounts");}
    @AfterAll static void close() throws Exception {PROVIDER.close();HTTP.close();Files.deleteIfExists(KEY);}
    HttpResponse<String> get(String url) throws Exception {return HTTP.send(HttpRequest.newBuilder(URI.create(url.startsWith("/")?base()+url:url)).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> post(String path,Object body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    String base(){return "http://127.0.0.1:"+port;}
    JsonNode json(HttpResponse<String> response){return mapper.readTree(response.body());}
    record Flow(String attemptId,String verifier,String authorization,String callback,String handoff) {}
    Flow start(String provider) throws Exception {
        String verifier=AuthCrypto.secret();var response=post("/api/v1/auth/login-attempts",Map.of("provider",provider,"client","IOS","returnRoute","APP_LOGIN","codeChallenge",AuthCrypto.challenge(verifier),"codeChallengeMethod","S256"));
        assertThat(response.statusCode()).isEqualTo(201);var body=json(response);
        return new Flow(body.get("attemptId").asString(),verifier,body.get("authorizationUrl").asString(),null,null);
    }
    Flow authorize(Flow flow) throws Exception {
        var authorization=get(flow.authorization());assertThat(authorization.statusCode()).isEqualTo(302);
        return new Flow(flow.attemptId(),flow.verifier(),flow.authorization(),authorization.headers().firstValue("Location").orElseThrow(),null);
    }
    Flow callback(Flow flow) throws Exception {
        var response=get(flow.callback());assertThat(response.statusCode()).isEqualTo(302);
        String location=response.headers().firstValue("Location").orElseThrow();assertThat(location).startsWith("rufit://auth/callback?");
        var query=FakeOAuthServer.parameters(URI.create(location).getRawQuery());assertThat(query).containsOnlyKeys("attemptId","handoffCode");
        return new Flow(flow.attemptId(),flow.verifier(),flow.authorization(),flow.callback(),query.get("handoffCode"));
    }
    HttpResponse<String> exchange(Flow f) throws Exception {return post("/api/v1/auth/session-exchanges",Map.of("attemptId",f.attemptId(),"handoffCode",f.handoff(),"codeVerifier",f.verifier()));}
    @Test void googleAndKakaoCompleteHttpFlowAndKeepSecretsOutOfStorage() throws Exception {
        for(String provider:List.of("GOOGLE","KAKAO")) {
            Flow f=callback(authorize(start(provider)));var result=exchange(f);assertThat(result.statusCode()).isEqualTo(200);
            var body=json(result);String access=body.get("accessToken").asString(),owner=body.get("account").get("id").asString();
            assertThat(owner).hasSize(69);assertThat(body.get("account").get("isNewAccount").asBoolean()).isTrue();
            var me=HTTP.send(HttpRequest.newBuilder(URI.create(base()+"/api/v1/me")).header("Authorization","Bearer "+access).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(me.statusCode()).isEqualTo(200);assertThat(json(me).get("id").asString()).isEqualTo(owner);
            assertThat(PROVIDER.lastForm.get("code_verifier")).isNotEqualTo(f.verifier());
            assertThat(result.headers().firstValue("Cache-Control")).hasValue("no-store");assertThat(result.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
            assertThat(result.headers().firstValue("Set-Cookie")).isEmpty();assertThat(result.body()).doesNotContain("fixture-secret","unused-provider-token");
            assertThat(jdbc.queryForObject("select verifier_cipher from auth_login_attempts where id=?",String.class,UUID.fromString(f.attemptId()))).isNull();
            assertThat(jdbc.queryForObject("select handoff_hash from auth_login_attempts where id=?",String.class,UUID.fromString(f.attemptId()))).isEqualTo(AuthCrypto.hash(f.handoff()));
            assertThat(exchange(f).statusCode()).isEqualTo(409);assertThat(get(f.callback()).statusCode()).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts",Integer.class)).isEqualTo(2);
    }
    @Test void concurrentCallbackAndHandoffAreEachConsumedExactlyOnce() throws Exception {
        Flow ready=authorize(start("GOOGLE"));
        var callbacks=race(()->get(ready.callback()));assertThat(callbacks).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(302,409);
        assertThat(PROVIDER.tokenCalls).hasValue(1);
        String location=callbacks.stream().filter(r->r.statusCode()==302).findFirst().orElseThrow().headers().firstValue("Location").orElseThrow();
        String handoff=FakeOAuthServer.parameters(URI.create(location).getRawQuery()).get("handoffCode");
        Flow f=new Flow(ready.attemptId(),ready.verifier(),ready.authorization(),ready.callback(),handoff);
        assertThat(race(()->exchange(f))).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions",Integer.class)).isEqualTo(1);
    }
    @Test void wrongVerifierDoesNotConsumeHandoffAndHasDurableRateLimit() throws Exception {
        Flow f=callback(authorize(start("GOOGLE")));Flow bad=new Flow(f.attemptId(),AuthCrypto.secret(),f.authorization(),f.callback(),f.handoff());
        for(int i=0;i<5;i++) assertThat(exchange(bad).statusCode()).isEqualTo(401);
        var limited=exchange(bad);assertThat(limited.statusCode()).isEqualTo(429);assertThat(limited.headers().firstValue("Retry-After")).hasValue("60");
        assertThat(exchange(f).statusCode()).isEqualTo(200);
    }
    @Test void stateProviderAndDuplicateParametersAreBoundToOriginalAttempt() throws Exception {
        Flow f=authorize(start("GOOGLE"));
        assertThat(get(f.callback().replace("/google?","/kakao?")).statusCode()).isEqualTo(400);
        assertThat(get(f.callback()+"&state="+AuthCrypto.secret()).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/auth/callback/google?code=fake&state="+AuthCrypto.secret()).statusCode()).isEqualTo(400);
        assertThat(PROVIDER.tokenCalls).hasValue(0);callback(f);
    }
    @Test void cancellationOnlyReturnsAllowlistedErrorAndConfiguredAppRoute() throws Exception {
        Flow f=start("KAKAO");String state=FakeOAuthServer.parameters(URI.create(f.authorization()).getRawQuery()).get("state");
        var response=get("/api/v1/auth/callback/kakao?error=access_denied&error_description=secret&state="+state);
        assertThat(response.statusCode()).isEqualTo(302);String location=response.headers().firstValue("Location").orElseThrow();
        assertThat(location).contains("error=LOGIN_CANCELLED").doesNotContain("secret",state);assertThat(PROVIDER.tokenCalls).hasValue(0);
        assertThat(jdbc.queryForObject("select verifier_cipher from auth_login_attempts",String.class)).isNull();
    }
    @Test void exactAttemptAndHandoffExpiryRejectWithoutCreatingSession() throws Exception {
        Flow expired=authorize(start("GOOGLE"));CLOCK.now=CLOCK.now.plusSeconds(300);assertThat(get(expired.callback()).statusCode()).isEqualTo(401);
        Flow f=callback(authorize(start("GOOGLE")));CLOCK.now=CLOCK.now.plusSeconds(60);assertThat(exchange(f).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions",Integer.class)).isZero();
    }
    @Test void invalidProviderProofsNeverCreateAccountOrExposeProviderSecrets() throws Exception {
        for(String mode:List.of("issuer","audience","azp","nonce","expired","signature","redirect","unavailable","oversize")) {
            PROVIDER.mode=mode;Flow f=authorize(start("GOOGLE"));var response=get(f.callback());
            assertThat(response.statusCode()).as(mode).isEqualTo(Set.of("redirect","unavailable","oversize").contains(mode)?503:401);
            assertThat(response.body()).doesNotContain("never-return-this","fixture-secret","unused-provider-token");
        }
        assertThat(PROVIDER.unexpectedCalls).hasValue(0);assertThat(jdbc.queryForObject("select count(*) from auth_accounts",Integer.class)).isZero();
    }
    @Test void databaseFailureRollsBackAccountSessionAndHandoffTogether() throws Exception {
        Flow f=callback(authorize(start("GOOGLE")));
        jdbc.execute("create function auth_test_reject_session() returns trigger language plpgsql as $$ begin raise exception 'test session failure'; end $$");
        jdbc.execute("create trigger auth_test_session_failure before insert on auth_sessions for each row execute function auth_test_reject_session()");
        try {
            assertThat(exchange(f).statusCode()).isEqualTo(503);
            assertThat(jdbc.queryForObject("select status from auth_login_attempts",String.class)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("select count(*) from auth_accounts",Integer.class)).isZero();
        } finally {jdbc.execute("drop trigger auth_test_session_failure on auth_sessions");jdbc.execute("drop function auth_test_reject_session()");}
        assertThat(exchange(f).statusCode()).isEqualTo(200);
    }
    @Test void oversizedInputAndStartRateLimitsAreEnforcedOverHttp() throws Exception {
        assertThat(post("/api/v1/auth/session-exchanges",Map.of("padding","a".repeat(2048))).statusCode()).isEqualTo(413);
        for(int i=0;i<20;i++) start("GOOGLE");
        var limited=post("/api/v1/auth/login-attempts",Map.of("provider","GOOGLE","client","IOS","returnRoute","APP_LOGIN","codeChallenge",AuthCrypto.challenge(AuthCrypto.secret()),"codeChallengeMethod","S256"));
        assertThat(limited.statusCode()).isEqualTo(429);assertThat(limited.headers().firstValue("Retry-After")).hasValue("60");
    }
    @Test void openApiMarksLoginEndpointsAnonymousAndExposesSessionSchema() throws Exception {
        var session=json(exchange(callback(authorize(start("GOOGLE")))));
        var response=HTTP.send(HttpRequest.newBuilder(URI.create(base()+"/v3/api-docs")).header("Authorization","Bearer "+session.get("accessToken").asString()).GET().build(),HttpResponse.BodyHandlers.ofString());
        var body=json(response);assertThat(response.statusCode()).isEqualTo(200);
        Files.writeString(Path.of("target/auth-openapi.json"),response.body());
        assertThat(body.at("/paths/~1api~1v1~1auth~1login-attempts/post").isMissingNode()).isFalse();
        assertThat(body.at("/paths/~1api~1v1~1auth~1login-attempts/post/security").isArray()).isTrue();
        assertThat(body.at("/paths/~1api~1v1~1auth~1login-attempts/post/security").isEmpty()).isTrue();
        assertThat(body.at("/paths/~1api~1v1~1auth~1login-attempts/post/responses/201").isMissingNode()).isFalse();
        assertThat(body.at("/paths/~1api~1v1~1auth~1session-exchanges/post/responses/429/headers/Retry-After").isMissingNode()).isFalse();
        assertThat(body.at("/components/schemas/com.rufit.auth.SessionView/properties/refreshToken").isMissingNode()).isFalse();
        assertThat(body.at("/paths/~1api~1v1~1auth~1logout/post/responses/204").isMissingNode()).isFalse();
    }
    JsonNode login() throws Exception {var r=exchange(callback(authorize(start("GOOGLE"))));assertThat(r.statusCode()).isEqualTo(200);return json(r);}
    HttpResponse<String> refresh(JsonNode session) throws Exception {return post("/api/v1/auth/refresh",Map.of("refreshToken",session.get("refreshToken").asString()));}
    HttpResponse<String> logout(JsonNode session) throws Exception {return post("/api/v1/auth/logout",Map.of("refreshToken",session.get("refreshToken").asString()));}
    HttpResponse<String> me(JsonNode session) throws Exception {return HTTP.send(HttpRequest.newBuilder(URI.create(base()+"/api/v1/me")).header("Authorization","Bearer "+session.get("accessToken").asString()).GET().build(),HttpResponse.BodyHandlers.ofString());}
    @Test void refreshRotatesAndOldTokenReuseImmediatelyRevokesOnlyItsGrant() throws Exception {
        var first=login();var other=login();var response=refresh(first);assertThat(response.statusCode()).isEqualTo(200);var rotated=json(response);
        assertThat(rotated.get("sessionId")).isEqualTo(first.get("sessionId"));assertThat(rotated.at("/account/id")).isEqualTo(first.at("/account/id"));
        assertThat(rotated.at("/account/isNewAccount").asBoolean()).isFalse();assertThat(rotated.get("refreshToken")).isNotEqualTo(first.get("refreshToken"));
        assertThat(me(rotated).statusCode()).isEqualTo(200);assertThat(refresh(first).statusCode()).isEqualTo(401);
        assertThat(me(first).statusCode()).isEqualTo(401);assertThat(me(rotated).statusCode()).isEqualTo(401);assertThat(refresh(rotated).statusCode()).isEqualTo(401);
        assertThat(me(other).statusCode()).isEqualTo(200);
    }
    @Test void concurrentRefreshHasOneRotationAndReuseRevokesItsWinningTokens() throws Exception {
        var session=login();var responses=race(()->refresh(session));
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200,401);
        var winner=json(responses.stream().filter(r->r.statusCode()==200).findFirst().orElseThrow());
        assertThat(me(winner).statusCode()).isEqualTo(401);assertThat(refresh(winner).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from auth_refresh_tokens",Integer.class)).isEqualTo(2);
    }
    @Test void logoutAcceptsKnownOldRefreshAndIsIdempotentWithoutAffectingOtherSessions() throws Exception {
        var first=login();var other=login();var rotated=json(refresh(first));
        assertThat(logout(first).statusCode()).isEqualTo(204);assertThat(logout(first).body()).isEmpty();
        assertThat(me(first).statusCode()).isEqualTo(401);assertThat(me(rotated).statusCode()).isEqualTo(401);assertThat(me(other).statusCode()).isEqualTo(200);
        assertThat(logout(rotated).statusCode()).isEqualTo(204);
    }
    @Test void randomRefreshCannotRevokeRealGrantAndInvalidFormatsAre400() throws Exception {
        var session=login();var unknown=Map.of("refreshToken",AuthCrypto.secret());
        assertThat(post("/api/v1/auth/refresh",unknown).statusCode()).isEqualTo(401);assertThat(post("/api/v1/auth/logout",unknown).statusCode()).isEqualTo(204);
        for(String token:List.of("bad","A".repeat(42)+"B")) {
            assertThat(post("/api/v1/auth/refresh",Map.of("refreshToken",token)).statusCode()).isEqualTo(400);
            assertThat(post("/api/v1/auth/logout",Map.of("refreshToken",token)).statusCode()).isEqualTo(400);
        }
        assertThat(me(session).statusCode()).isEqualTo(200);
    }
    @Test void concurrentLogoutAndRefreshNeverLeaveAnActiveSession() throws Exception {
        var session=login();var counter=new AtomicInteger();
        var responses=race(()->counter.getAndIncrement()==0?refresh(session):logout(session));
        assertThat(responses).extracting(HttpResponse::statusCode).contains(204).allMatch(s->Set.of(200,204,401).contains(s));
        for(var response:responses) if(response.statusCode()==200) assertThat(me(json(response)).statusCode()).isEqualTo(401);
        assertThat(me(session).statusCode()).isEqualTo(401);
    }
    @Test void idleAndAbsoluteExpiryAreStrictAndAccessCannotOutliveGrant() throws Exception {
        var idle=login();CLOCK.now=CLOCK.now.plus(Duration.ofDays(14));assertThat(refresh(idle).statusCode()).isEqualTo(401);
        var absolute=login();Instant created=CLOCK.now;
        CLOCK.now=created.plus(Duration.ofDays(13));absolute=json(refresh(absolute));
        CLOCK.now=created.plus(Duration.ofDays(26));absolute=json(refresh(absolute));
        CLOCK.now=created.plus(Duration.ofDays(30)).minusSeconds(60);absolute=json(refresh(absolute));
        assertThat(absolute.get("refreshTokenExpiresAt").asString()).isEqualTo(created.plus(Duration.ofDays(30)).toString());
        assertThat(absolute.get("accessTokenExpiresAt")).isEqualTo(absolute.get("refreshTokenExpiresAt"));
        CLOCK.now=created.plus(Duration.ofDays(30));assertThat(me(absolute).statusCode()).isEqualTo(401);assertThat(refresh(absolute).statusCode()).isEqualTo(401);
    }
    @Test void refreshRateLimitDoesNotPreventLogout() throws Exception {
        var current=login();
        for(int i=0;i<12;i++) {var r=refresh(current);assertThat(r.statusCode()).isEqualTo(200);current=json(r);}
        var limited=refresh(current);assertThat(limited.statusCode()).isEqualTo(429);assertThat(limited.headers().firstValue("Retry-After")).hasValue("60");
        assertThat(logout(current).statusCode()).isEqualTo(204);assertThat(me(current).statusCode()).isEqualTo(401);
    }
    @Test void failedRefreshInsertRollsBackUsedFlagAndDoesNotLoseCurrentToken() throws Exception {
        var session=login();
        jdbc.execute("create function auth_test_reject_refresh() returns trigger language plpgsql as $$ begin raise exception 'test refresh failure'; end $$");
        jdbc.execute("create trigger auth_test_refresh_failure before insert on auth_refresh_tokens for each row execute function auth_test_reject_refresh()");
        try {
            var failure=refresh(session);assertThat(failure.statusCode()).isEqualTo(503);assertThat(json(failure).get("code").asString()).isEqualTo("STORAGE_UNAVAILABLE");
            assertThat(jdbc.queryForObject("select used_at from auth_refresh_tokens",java.sql.Timestamp.class)).isNull();
            assertThat(me(session).statusCode()).isEqualTo(200);
        } finally {jdbc.execute("drop trigger auth_test_refresh_failure on auth_refresh_tokens");jdbc.execute("drop function auth_test_reject_refresh()");}
        assertThat(refresh(session).statusCode()).isEqualTo(200);
    }
    @Test void maintenanceRemovesExpiredCipherAndRetainsRefreshRelationsUntilAbsolutePlusSkew() throws Exception {
        var session=login();refresh(session);start("GOOGLE");Instant created=CLOCK.now;
        CLOCK.now=created.plusSeconds(300);maintenance.purge();
        assertThat(jdbc.queryForObject("select count(*) from auth_login_attempts where verifier_cipher is not null",Integer.class)).isZero();
        CLOCK.now=created.plus(Duration.ofDays(30)).plusSeconds(29);maintenance.purge();
        assertThat(jdbc.queryForObject("select count(*) from auth_refresh_tokens",Integer.class)).isEqualTo(2);
        CLOCK.now=CLOCK.now.plusSeconds(1);maintenance.purge();
        assertThat(jdbc.queryForObject("select count(*) from auth_refresh_tokens",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from auth_rate_events",Integer.class)).isZero();
    }
    static <T> List<T> race(Callable<T> task) throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {var barrier=new CyclicBarrier(2);Callable<T> action=()->{barrier.await();return task.call();};var a=executor.submit(action);var b=executor.submit(action);return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));}
    }
    static class MutableClock extends Clock {
        volatile Instant now=SessionFoundationTests.NOW;
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
