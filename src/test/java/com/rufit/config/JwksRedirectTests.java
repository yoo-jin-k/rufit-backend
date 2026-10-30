package com.rufit.config;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

class JwksRedirectTests {

  @Test
  void keyFetchDoesNotFollowRedirectToAnotherAddress() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("redirect-test").generate();
    AtomicInteger keyRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/redirect", exchange -> {
      exchange.getResponseHeaders().add("Location", base + "/keys");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.createContext("/keys", exchange -> {
      keyRequests.incrementAndGet();
      byte[] bytes = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    try {
      SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
        new JWTClaimsSet.Builder()
          .issuer("https://identity.rufit.test")
          .subject("redirect-test")
          .audience("rufit-api")
          .expirationTime(Date.from(Instant.now().plusSeconds(300)))
          .build()
      );
      jwt.sign(new RSASSASigner(key));
      var decoder = new OidcSecurityConfig().jwtDecoder(
        "https://identity.rufit.test",
        base + "/redirect",
        "rufit-api",
        true
      );
      assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
      assertThat(keyRequests.get()).isZero();
      var direct = new OidcSecurityConfig().jwtDecoder(
        "https://identity.rufit.test",
        base + "/keys",
        "rufit-api",
        true
      );
      assertThat(direct.decode(jwt.serialize()).getSubject()).isEqualTo("redirect-test");
      assertThat(keyRequests.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }
}
