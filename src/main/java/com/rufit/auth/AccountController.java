package com.rufit.auth;

import java.security.Principal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 로그인한 회원 본인의 계정을 탈퇴합니다. 되돌릴 수 없으므로 앱에서 두 번 확인한 뒤 부릅니다. */
@RestController
@Profile("social")
@RequestMapping("/api/v1/account")
public class AccountController {

  private final AccountDeletion deletion;

  public AccountController(AccountDeletion deletion) {
    this.deletion = deletion;
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(Principal principal) {
    deletion.delete(principal.getName());
  }
}
