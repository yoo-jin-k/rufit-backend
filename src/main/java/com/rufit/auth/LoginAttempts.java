package com.rufit.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
@Profile("social")
public class LoginAttempts {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final SocialProviders providers;
    private final ProviderGateway gateway;
    private final TransientSecrets secrets;
    private final AuthSessions sessions;
    private final AuthLimits limits;
    public LoginAttempts(JdbcTemplate jdbc,Clock clock,PlatformTransactionManager manager,SocialProviders providers,ProviderGateway gateway,
                         TransientSecrets secrets,AuthSessions sessions,AuthLimits limits) {
        this.jdbc=jdbc;this.clock=clock;tx=new TransactionTemplate(manager);this.providers=providers;this.gateway=gateway;
        this.secrets=secrets;this.sessions=sessions;this.limits=limits;
    }
    public AuthController.Started start(AuthController.Start input,String ip) {
        if(!AuthCrypto.secretFormat(input.codeChallenge()) || !"S256".equals(input.codeChallengeMethod())) throw new AuthFailure(400,"INVALID_INPUT");
        var provider=providers.provider(input.provider()); String returnUri=providers.returnUri(input.client(),input.returnRoute());
        limits.take("start-minute:"+ip,20,60);limits.take("start-hour:"+ip,100,3600);
        return tx.execute(status->{
            Instant now=clock.instant();String ipHash=AuthCrypto.hash(ip);
            jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,2))",ipHash);
            int active=jdbc.queryForObject("select count(*) from auth_login_attempts where ip_hash=? and expires_at>? and status in ('PENDING','CLAIMED','READY')",Integer.class,ipHash,Timestamp.from(now));
            if(active>=30) {
                Instant next=jdbc.queryForObject("select min(expires_at) from auth_login_attempts where ip_hash=? and expires_at>? and status in ('PENDING','CLAIMED','READY')",Timestamp.class,ipHash,Timestamp.from(now)).toInstant();
                throw new AuthFailure(429,"RATE_LIMITED",Math.max(1,(Duration.between(now,next).toMillis()+999)/1000));
            }
            UUID id=UUID.randomUUID();String state=AuthCrypto.secret(),nonce=AuthCrypto.secret(),verifier=AuthCrypto.secret();
            Instant expires=now.plusSeconds(300);
            jdbc.update("insert into auth_login_attempts(id,provider,client,return_uri,ip_hash,challenge,state_hash,nonce_hash,verifier_cipher,status,expires_at) values(?,?,?,?,?,?,?,?,?,'PENDING',?)",
                id,input.provider(),input.client(),returnUri,ipHash,input.codeChallenge(),AuthCrypto.hash(state),AuthCrypto.hash(nonce),secrets.encrypt(id,verifier),Timestamp.from(expires));
            return new AuthController.Started(id,provider.authorize(state,nonce,AuthCrypto.challenge(verifier)),expires);
        });
    }
    public String callback(String providerName,String state,String code,String error) {
        if(!Set.of("google","kakao").contains(providerName) || !AuthCrypto.secretFormat(state) || (code==null)==(error==null)
            || (code!=null && (code.isBlank() || code.length()>2048)) || (error!=null && (error.isBlank() || error.length()>128))) throw new AuthFailure(400,"INVALID_INPUT");
        String name=providerName.toUpperCase(Locale.ROOT);
        // CLAIMED 상태를 먼저 커밋한다. 외부 호출을 잠금 안에서 실행하거나 유실 뒤 다시 보내지 않는다.
        Attempt attempt=tx.execute(status->{
            var rows=jdbc.query("select * from auth_login_attempts where state_hash=? for update",this::map,AuthCrypto.hash(state));
            if(rows.isEmpty() || !rows.getFirst().provider().equals(name)) throw new AuthFailure(400,"INVALID_INPUT");
            Attempt a=rows.getFirst();
            if(!clock.instant().isBefore(a.expires())) throw AuthFailure.proof();
            if(!a.status().equals("PENDING")) throw new AuthFailure(409,"LOGIN_ATTEMPT_CONFLICT");
            jdbc.update("update auth_login_attempts set status=? where id=?",error==null?"CLAIMED":"FAILED",a.id());
            if(error!=null) jdbc.update("update auth_login_attempts set verifier_cipher=null where id=?",a.id());
            return a;
        });
        if(error!=null) {
            if(!error.equals("access_denied")) throw AuthFailure.proof();
            return attempt.returnUri()+"?"+SocialProviders.query(Map.of("attemptId",attempt.id().toString(),"error","LOGIN_CANCELLED"));
        }
        ProviderGateway.Identity identity;
        try { identity=gateway.exchange(providers.provider(name),code,secrets.decrypt(attempt.id(),attempt.cipher()),attempt.nonceHash()); }
        catch(AuthFailure failure) {
            tx.executeWithoutResult(s->jdbc.update("update auth_login_attempts set status='FAILED',verifier_cipher=null where id=? and status='CLAIMED'",attempt.id()));
            throw failure;
        }
        String handoff=AuthCrypto.secret();
        tx.executeWithoutResult(status->{
            Attempt current=jdbc.query("select * from auth_login_attempts where id=? for update",this::map,attempt.id()).getFirst();
            Instant now=clock.instant();
            if(!current.status().equals("CLAIMED") || !now.isBefore(current.expires())) throw AuthFailure.proof();
            Instant expires=now.plusSeconds(60).isBefore(current.expires())?now.plusSeconds(60):current.expires();
            jdbc.update("update auth_login_attempts set status='READY',verifier_cipher=null,handoff_hash=?,handoff_expires_at=?,identity_issuer=?,identity_subject=? where id=?",
                AuthCrypto.hash(handoff),Timestamp.from(expires),identity.issuer(),identity.subject(),attempt.id());
        });
        return attempt.returnUri()+"?"+SocialProviders.query(Map.of("attemptId",attempt.id().toString(),"handoffCode",handoff));
    }
    public SessionView exchange(AuthController.Exchange input,String ip) {
        if(!AuthCrypto.secretFormat(input.handoffCode()) || input.codeVerifier()==null || !input.codeVerifier().matches("[A-Za-z0-9._~-]{43,128}")) throw new AuthFailure(400,"INVALID_INPUT");
        SessionView result=tx.execute(status->{
            var rows=jdbc.query("select * from auth_login_attempts where id=? for update",this::map,input.attemptId());
            if(rows.isEmpty()) return null;
            Attempt a=rows.getFirst();
            if(!AuthCrypto.same(a.handoffHash(),AuthCrypto.hash(input.handoffCode())) || !AuthCrypto.same(a.challenge(),AuthCrypto.challenge(input.codeVerifier()))) return null;
            Instant now=clock.instant();
            if(!now.isBefore(a.expires()) || a.handoffExpires()==null || !now.isBefore(a.handoffExpires())) throw AuthFailure.proof();
            if(a.status().equals("CONSUMED")) throw new AuthFailure(409,"LOGIN_ATTEMPT_CONFLICT");
            if(!a.status().equals("READY")) throw AuthFailure.proof();
            SessionView view=sessions.open(a.provider(),a.issuer(),a.subject());
            jdbc.update("update auth_login_attempts set status='CONSUMED' where id=?",a.id());
            return view;
        });
        // 행 잠금/연결을 반환한 뒤 별도 카운터를 갱신해 연결 풀 교착을 피한다.
        if(result==null) {limits.take("bad-handoff:"+input.attemptId()+":"+ip,5,60);throw AuthFailure.proof();}
        return result;
    }
    private Attempt map(ResultSet rs,int row) throws SQLException {
        Timestamp handoff=rs.getTimestamp("handoff_expires_at");
        return new Attempt(rs.getObject("id",UUID.class),rs.getString("provider"),rs.getString("return_uri"),rs.getString("challenge"),rs.getString("nonce_hash"),
            rs.getString("verifier_cipher"),rs.getString("status"),rs.getTimestamp("expires_at").toInstant(),rs.getString("handoff_hash"),handoff==null?null:handoff.toInstant(),
            rs.getString("identity_issuer"),rs.getString("identity_subject"));
    }
    private record Attempt(UUID id,String provider,String returnUri,String challenge,String nonceHash,String cipher,String status,Instant expires,String handoffHash,Instant handoffExpires,String issuer,String subject) {
        @Override public String toString() { return "Attempt[redacted]"; }
    }
}
