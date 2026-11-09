package com.rufit.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 공개 이메일 인증과 로그인 후 연결 작업을 서로 다른 경로로 분리합니다. */
@RestController
@Profile("social")
@RequestMapping("/api/v1")
public class EmailAccountController {
  private final EmailAccounts emails;
  private final AuthSessions sessions;
  private final LoginAttempts attempts;
  public EmailAccountController(EmailAccounts emails,AuthSessions sessions,LoginAttempts attempts) {
    this.emails=emails; this.sessions=sessions; this.attempts=attempts;
  }
  @PostMapping("/auth/email/registrations") @ResponseStatus(HttpStatus.ACCEPTED)
  EmailAccounts.Requested register(@Valid @RequestBody Address input,HttpServletRequest request) {
    return emails.request(input.email(),"REGISTER",null,null,request.getRemoteAddr());
  }
  @PostMapping("/auth/email/registrations/confirm")
  SessionView confirmRegister(@Valid @RequestBody Confirm input,HttpServletRequest request) {
    return emails.confirm(input,"REGISTER",null,null,request.getRemoteAddr());
  }
  @PostMapping("/auth/email/login")
  SessionView login(@Valid @RequestBody Login input,HttpServletRequest request) {
    return emails.login(input.email(),input.password(),request.getRemoteAddr());
  }
  @PostMapping("/auth/email/password-resets") @ResponseStatus(HttpStatus.ACCEPTED)
  EmailAccounts.Requested reset(@Valid @RequestBody Address input,HttpServletRequest request) {
    return emails.request(input.email(),"RESET",null,null,request.getRemoteAddr());
  }
  @PostMapping("/auth/email/password-resets/confirm") @ResponseStatus(HttpStatus.NO_CONTENT)
  void confirmReset(@Valid @RequestBody Confirm input,HttpServletRequest request) {
    emails.confirm(input,"RESET",null,null,request.getRemoteAddr());
  }
  @GetMapping("/account/login-methods")
  EmailAccounts.Methods methods(Principal principal) { return emails.methods(principal.getName()); }
  @PostMapping("/account/email-link-requests") @ResponseStatus(HttpStatus.ACCEPTED)
  EmailAccounts.Requested linkEmail(@Valid @RequestBody Address input,Principal principal,HttpServletRequest request) {
    return emails.request(input.email(),"LINK",principal.getName(),session(request),request.getRemoteAddr());
  }
  @PostMapping("/account/email-link-requests/confirm") @ResponseStatus(HttpStatus.NO_CONTENT)
  void confirmEmail(@Valid @RequestBody Confirm input,Principal principal,HttpServletRequest request) {
    emails.confirm(input,"LINK",principal.getName(),session(request),request.getRemoteAddr());
  }
  @PostMapping("/account/social-link-attempts") @ResponseStatus(HttpStatus.CREATED)
  AuthController.Started linkSocial(@Valid @RequestBody AuthController.Start input,Principal principal,HttpServletRequest request) {
    return attempts.startLink(input,request.getRemoteAddr(),principal.getName(),session(request));
  }
  @PostMapping("/account/social-link-exchanges") @ResponseStatus(HttpStatus.NO_CONTENT)
  void confirmSocial(@Valid @RequestBody AuthController.Exchange input,Principal principal,HttpServletRequest request) {
    attempts.exchangeLink(input,request.getRemoteAddr(),principal.getName(),session(request));
  }
  /** 명시적으로 통합을 선택한 요청만 허용하고 대상 계정은 외부 인증 결과에서 확인합니다. */
  @PostMapping("/account/social-merge-exchanges") @ResponseStatus(HttpStatus.NO_CONTENT)
  void mergeSocial(@Valid @RequestBody Merge input,Principal principal,HttpServletRequest request) {
    attempts.exchangeMerge(new AuthController.Exchange(input.attemptId(),input.handoffCode(),input.codeVerifier()),
      request.getRemoteAddr(),principal.getName(),session(request));
  }
  public record Merge(@NotNull UUID attemptId,
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String handoffCode,
    @NotBlank @Pattern(regexp="[A-Za-z0-9._~-]{43,128}") String codeVerifier,
    @NotNull @AssertTrue Boolean confirmMerge) {
    public String toString(){return "Merge[redacted]";}
  }
  private UUID session(HttpServletRequest request) {
    String header=request.getHeader("Authorization");
    if(header==null || !header.startsWith("Bearer ")) throw AuthFailure.proof();
    return sessions.sessionId(header.substring(7));
  }
  public record Address(@NotBlank @Size(max=254) String email) { public String toString(){return "Address[redacted]";} }
  public record Login(@NotBlank @Size(max=254) String email,@NotNull @Size(max=128) String password) { public String toString(){return "Login[redacted]";} }
  public record Confirm(@NotNull UUID requestId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String verificationToken,@NotNull @Size(min=15,max=128) String password) { public String toString(){return "Confirm[redacted]";} }
}
