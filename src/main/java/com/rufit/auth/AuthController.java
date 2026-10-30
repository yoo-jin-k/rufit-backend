package com.rufit.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("social")
@RequestMapping("/api/v1/auth")
@SecurityRequirements
public class AuthController {

  private final LoginAttempts attempts;
  private final AuthSessions sessions;

  public AuthController(LoginAttempts attempts, AuthSessions sessions) {
    this.attempts = attempts;
    this.sessions = sessions;
  }

  @PostMapping("/login-attempts")
  @ResponseStatus(HttpStatus.CREATED)
  ResponseEntity<Started> start(@Valid @RequestBody Start input, HttpServletRequest request) {
    return ResponseEntity.status(201).body(attempts.start(input, request.getRemoteAddr()));
  }

  @GetMapping("/callback/{provider}")
  @ResponseStatus(HttpStatus.FOUND)
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
    responseCode = "302",
    description = "등록된 앱 복귀 주소",
    headers = @io.swagger.v3.oas.annotations.headers.Header(
      name = "Location",
      schema = @Schema(type = "string")
    )
  )
  ResponseEntity<Void> callback(@PathVariable String provider, HttpServletRequest request) {
    // 컨테이너가 여러 query 중 하나를 임의 선택하도록 두지 않습니다.
    for (var values : request.getParameterMap().values())
      if (values.length != 1) throw new AuthFailure(400, "INVALID_INPUT");
    String result = attempts.callback(
      provider,
      request.getParameter("state"),
      request.getParameter("code"),
      request.getParameter("error")
    );
    return ResponseEntity.status(302).location(URI.create(result)).build();
  }

  @PostMapping("/session-exchanges")
  SessionView exchange(@Valid @RequestBody Exchange input, HttpServletRequest request) {
    return attempts.exchange(input, request.getRemoteAddr());
  }

  @PostMapping("/refresh")
  SessionView refresh(@Valid @RequestBody Refresh input) {
    return sessions.refresh(input.refreshToken());
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(@Valid @RequestBody Refresh input) {
    sessions.logout(input.refreshToken());
  }

  public record Refresh(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken) {
    @Override
    public String toString() {
      return "Refresh[redacted]";
    }
  }

  public record Start(
    @NotBlank @Pattern(regexp = "GOOGLE|KAKAO") String provider,
    @NotBlank @Pattern(regexp = "IOS|ANDROID") String client,
    @NotBlank @Pattern(regexp = "APP_LOGIN") String returnRoute,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String codeChallenge,
    @NotBlank @Pattern(regexp = "S256") String codeChallengeMethod
  ) {}

  @Schema(requiredProperties = { "attemptId", "authorizationUrl", "expiresAt" })
  public record Started(UUID attemptId, String authorizationUrl, Instant expiresAt) {
    @Override
    public String toString() {
      return "Started[redacted]";
    }
  }

  public record Exchange(
    @NotNull UUID attemptId,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String handoffCode,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._~-]{43,128}") String codeVerifier
  ) {
    @Override
    public String toString() {
      return "Exchange[redacted]";
    }
  }
}
