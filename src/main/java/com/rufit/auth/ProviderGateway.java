package com.rufit.auth;

/*
 * 외부 소셜 로그인 제공자와 통신하여 인증 증명을 확인합니다.
 * 외부 응답은 검증한 뒤 사용하며 사용자 식별자를 클라이언트 요청만으로 신뢰하지 않습니다.
 */

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.SignedJWT;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("social")
public class ProviderGateway {

  private final ObjectMapper mapper;
  private final Clock clock;
  private final Map<String, Keys> cache = new HashMap<>();

  public ProviderGateway(ObjectMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  /** 목적지는 서버가 만든 제공자 설정뿐입니다. 요청/JWT에 들어 있는 URL은 사용하지 않습니다. */
  Identity exchange(
    SocialProviders.Provider provider,
    String code,
    String verifier,
    String nonceHash
  ) {
    Map<String, String> form = Map.of(
      "grant_type",
      "authorization_code",
      "code",
      code,
      "client_id",
      provider.clientId(),
      "client_secret",
      provider.secret(),
      "redirect_uri",
      provider.callback(),
      "code_verifier",
      verifier
    );
    byte[] response = fetch(provider.token(), SocialProviders.query(form));
    try {
      var body = mapper.readTree(response);
      var node = body.get("id_token");
      if (
        node == null || !node.isString() || node.asString().length() > 16384
      ) throw AuthFailure.proof();
      var jwt = SignedJWT.parse(node.asString());
      var h = jwt.getHeader();
      if (
        !JWSAlgorithm.RS256.equals(h.getAlgorithm()) ||
        h.getKeyID() == null ||
        h.getKeyID().length() > 128 ||
        h.getCriticalParams() != null
      ) throw AuthFailure.proof();
      RSAKey key = key(provider, h.getKeyID());
      if (!jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))) throw AuthFailure.proof();
      var c = jwt.getJWTClaimsSet();
      Instant now = clock.instant();
      boolean issuer =
        provider.issuer().equals(c.getIssuer()) ||
        (!provider.fake() &&
          provider.name().equals("GOOGLE") &&
          "accounts.google.com".equals(c.getIssuer()));
      String azp = c.getStringClaim("azp"),
        nonce = c.getStringClaim("nonce"),
        subject = c.getSubject();
      if (
        !issuer ||
        c.getAudience() == null ||
        !c.getAudience().contains(provider.clientId()) ||
        (c.getAudience().size() > 1 && azp == null) ||
        (azp != null && !azp.equals(provider.clientId())) ||
        subject == null ||
        subject.isBlank() ||
        subject.length() > 255 ||
        c.getExpirationTime() == null ||
        c.getIssueTime() == null ||
        !now.isBefore(c.getExpirationTime().toInstant().plusSeconds(30)) ||
        c.getIssueTime().toInstant().isAfter(now.plusSeconds(30)) ||
        !c.getExpirationTime().after(c.getIssueTime()) ||
        (c.getNotBeforeTime() != null &&
          c.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(30))) ||
        nonce == null ||
        !AuthCrypto.same(AuthCrypto.hash(nonce), nonceHash)
      ) throw AuthFailure.proof();
      return new Identity(provider.issuer(), subject, "KAKAO".equals(provider.name())
        ? displayEmail(c.getClaim("email")) : null);
    } catch (AuthFailure e) {
      throw e;
    } catch (Exception e) {
      throw AuthFailure.proof();
    }
  }

  private synchronized RSAKey key(SocialProviders.Provider p, String kid) {
    Instant now = clock.instant();
    Keys keys = cache.get(p.name());
    if (
      keys == null ||
      !keys.url().equals(p.jwks()) ||
      !now.isBefore(keys.fetched().plusSeconds(300)) ||
      (keys.set().getKeyByKeyId(kid) == null && !now.isBefore(keys.fetched().plusSeconds(10)))
    ) {
      byte[] response = fetch(p.jwks(), null);
      try {
        keys = new Keys(p.jwks(), now, JWKSet.parse(new String(response, StandardCharsets.UTF_8)));
        cache.put(p.name(), keys);
      } catch (Exception e) {
        throw new AuthFailure(503, "AUTHENTICATION_UNAVAILABLE");
      }
    }
    var matches = keys
      .set()
      .getKeys()
      .stream()
      .filter(k -> kid.equals(k.getKeyID()))
      .toList();
    if (
      matches.size() != 1 ||
      !(matches.getFirst() instanceof RSAKey rsa) ||
      rsa.size() < 2048 ||
      rsa.isPrivate() ||
      (rsa.getKeyUse() != null && !KeyUse.SIGNATURE.equals(rsa.getKeyUse())) ||
      (rsa.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(rsa.getAlgorithm()))
    ) throw AuthFailure.proof();
    return rsa;
  }

  /** 응답·대기시간을 제한하고 30x를 따라가지 않아 비밀의 다른 호스트 전달을 방지합니다. */
  byte[] fetch(String target, String form) {
    HttpURLConnection connection = null;
    try {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      connection = (HttpURLConnection) URI.create(target).toURL().openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      connection.setRequestProperty("Accept", "application/json");
      if (form != null) {
        byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (var out = connection.getOutputStream()) {
          out.write(bytes);
        }
      }
      int status = connection.getResponseCode();
      if (status != 200) throw new AuthFailure(
        status == 400 || status == 401 ? 401 : 503,
        status == 400 || status == 401 ? "AUTHENTICATION_REQUIRED" : "AUTHENTICATION_UNAVAILABLE"
      );
      if (connection.getContentLengthLong() > 65536) throw new AuthFailure(
        503,
        "AUTHENTICATION_UNAVAILABLE"
      );
      try (var input = connection.getInputStream()) {
        // 조금씩 계속 보내는 응답도 전체 기한을 넘겨 스레드를 점유하지 못합니다.
        var output = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        while (true) {
          long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            deadline - System.nanoTime()
          );
          if (remaining <= 0) throw new AuthFailure(503, "AUTHENTICATION_UNAVAILABLE");
          connection.setReadTimeout((int) Math.min(5000, Math.max(1, remaining)));
          int read = input.read(chunk);
          if (read < 0) return output.toByteArray();
          if (output.size() + read > 65536) throw new AuthFailure(
            503,
            "AUTHENTICATION_UNAVAILABLE"
          );
          output.write(chunk, 0, read);
        }
      }
    } catch (AuthFailure e) {
      throw e;
    } catch (Exception e) {
      throw new AuthFailure(503, "AUTHENTICATION_UNAVAILABLE");
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  /** 검증된 토큰의 이메일도 표시 형식을 확인하며 계정 연결 키로 사용하지 않습니다. */
  static String displayEmail(Object value) {
    if (!(value instanceof String email) || email.length() > 254 ||
        !email.matches("[^\\s@\\p{Cntrl}]+@[^\\s@\\p{Cntrl}]+\\.[^\\s@\\p{Cntrl}]+")) return null;
    return email;
  }

  record Identity(String issuer, String subject, String email) {
    @Override public String toString() { return "Identity[redacted]"; }
  }

  private record Keys(String url, Instant fetched, JWKSet set) {}
}
