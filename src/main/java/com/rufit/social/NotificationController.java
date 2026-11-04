package com.rufit.social;

import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 수신자는 요청 값이 아니라 인증된 본인 계정으로 고정합니다. */
@RestController
@RequestMapping("/api/v1/social/notifications")
public class NotificationController {

  private final NotificationService service;

  NotificationController(NotificationService service) {
    this.service = service;
  }

  @GetMapping
  public NotificationService.Inbox inbox(Principal p, @RequestParam(required = false) Long before) {
    return service.inbox(p.getName(), before);
  }

  @PostMapping("/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void read(Principal p, @PathVariable long id) {
    service.read(p.getName(), id);
  }
}
