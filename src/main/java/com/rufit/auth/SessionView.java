package com.rufit.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties={"account","sessionId","tokenType","accessToken","accessTokenExpiresAt","refreshToken","refreshTokenExpiresAt"})
public record SessionView(Account account,UUID sessionId,String tokenType,String accessToken,Instant accessTokenExpiresAt,
                          String refreshToken,Instant refreshTokenExpiresAt) {
    @Schema(requiredProperties={"id","isNewAccount","profileStatus"})
    public record Account(@Schema(maxLength=100,description="불투명 owner 키, 1~100 UTF-16 단위. 원문 보존") String id,
                          boolean isNewAccount,String profileStatus) {}
    // record 기본 toString은 토큰을 출력하므로 안전한 고정 표현으로 교체한다.
    @Override public String toString() { return "SessionView[redacted]"; }
}
