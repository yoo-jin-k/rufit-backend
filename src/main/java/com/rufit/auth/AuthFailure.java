package com.rufit.auth;

/** 인증 실패에는 고정 코드만 담아 제공자 응답/비밀이 예외 로그로 새지 않게 합니다. */
public final class AuthFailure extends RuntimeException {

  private final int status;
  private final String code;
  private final long retryAfter;
  private String verifiedKakaoEmail;
  private boolean kakaoEmailUnavailable;

  public AuthFailure(int status, String code) {
    this(status, code, 0);
  }

  public AuthFailure(int status, String code, long retryAfter) {
    super(code);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }

  /** 인증 완료된 연결 요청에만 이메일을 전달하며 예외 메시지에는 넣지 않습니다. */
  static AuthFailure verifiedLinkConflict(String provider, String email) {
    var failure = new AuthFailure(409, "LOGIN_METHOD_CONFLICT");
    if ("KAKAO".equals(provider)) {
      failure.verifiedKakaoEmail = ProviderGateway.displayEmail(email);
      failure.kakaoEmailUnavailable = failure.verifiedKakaoEmail == null;
    }
    return failure;
  }

  public String verifiedKakaoEmail() { return verifiedKakaoEmail; }
  public boolean kakaoEmailUnavailable() { return kakaoEmailUnavailable; }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public long retryAfter() {
    return retryAfter;
  }

  public static AuthFailure proof() {
    return new AuthFailure(401, "AUTHENTICATION_REQUIRED");
  }

  public static AuthFailure expired() {
    return new AuthFailure(401, "SESSION_EXPIRED");
  }
}
