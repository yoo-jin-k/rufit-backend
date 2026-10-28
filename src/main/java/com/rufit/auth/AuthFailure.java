package com.rufit.auth;

/** 인증 실패에는 고정 코드만 담아 제공자 응답/비밀이 예외 로그로 새지 않게 한다. */
public final class AuthFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final long retryAfter;
    public AuthFailure(int status, String code) { this(status, code, 0); }
    public AuthFailure(int status, String code, long retryAfter) {
        super(code); this.status=status; this.code=code; this.retryAfter=retryAfter;
    }
    public int status() { return status; }
    public String code() { return code; }
    public long retryAfter() { return retryAfter; }
    public static AuthFailure proof() { return new AuthFailure(401,"AUTHENTICATION_REQUIRED"); }
    public static AuthFailure expired() { return new AuthFailure(401,"SESSION_EXPIRED"); }
}
