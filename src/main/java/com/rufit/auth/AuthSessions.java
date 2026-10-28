package com.rufit.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
@Profile("social")
public class AuthSessions {
    final JdbcTemplate jdbc;
    final SessionTokens tokens;
    final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final AuthLimits limits;
    public AuthSessions(JdbcTemplate jdbc,SessionTokens tokens,Clock clock,org.springframework.transaction.PlatformTransactionManager manager,AuthLimits limits) {
        this.jdbc=jdbc;this.tokens=tokens;this.clock=clock;this.limits=limits;
        tx=new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    /** 호출한 쪽의 트랜잭션에 참여해 handoff 소비만 남는 부분 성공을 막는다. */
    @Transactional
    public SessionView open(String provider,String issuer,String subject) {
        if(!Set.of("GOOGLE","KAKAO").contains(provider) || issuer==null || subject==null || issuer.isBlank() || subject.isBlank()
            || issuer.length()>255 || subject.length()>255) throw AuthFailure.proof();
        Instant now=clock.instant();
        String owner=AuthCrypto.owner(issuer,subject);
        // 같은 외부 계정의 첫 동시 로그인도 회원 원장 하나만 만든다.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",owner);
        var existing=jdbc.query("select a.id from auth_identities i join auth_accounts a on a.id=i.account_id where i.provider=? and i.issuer=? and i.subject=?",
            (rs,n)->rs.getObject(1,UUID.class),provider,issuer,subject);
        UUID account;
        boolean isNew=false;
        if(existing.isEmpty()) {
            var owners=jdbc.query("select id from auth_accounts where owner_key=?",(rs,n)->rs.getObject(1,UUID.class),owner);
            if(owners.isEmpty()) {
                account=UUID.randomUUID(); isNew=true;
                jdbc.update("insert into auth_accounts(id,owner_key,created_at) values(?,?,?)",account,owner,Timestamp.from(now));
            } else account=owners.getFirst();
            jdbc.update("insert into auth_identities(provider,issuer,subject,account_id) values(?,?,?,?)",provider,issuer,subject,account);
        } else account=existing.getFirst();
        UUID session=UUID.randomUUID();
        Instant expires=now.plus(Duration.ofDays(14));
        jdbc.update("insert into auth_sessions(id,account_id,created_at,idle_expires_at,absolute_expires_at) values(?,?,?,?,?)",
            session,account,Timestamp.from(now),Timestamp.from(expires),Timestamp.from(now.plus(Duration.ofDays(30))));
        return issue(account,owner,session,isNew,now,expires);
    }
    SessionView issue(UUID account,String owner,UUID session,boolean isNew,Instant now,Instant refreshExpires) {
        String refresh=AuthCrypto.secret();
        jdbc.update("insert into auth_refresh_tokens(token_hash,session_id,created_at) values(?,?,?)",AuthCrypto.hash(refresh),session,Timestamp.from(now));
        Instant accessExpires=now.plusSeconds(600).isBefore(refreshExpires)?now.plusSeconds(600):refreshExpires;
        String access=tokens.issue(account,session,now,accessExpires);
        boolean profile=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from user_profiles where owner_name=?)",Boolean.class,owner));
        return new SessionView(new SessionView.Account(owner,isNew,profile?"PRESENT":"MISSING"),session,"Bearer",access,accessExpires,refresh,refreshExpires);
    }
    /** 매 인증마다 세션을 조회한다. 캐시를 사용하지 않아 폐기 직후 새 요청을 차단한다. */
    public String authenticate(String token) {
        Instant now=clock.instant();
        var identity=tokens.verify(token,now);
        var rows=jdbc.query("select a.owner_key from auth_sessions s join auth_accounts a on a.id=s.account_id where s.id=? and s.account_id=? and s.revoked_at is null and s.idle_expires_at>? and s.absolute_expires_at>?",
            (rs,n)->rs.getString(1),identity.sessionId(),identity.accountId(),Timestamp.from(now),Timestamp.from(now));
        if(rows.size()!=1) throw AuthFailure.expired();
        return rows.getFirst();
    }
    /** 회전은 세션 행 잠금 하나로 직렬화한다. 과거 토큰 재사용 폐기는 401 전에 커밋한다. */
    public SessionView refresh(String refresh) {
        requireRefresh(refresh);
        String hash=AuthCrypto.hash(refresh);UUID session=knownSession(hash);
        if(session==null) throw AuthFailure.expired();
        // 별도 트랜잭션 카운터는 세션 행을 잠그기 전에 확인한다.
        limits.take("refresh:"+session,12,60);
        SessionView result=tx.execute(status->{
            Grant grant=lock(session);Instant now=clock.instant();
            if(grant==null || grant.revoked() || !now.isBefore(grant.idle()) || !now.isBefore(grant.absolute())) return null;
            var used=jdbc.query("select used_at is not null from auth_refresh_tokens where token_hash=? and session_id=?",(rs,n)->rs.getBoolean(1),hash,session);
            if(used.isEmpty()) return null;
            if(used.getFirst()) {
                jdbc.update("update auth_sessions set revoked_at=? where id=?",Timestamp.from(now),session);
                return null;
            }
            Instant expires=now.plus(Duration.ofDays(14)).isBefore(grant.absolute())?now.plus(Duration.ofDays(14)):grant.absolute();
            jdbc.update("update auth_refresh_tokens set used_at=? where token_hash=?",Timestamp.from(now),hash);
            jdbc.update("update auth_sessions set idle_expires_at=? where id=?",Timestamp.from(expires),session);
            return issue(grant.account(),grant.owner(),session,false,now,expires);
        });
        // 트랜잭션 안에서 예외를 던지면 폐기가 롤백되어 재사용 토큰이 계속 살아남는다.
        if(result==null) throw AuthFailure.expired();
        return result;
    }
    /** 이미 쓴 refresh도 같은 grant를 찾는다. 다른 기기의 세션과 운동 기록은 건드리지 않는다. */
    public void logout(String refresh) {
        requireRefresh(refresh);UUID session=knownSession(AuthCrypto.hash(refresh));if(session==null) return;
        tx.executeWithoutResult(status->{
            if(lock(session)!=null) jdbc.update("update auth_sessions set revoked_at=coalesce(revoked_at,?) where id=?",Timestamp.from(clock.instant()),session);
        });
    }
    private void requireRefresh(String refresh) {if(!AuthCrypto.secretFormat(refresh)) throw new AuthFailure(400,"INVALID_INPUT");}
    private UUID knownSession(String hash) {
        var rows=jdbc.query("select session_id from auth_refresh_tokens where token_hash=?",(rs,n)->rs.getObject(1,UUID.class),hash);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Grant lock(UUID session) {
        var rows=jdbc.query("select s.account_id,a.owner_key,s.idle_expires_at,s.absolute_expires_at,s.revoked_at from auth_sessions s join auth_accounts a on a.id=s.account_id where s.id=? for update of s",
            (rs,n)->new Grant(rs.getObject(1,UUID.class),rs.getString(2),rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant(),rs.getTimestamp(5)!=null),session);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private record Grant(UUID account,String owner,Instant idle,Instant absolute,boolean revoked) {}
}
