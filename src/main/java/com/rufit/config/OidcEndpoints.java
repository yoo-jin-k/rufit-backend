package com.rufit.config;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

final class OidcEndpoints {

  private OidcEndpoints() {}

  static void validate(String issuer, String jwks, boolean allowLocalHttpJwks) {
    URI issuerUri = endpoint(issuer, "OIDC issuer");
    if (
      !"https".equalsIgnoreCase(issuerUri.getScheme()) || issuerUri.getRawQuery() != null
    ) throw new IllegalArgumentException("OIDC issuer는 query 없는 HTTPS 주소여야 합니다.");
    URI keys = endpoint(jwks, "OIDC JWKS");
    boolean localHttp =
      allowLocalHttpJwks &&
      "http".equalsIgnoreCase(keys.getScheme()) &&
      Set.of("localhost", "127.0.0.1", "[::1]").contains(keys.getHost().toLowerCase(Locale.ROOT));
    if (
      !"https".equalsIgnoreCase(keys.getScheme()) && !localHttp
    ) throw new IllegalArgumentException(
      "OIDC JWKS는 HTTPS 주소여야 합니다. HTTP는 명시적으로 허용한 로컬 테스트 주소만 가능합니다."
    );
  }

  private static URI endpoint(String value, String label) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(label + " 주소 형식을 확인해 주세요.");
    }
    if (
      uri.getHost() == null ||
      uri.getRawUserInfo() != null ||
      uri.getRawFragment() != null ||
      uri.getPort() == 0 ||
      uri.getPort() > 65535
    ) throw new IllegalArgumentException(
      label + " 주소의 호스트·포트·사용자정보·fragment를 확인해 주세요."
    );
    return uri;
  }
}
