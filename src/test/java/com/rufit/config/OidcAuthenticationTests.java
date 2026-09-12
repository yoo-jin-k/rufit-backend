package com.rufit.config;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class OidcAuthenticationTests {
    static final String ISSUER = "https://identity.rufit.test";
    static final RSAKey KEY = key("trusted");
    static final HttpServer JWKS = server();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    static RSAKey key(String id) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).keyID(id).build();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] response = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var out = exchange.getResponseBody()) { out.write(response); }
            });
            server.start(); return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("rufit.allowed-origins", () -> "https://app.rufit.test");
        registry.add("rufit.auth.issuer", () -> ISSUER);
        registry.add("rufit.auth.audience", () -> "rufit-api");
        registry.add("rufit.auth.allow-local-http-jwks", () -> true);
        registry.add("rufit.auth.jwk-set-uri", () -> "http://127.0.0.1:"+JWKS.getAddress().getPort()+"/jwks");
    }
    @AfterAll static void close() { JWKS.stop(0); }
    @BeforeEach void clean() { jdbc.update("delete from workouts"); }
    private JWTClaimsSet.Builder claims(String subject) {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience("rufit-api")
            .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)));
    }
    private String sign(JWTClaimsSet claims, RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
    private String token(String subject) throws Exception { return sign(claims(subject).build(), KEY); }
    @Test void oidcAllowsAnonymousStatusProbesButKeepsOtherManagementPathsProtected() throws Exception {
        for(String path:List.of("/actuator/health/liveness","/actuator/health/readiness"))
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/actuator/health/db")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
    @Test void realSignedBearerTokenWritesWithoutCookiesAndIsolatesOwners() throws Exception {
        String alice = token("alice-subject");
        String body = mvc.perform(post("/api/v1/workouts").header("Authorization", "Bearer "+alice))
            .andExpect(status().isCreated()).andExpect(header().doesNotExist("Set-Cookie"))
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        mvc.perform(get("/api/v1/workouts/"+id).header("Authorization", "Bearer "+token("alice-subject"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/workouts/"+id).header("Authorization", "Bearer "+token("bob-subject"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/workouts/"+id)).andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"rufit\""));
        mvc.perform(get("/api/v1/workouts").with(httpBasic("local-test", "anything"))).andExpect(status().isUnauthorized());
    }
    @Test void rejectsBadSignaturesExpiryIssuerAudienceAndMissingIdentity() throws Exception {
        List<String> badTokens = List.of(
            sign(claims("alice").build(), key("attacker")),
            sign(claims("alice").expirationTime(Date.from(Instant.now().minusSeconds(300))).build(), KEY),
            sign(claims("alice").issuer("https://other-issuer.test").build(), KEY),
            sign(claims("alice").audience("other-api").build(), KEY),
            sign(claims("alice").audience((String) null).build(), KEY),
            sign(claims("alice").expirationTime(null).build(), KEY),
            sign(claims(null).build(), KEY),
            sign(claims("alice").notBeforeTime(Date.from(Instant.now().plusSeconds(300))).build(), KEY));
        for (String bad : badTokens)
            mvc.perform(get("/api/v1/workouts").header("Authorization", "Bearer "+bad))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }
    @Test void sameEmailNeverMergesDifferentSubjects() throws Exception {
        String first = sign(claims("first").claim("email", "same@example.test").build(), KEY);
        String second = sign(claims("second").claim("email", "same@example.test").build(), KEY);
        String body = mvc.perform(post("/api/v1/workouts").header("Authorization", "Bearer "+first))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/workouts/"+JsonPath.read(body, "$.id")).header("Authorization", "Bearer "+second)).andExpect(status().isNotFound());
    }
    @Test void currentAccountIsStableAcrossTokensAndIsolatedAcrossSubjects() throws Exception {
        String first = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer "+token("alice")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(first, "$.id");
        org.junit.jupiter.api.Assertions.assertTrue(id.startsWith("oidc:") && id.length() == 69);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer "+token("alice")))
            .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer "+token("bob")))
            .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(id)));
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }
    @Test void corsAllowsOnlyConfiguredBrowserOriginAndNeverBypassesAuthentication() throws Exception {
        mvc.perform(options("/api/v1/workouts").header("Origin", "https://app.rufit.test")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://app.rufit.test"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/api/v1/workouts").header("Origin", "https://untrusted.test")
            .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/v1/me").header("Origin", "https://app.rufit.test"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Access-Control-Allow-Origin", "https://app.rufit.test"));
        mvc.perform(get("/api/v1/me").header("Origin", "https://app.rufit.test")
            .header("Authorization", "Bearer "+token("alice")))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(header().string("Access-Control-Expose-Headers","X-Request-Id"));
        mvc.perform(options("/api/v1/workouts").header("Origin", "https://app.rufit.test")
            .header("Access-Control-Request-Method", "DELETE"))
            .andExpect(status().isForbidden());
    }
    @Test void exportsBearerContractWithoutDevelopmentCsrfRoute() throws Exception {
        String json=mvc.perform(get("/v3/api-docs").header("Authorization","Bearer "+token("contract")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String,Object> schemes=JsonPath.read(json,"$.components.securitySchemes");
        org.assertj.core.api.Assertions.assertThat(schemes).containsKey("bearerAuth").doesNotContainKey("developmentBasic");
        org.assertj.core.api.Assertions.assertThat((String)JsonPath.read(json,"$.components.securitySchemes.bearerAuth.scheme")).isEqualTo("bearer");
        Map<String,Object> paths=JsonPath.read(json,"$.paths");
        org.assertj.core.api.Assertions.assertThat(paths).containsKeys("/api/v1/profile","/api/v1/workouts/{id}/draft").doesNotContainKey("/api/v1/csrf");
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/openapi-oidc.json"),json);
    }
}
