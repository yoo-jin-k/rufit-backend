package com.rufit.auth;

import com.rufit.RufitBackendApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;

/** FE 통합 전용 진입점. 운영 JAR에 포함되지 않으며 매 기동 새 시험 키를 만든다. */
public class FakeLoginLauncher {
    private static FakeOAuthServer fixture;
    public static void main(String[] args) throws Exception {
        int port=8096;String base="http://127.0.0.1:"+port;
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        Path key=Files.createTempFile("rufit-fake-login-", ".pem",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(key,"-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n");key.toFile().deleteOnExit();
        byte[] transientKey=new byte[32];new SecureRandom().nextBytes(transientKey);
        Map<String,String> properties=new HashMap<>();
        properties.put("spring.profiles.active","social,test");properties.put("server.port",Integer.toString(port));
        // Docker가 호스트127.0.0.1에만 포트를 공개한다. LAN/운영 배포용 런처가 아니다.
        properties.put("server.address","0.0.0.0");properties.put("springdoc.api-docs.enabled","true");
        properties.put("rufit.session.issuer","https://fake-session.rufit.test");properties.put("rufit.session.audience","rufit-api");
        properties.put("rufit.session.key-id","fake-launch-key");properties.put("rufit.session.private-key-path",key.toString());
        properties.put("rufit.social.transient-key",Base64.getEncoder().encodeToString(transientKey));
        properties.put("rufit.social.test-provider-base-url",base);
        properties.put("rufit.social.ios-return-uri","rufit://auth/callback");properties.put("rufit.social.android-return-uri","rufit://auth/callback");
        properties.put("rufit.allowed-origins","http://localhost:8099,http://127.0.0.1:8099");
        for(String provider:List.of("google","kakao")) {
            properties.put("rufit.social."+provider+".client-id","fixture-client");properties.put("rufit.social."+provider+".client-secret","fixture-secret");
            properties.put("rufit.social."+provider+".callback-uri",base+"/api/v1/auth/callback/"+provider);
        }
        properties.forEach(System::setProperty);
        fixture=new FakeOAuthServer(Clock.systemUTC(),0);fixture.advertisedBase=base;
        try {SpringApplication.run(new Class<?>[]{RufitBackendApplication.class,FixtureConfiguration.class},new String[0]);}
        catch(Exception error){fixture.close();Files.deleteIfExists(key);throw error;}
    }
    @TestConfiguration(proxyBeanMethods=false)
    public static class FixtureConfiguration {
        @Bean(destroyMethod="close") FakeOAuthServer fakeOAuthFixture(){return fixture;}
        @Bean FilterRegistrationBean<Filter> fixtureBridge(FakeOAuthServer provider) {
            var registration=new FilterRegistrationBean<Filter>(new Bridge(provider));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);registration.addUrlPatterns("/google/*","/kakao/*");return registration;
        }
    }
    /** 한 개의 로컬 공개 포트에서 가짜 승인 화면과 API를 함께 시험한다. 목적지는 고정 loopback뿐이다. */
    static class Bridge implements Filter {
        private final FakeOAuthServer provider;
        private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
        Bridge(FakeOAuthServer provider){this.provider=provider;}
        @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
            var request=(HttpServletRequest)input;var response=(HttpServletResponse)output;String path=request.getRequestURI();
            if(!path.matches("/(google|kakao)/(authorize|token|jwks)")) {response.setStatus(404);return;}
            response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
            try {
                String query=request.getQueryString();if(query!=null && query.length()>8192){response.setStatus(400);return;}
                var builder=HttpRequest.newBuilder(URI.create(provider.localBase()+path+(query==null?"":"?"+query))).timeout(Duration.ofSeconds(5));
                if(path.endsWith("/token") && request.getMethod().equals("POST")) {
                    byte[] bytes=request.getInputStream().readNBytes(8193);if(bytes.length>8192){response.setStatus(413);return;}
                    builder.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
                } else if(!path.endsWith("/token") && request.getMethod().equals("GET")) builder.GET();
                else {response.setStatus(405);return;}
                var result=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());response.setStatus(result.statusCode());
                result.headers().firstValue("Location").ifPresent(v->response.setHeader("Location",v));
                response.setContentType("application/json");response.getOutputStream().write(result.body());
            } catch(Exception e){response.setStatus(503);}
        }
        @Override public void destroy(){client.close();}
    }
}
