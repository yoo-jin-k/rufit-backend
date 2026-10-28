package com.rufit.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 테스트 소스에만 존재하는 OAuth 서버. 실제 제공자·계정·비밀에 접근하지 않는다. */
public final class FakeOAuthServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final RSAKey key;
    private final Clock clock;
    private final ObjectMapper mapper=new ObjectMapper();
    private final Map<String,Map<String,String>> codes=new ConcurrentHashMap<>();
    final AtomicInteger tokenCalls=new AtomicInteger();
    final AtomicInteger unexpectedCalls=new AtomicInteger();
    volatile String mode="valid";
    volatile String subject="fixture-alice";
    volatile Map<String,String> lastForm=Map.of();

    public FakeOAuthServer(Clock clock,int port) {
        try {
            this.clock=clock;key=new RSAKeyGenerator(2048).keyID("fake-provider-key").generate();
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
            server.setExecutor(executor);server.createContext("/",this::handle);server.start();
        } catch(Exception e) { throw new IllegalStateException("Fake OAuth setup failed"); }
    }
    String base() {return "http://127.0.0.1:"+server.getAddress().getPort();}
    void reset() {mode="valid";subject="fixture-alice";lastForm=Map.of();codes.clear();tokenCalls.set(0);unexpectedCalls.set(0);}
    static Map<String,String> parameters(String query) {
        Map<String,String> result=new HashMap<>();
        if(query!=null && !query.isEmpty()) for(String pair:query.split("&")) {
            String[] pieces=pair.split("=",2);
            result.put(URLDecoder.decode(pieces[0],StandardCharsets.UTF_8),pieces.length==2?URLDecoder.decode(pieces[1],StandardCharsets.UTF_8):"");
        }
        return result;
    }
    private void handle(HttpExchange exchange) throws java.io.IOException {
        try {
            String[] path=exchange.getRequestURI().getPath().split("/");
            if(path.length!=3 || !Set.of("google","kakao").contains(path[1])) {unexpectedCalls.incrementAndGet();reply(exchange,404,"{}");return;}
            String provider=path[1];
            if(path[2].equals("jwks")) {reply(exchange,200,new JWKSet(key.toPublicJWK()).toString());return;}
            if(path[2].equals("authorize")) {
                var query=parameters(exchange.getRequestURI().getRawQuery());
                // 고정된 로컬/테스트 콜백만 허용해 fixture가 임의 URL 리다이렉터가 되지 않게 한다.
                URI callback=URI.create(query.get("redirect_uri"));
                if(!Set.of("127.0.0.1","localhost").contains(callback.getHost()) || !callback.getPath().equals("/api/v1/auth/callback/"+provider)) {reply(exchange,400,"{}");return;}
                String code=AuthCrypto.secret();var saved=new HashMap<>(query);saved.put("provider",provider);saved.put("subject",subject);codes.put(code,saved);
                exchange.getResponseHeaders().set("Location",callback+"?"+SocialProviders.query(Map.of("code",code,"state",query.get("state"))));
                exchange.sendResponseHeaders(302,-1);return;
            }
            if(path[2].equals("token")) {
                tokenCalls.incrementAndGet();var form=parameters(new String(exchange.getRequestBody().readNBytes(8193),StandardCharsets.UTF_8));lastForm=form;
                var saved=codes.remove(form.get("code"));
                if(saved==null || !provider.equals(saved.get("provider")) || !"fixture-client".equals(form.get("client_id")) || !"fixture-secret".equals(form.get("client_secret"))
                    || !saved.get("redirect_uri").equals(form.get("redirect_uri")) || !saved.get("code_challenge").equals(AuthCrypto.challenge(form.get("code_verifier")))) {reply(exchange,400,"{}");return;}
                if(mode.equals("redirect")) {exchange.getResponseHeaders().set("Location",base()+"/must-not-follow");exchange.sendResponseHeaders(307,-1);return;}
                if(mode.equals("unavailable")) {reply(exchange,503,"{\"provider_secret\":\"never-return-this\"}");return;}
                if(mode.equals("oversize")) {reply(exchange,200,"x".repeat(65537));return;}
                Instant now=clock.instant();
                var claims=new JWTClaimsSet.Builder().issuer(mode.equals("issuer")?"https://wrong.test":base()+"/"+provider)
                    .subject(saved.get("subject")).audience(mode.equals("audience")?"wrong-client":"fixture-client")
                    .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(mode.equals("expired")?-31:300)))
                    .claim("nonce",mode.equals("nonce")?"wrong-nonce":saved.get("nonce"));
                if(mode.equals("azp")) claims.audience(List.of("fixture-client","other-client")).claim("azp","other-client");
                var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims.build());
                token.sign(new RSASSASigner(mode.equals("signature")?new RSAKeyGenerator(2048).generate():key));
                reply(exchange,200,mapper.writeValueAsString(Map.of("id_token",token.serialize(),"access_token","unused-provider-token")));return;
            }
            reply(exchange,404,"{}");
        } catch(Exception e) {reply(exchange,500,"{}");}
        finally {exchange.close();}
    }
    private static void reply(HttpExchange exchange,int status,String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
    @Override public void close(){server.stop(0);executor.close();}
}
