package com.rufit.social;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 친구 연결은 공개용 식별자를 사용하며 본인 권한은 인증 세션에서만 가져옵니다. */
@RestController
@RequestMapping("/api/v1/social")
public class FriendController {
  private final FriendService service;
  FriendController(FriendService service) { this.service = service; }
  public enum Action { ACCEPT, REJECT, CANCEL }
  public record Send(@NotNull UUID requestId, @NotNull UUID targetId) {}
  public record Respond(@NotNull Action action) {}
  public record Request(UUID id, UUID requesterId, UUID targetId, String state, String requesterNickname, String targetNickname) {}
  public record Requests(List<Request> incoming, List<Request> outgoing) {}
  public record Members(List<SocialDirectoryController.Member> items) {}

  public enum ReportReason { SPAM, HARASSMENT, INAPPROPRIATE, OTHER }
  public record Report(@NotNull UUID requestId, @NotNull UUID targetId, @NotNull ReportReason reason) {}
  public record Receipt(UUID id, String status) {}
  public record MemberProfile(UUID memberId, String nickname, String relationship) {}

  @DeleteMapping("/friends/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(Principal p, @PathVariable UUID id) { service.remove(p.getName(), id); }
  @GetMapping("/members/{id}")
  public MemberProfile profile(Principal p, @PathVariable UUID id) { return service.profile(p.getName(), id); }
  @PostMapping("/reports")
  public Receipt report(Principal p, @Valid @RequestBody Report body) { return service.report(p.getName(), body); }

  @PostMapping("/requests")
  public Request send(Principal p, @Valid @RequestBody Send body) { return service.send(p.getName(), body); }
  @GetMapping("/requests")
  public Requests requests(Principal p) { return service.requests(p.getName()); }
  @PostMapping("/requests/{id}/respond")
  public Request respond(Principal p, @PathVariable UUID id, @Valid @RequestBody Respond body) {
    return service.respond(p.getName(), id, body.action());
  }
  @GetMapping("/friends")
  public Members friends(Principal p) { return service.friends(p.getName()); }
  @GetMapping("/blocks")
  public Members blocks(Principal p) { return service.blocks(p.getName()); }
  @PostMapping("/blocks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void block(Principal p, @PathVariable UUID id) { service.block(p.getName(), id, true); }
  @DeleteMapping("/blocks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unblock(Principal p, @PathVariable UUID id) { service.block(p.getName(), id, false); }
}
