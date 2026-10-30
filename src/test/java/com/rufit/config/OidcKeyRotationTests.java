package com.rufit.config;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class OidcKeyRotationTests {

  static final String ISSUER = "https://rotation.rufit.test";
  static final RSAKey FIRST = key("first"),
    SECOND = key("second"),
    RECOVERED = key("recovered");
  static final AtomicReference<List<JWK>> published = new AtomicReference<>(
    List.of(FIRST.toPublicJWK())
  );
  static final AtomicBoolean unavailable = new AtomicBoolean();
  static final AtomicInteger requests = new AtomicInteger();
  static final HttpServer keys = server();

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  static RSAKey key(String id) {
    try {
      return new RSAKeyGenerator(2048).keyID(id).generate();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  static HttpServer server() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/keys", exchange -> {
        requests.incrementAndGet();
        byte[] body = (
          unavailable.get() ? "{\"error\":\"test outage\"}" : new JWKSet(published.get()).toString()
        ).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(unavailable.get() ? 503 : 200, body.length);
        try (var output = exchange.getResponseBody()) {
          output.write(body);
        }
      });
      server.start();
      return server;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("rufit.auth.issuer", () -> ISSUER);
    registry.add("rufit.auth.audience", () -> "rufit-api");
    registry.add(
      "rufit.auth.jwk-set-uri",
      () -> "http://127.0.0.1:" + keys.getAddress().getPort() + "/keys"
    );
    registry.add("rufit.auth.allow-local-http-jwks", () -> true);
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from workouts");
    published.set(List.of(FIRST.toPublicJWK()));
    unavailable.set(false);
  }

  @AfterAll
  static void close() {
    keys.stop(0);
  }

  String token(RSAKey key, String subject) throws Exception {
    SignedJWT jwt = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
      new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .subject(subject)
        .audience("rufit-api")
        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        .build()
    );
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  @Test
  void newlyPublishedSigningKeyRefreshesWithoutChangingRecordOwnership() throws Exception {
    String first = token(FIRST, "rotation-owner");
    String created = mvc
      .perform(post("/api/v1/workouts").header("Authorization", "Bearer " + first))
      .andExpect(status().isCreated())
      .andReturn()
      .getResponse()
      .getContentAsString();
    String id = JsonPath.read(created, "$.id");
    int before = requests.get();
    published.set(List.of(FIRST.toPublicJWK(), SECOND.toPublicJWK()));
    String second = token(SECOND, "rotation-owner");
    mvc
      .perform(get("/api/v1/workouts/" + id).header("Authorization", "Bearer " + second))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.id").value(id));
    assertThat(requests.get()).isGreaterThan(before);
    mvc
      .perform(get("/api/v1/workouts/" + id).header("Authorization", "Bearer " + first))
      .andExpect(status().isOk());
    mvc
      .perform(
        get("/api/v1/workouts/" + id).header(
          "Authorization",
          "Bearer " + token(SECOND, "other-owner")
        )
      )
      .andExpect(status().isNotFound());
    assertThat(jdbc.queryForObject("select count(*) from workouts", Long.class)).isEqualTo(1);
  }

  @Test
  void keyServerOutageRejectsUnverifiedWriteAndRecoversWithoutRestart() throws Exception {
    String created = mvc
      .perform(
        post("/api/v1/workouts").header("Authorization", "Bearer " + token(FIRST, "outage-owner"))
      )
      .andExpect(status().isCreated())
      .andReturn()
      .getResponse()
      .getContentAsString();
    String id = JsonPath.read(created, "$.id");
    Number version = JsonPath.read(created, "$.version");
    String next = token(RECOVERED, "outage-owner");
    unavailable.set(true);
    try {
      mvc
        .perform(
          put("/api/v1/workouts/" + id + "/note")
            .header("Authorization", "Bearer " + next)
            .contentType("application/json")
            .content("{\"version\":" + version + ",\"note\":\"Do not save\"}")
        )
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"))
        .andExpect(header().doesNotExist("WWW-Authenticate"))
        .andExpect(
          jsonPath("$.detail").value(
            "로그인 검증 서비스에 일시적으로 연결할 수 없습니다. 잠시 후 다시 확인해 주세요."
          )
        );
      assertThat(
        jdbc.queryForObject(
          "select note from workouts where id=?",
          String.class,
          UUID.fromString(id)
        )
      ).isEmpty();
      assertThat(
        jdbc.queryForObject(
          "select version from workouts where id=?",
          Long.class,
          UUID.fromString(id)
        )
      ).isEqualTo(version.longValue());
    } finally {
      unavailable.set(false);
    }
    published.set(List.of(FIRST.toPublicJWK(), RECOVERED.toPublicJWK()));
    mvc
      .perform(
        put("/api/v1/workouts/" + id + "/note")
          .header("Authorization", "Bearer " + next)
          .contentType("application/json")
          .content("{\"version\":" + version + ",\"note\":\"Recovered\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.note").value("Recovered"));
    mvc
      .perform(
        get("/api/v1/workouts/" + id).header(
          "Authorization",
          "Bearer " + token(FIRST, "outage-owner")
        )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.note").value("Recovered"));
  }
}
