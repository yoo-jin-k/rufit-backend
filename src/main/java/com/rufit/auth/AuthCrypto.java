package com.rufit.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** URL/저장소에 필요한 난수와 비교용 해시. 계정 키 생성식은 기존 OIDC와 동일하게 유지합니다. */
public final class AuthCrypto {

  private static final SecureRandom RANDOM = new SecureRandom();

  private AuthCrypto() {}

  public static String secret() {
    byte[] value = new byte[32];
    RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public static String hash(String value) {
    return HexFormat.of().formatHex(digest(value));
  }

  public static String challenge(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(value));
  }

  public static boolean same(String a, String b) {
    return (
      a != null &&
      b != null &&
      MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8))
    );
  }

  public static String owner(String issuer, String subject) {
    return "oidc:" + hash(issuer.length() + ":" + issuer + subject);
  }

  public static boolean secretFormat(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) return false;
    try {
      return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(Base64.getUrlDecoder().decode(value))
        .equals(value);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
