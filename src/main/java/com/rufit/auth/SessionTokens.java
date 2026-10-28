package com.rufit.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.time.*;
import java.util.*;
import java.net.URI;

@Component
@Profile("social")
public class SessionTokens {
    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String issuer,audience,keyId;
    public SessionTokens(@Value("${rufit.session.issuer}") String issuer,
                         @Value("${rufit.session.audience}") String audience,
                         @Value("${rufit.session.key-id}") String keyId,
                         @Value("${rufit.session.private-key-path}") String path) {
        try {
            URI uri=URI.create(issuer);
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || audience.isBlank() || audience.length()>128 || !keyId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException();
            Path keyPath=Path.of(path);
            if(Files.size(keyPath)>16384) throw new IllegalArgumentException();
            String pem=Files.readString(keyPath).replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            KeyFactory factory=KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey key=(RSAPrivateCrtKey)factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            if(key.getModulus().bitLength()<2048) throw new IllegalArgumentException();
            privateKey=key;
            publicKey=(RSAPublicKey)factory.generatePublic(new RSAPublicKeySpec(key.getModulus(),key.getPublicExponent()));
        } catch(Exception e) { throw new IllegalStateException("Rufit session signing configuration is invalid"); }
        this.issuer=issuer; this.audience=audience; this.keyId=keyId;
    }
    String issue(UUID accountId,UUID sessionId,Instant now,Instant expires) {
        try {
            var claims=new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(accountId.toString())
                .claim("sid",sessionId.toString()).issueTime(Date.from(now)).notBeforeTime(Date.from(now))
                .expirationTime(Date.from(expires)).jwtID(UUID.randomUUID().toString()).build();
            var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt")).keyID(keyId).build(),claims);
            token.sign(new RSASSASigner(privateKey));
            String result=token.serialize();
            if(result.length()>4096) throw new IllegalStateException();
            return result;
        } catch(Exception e) { throw new AuthFailure(503,"AUTHENTICATION_UNAVAILABLE"); }
    }
    /** 토큰이 지정한 URL/키를 따라가지 않고 설정된 자체 공개키로만 검증한다. */
    Identity verify(String value,Instant now) {
        try {
            if(value==null || value.length()>4096 || !value.matches("[A-Za-z0-9_.-]+")) throw AuthFailure.proof();
            SignedJWT jwt=SignedJWT.parse(value);
            var h=jwt.getHeader();
            if(!JWSAlgorithm.RS256.equals(h.getAlgorithm()) || !new JOSEObjectType("at+jwt").equals(h.getType())
                || !keyId.equals(h.getKeyID()) || h.getCriticalParams()!=null || !jwt.verify(new RSASSAVerifier(publicKey))) throw AuthFailure.proof();
            var c=jwt.getJWTClaimsSet();
            if(!issuer.equals(c.getIssuer()) || !List.of(audience).equals(c.getAudience()) || c.getExpirationTime()==null || c.getIssueTime()==null || c.getNotBeforeTime()==null
                || !now.isBefore(c.getExpirationTime().toInstant().plusSeconds(30))
                || c.getIssueTime().toInstant().isAfter(now.plusSeconds(30)) || c.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(30))
                || !c.getExpirationTime().after(c.getIssueTime()) || c.getExpirationTime().toInstant().isAfter(c.getIssueTime().toInstant().plusSeconds(600))) throw AuthFailure.proof();
            return new Identity(UUID.fromString(c.getSubject()),UUID.fromString(c.getStringClaim("sid")));
        } catch(Exception e) { throw AuthFailure.proof(); }
    }
    record Identity(UUID accountId,UUID sessionId) {}
}
