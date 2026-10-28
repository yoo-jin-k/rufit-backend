package com.rufit.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Component
@Profile("social")
public class TransientSecrets {
    private final SecretKeySpec key;
    public TransientSecrets(@Value("${rufit.social.transient-key}") String encoded) {
        try { byte[] bytes=Base64.getDecoder().decode(encoded); if(bytes.length!=32) throw new IllegalArgumentException(); key=new SecretKeySpec(bytes,"AES"); }
        catch(Exception e) { throw new IllegalStateException("Social transient encryption configuration is invalid"); }
    }
    String encrypt(UUID attempt,String value) {
        try {
            byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(attempt.toString().getBytes(StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result=new byte[nonce.length+encrypted.length]; System.arraycopy(nonce,0,result,0,12);System.arraycopy(encrypted,0,result,12,encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch(Exception e) { throw new AuthFailure(503,"AUTHENTICATION_UNAVAILABLE"); }
    }
    String decrypt(UUID attempt,String value) {
        try {
            byte[] bytes=Base64.getDecoder().decode(value);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,bytes,0,12));
            cipher.updateAAD(attempt.toString().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new AuthFailure(503,"AUTHENTICATION_UNAVAILABLE"); }
    }
}
