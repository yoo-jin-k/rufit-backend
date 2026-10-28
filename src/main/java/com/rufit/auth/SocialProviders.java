package com.rufit.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.*;
import org.springframework.stereotype.Component;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@Profile("social")
public class SocialProviders {
    private final Environment env;
    private final URI fake;
    public SocialProviders(Environment env) {
        this.env=env;
        String base=env.getProperty("rufit.social.test-provider-base-url","");
        if(base.isBlank()) fake=null;
        else {
            if(!env.acceptsProfiles(Profiles.of("test"))) throw new IllegalStateException("Fake provider requires test profile");
            fake=URI.create(base); validateLoopback(fake);
            if(!List.of("","/").contains(fake.getPath())) throw new IllegalStateException("Fake provider base must not contain a path");
        }
    }
    Provider provider(String name) {
        if(!Set.of("GOOGLE","KAKAO").contains(name)) throw new AuthFailure(400,"INVALID_INPUT");
        String prefix="rufit.social."+name.toLowerCase(Locale.ROOT)+".";
        String id=env.getProperty(prefix+"client-id",""); String secret=env.getProperty(prefix+"client-secret","");
        String callback=env.getProperty(prefix+"callback-uri","");
        if(id.isBlank() || secret.isBlank() || callback.isBlank()) throw new AuthFailure(503,"AUTH_PROVIDER_UNAVAILABLE");
        URI cb=uri(callback);
        if(fake!=null && "http".equals(cb.getScheme())) {
            try {validateLoopback(cb);} catch(IllegalStateException e) {throw new AuthFailure(503,"AUTH_PROVIDER_UNAVAILABLE");}
        } else validateHttps(cb);
        if(!cb.getPath().equals("/api/v1/auth/callback/"+name.toLowerCase(Locale.ROOT))) throw new AuthFailure(503,"AUTH_PROVIDER_UNAVAILABLE");
        if(id.length()>512 || secret.length()>4096 || callback.length()>512) throw new AuthFailure(503,"AUTH_PROVIDER_UNAVAILABLE");
        if(fake!=null) {
            String base=fake.toString().replaceAll("/$", "")+"/"+name.toLowerCase(Locale.ROOT);
            return new Provider(name,base,base+"/authorize",base+"/token",base+"/jwks",id,secret,callback,true);
        }
        return name.equals("GOOGLE")
            ? new Provider(name,"https://accounts.google.com","https://accounts.google.com/o/oauth2/v2/auth","https://oauth2.googleapis.com/token","https://www.googleapis.com/oauth2/v3/certs",id,secret,callback,false)
            : new Provider(name,"https://kauth.kakao.com","https://kauth.kakao.com/oauth/authorize","https://kauth.kakao.com/oauth/token","https://kauth.kakao.com/.well-known/jwks.json",id,secret,callback,false);
    }
    String returnUri(String client,String route) {
        if(!Set.of("IOS","ANDROID").contains(client) || !"APP_LOGIN".equals(route)) throw new AuthFailure(400,"INVALID_INPUT");
        String value=env.getProperty("rufit.social."+client.toLowerCase(Locale.ROOT)+"-return-uri","");
        if(value.isBlank()) throw new AuthFailure(503,"AUTH_PROVIDER_UNAVAILABLE");
        URI uri=uri(value);
        String scheme=uri.getScheme();
        if(value.length()>512 || scheme==null || uri.isOpaque() || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
            || !scheme.matches("[a-z][a-z0-9+.-]{2,31}") || Set.of("http","file","data","javascript","intent").contains(scheme)) throw new AuthFailure(503,"AUTH_PROVIDER_UNAVAILABLE");
        return value;
    }
    static URI uri(String value) { try {return URI.create(value);}catch(Exception e){throw new AuthFailure(503,"AUTH_PROVIDER_UNAVAILABLE");} }
    static void validateHttps(URI u) {
        if(!"https".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null)
            throw new AuthFailure(503,"AUTH_PROVIDER_UNAVAILABLE");
    }
    static void validateLoopback(URI u) {
        if(!"http".equals(u.getScheme()) || !Set.of("127.0.0.1","[::1]").contains(u.getHost()) || u.getPort()<1 || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null)
            throw new IllegalStateException("Test provider must use an explicit loopback IP and port");
    }
    static String query(Map<String,String> values) {
        return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    record Provider(String name,String issuer,String authorization,String token,String jwks,String clientId,String secret,String callback,boolean fake) {
        @Override public String toString() { return "Provider[redacted]"; }
        String authorize(String state,String nonce,String challenge) {
            return authorization+"?"+query(Map.of("client_id",clientId,"redirect_uri",callback,"response_type","code","scope","openid",
                "state",state,"nonce",nonce,"code_challenge",challenge,"code_challenge_method","S256"));
        }
    }
}
