package com.rufit.social;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 계정 게시물의 조회·편집·삭제 진입점입니다. 소유권과 충돌 검사는 서비스에서 수행합니다. */
@RestController
@RequestMapping("/api/v1/social/posts")
public class JournalController {

  public enum Visibility {
    PRIVATE,
    FRIENDS,
    /** 로그인한 모든 회원에게 보입니다(차단한·차단된 회원 제외). 소셜 피드에 나옵니다. */
    PUBLIC,
  }

  public record Edit(
    @NotNull @Min(0) Long version,
    @NotNull @Size(max = 200) String memo,
    @NotNull LocalDate date,
    @NotNull @Size(max = 160) String summary,
    @NotNull Visibility visibility,
    @NotNull @Size(max = 5) List<@NotNull @Size(max = 2_800_000) String> photos
  ) {}

  public record Like(@NotNull Boolean liked) {}

  private final JournalService service;

  JournalController(JournalService service) {
    this.service = service;
  }

  @PutMapping("/mine/{sourceId}")
  public JournalService.Post save(
    Principal p,
    @PathVariable String sourceId,
    @Valid @RequestBody Edit edit
  ) {
    return service.save(p.getName(), sourceId, edit);
  }

  @GetMapping("/mine/{sourceId}")
  public JournalService.Post mine(Principal p, @PathVariable String sourceId) {
    return service.mine(p.getName(), sourceId);
  }

  /**
   * 소셜 피드: 내가 볼 수 있는 다른 회원의 글(전체 공개 + 친구의 친구 공개)을 최신순으로.
   * following=true면 친구(팔로잉)의 글만, memberId를 주면 그 사람의 글만 돌려줍니다.
   */
  @GetMapping("/feed")
  public JournalService.Page feed(
    Principal p,
    @RequestParam(required = false) UUID memberId,
    @RequestParam(defaultValue = "false") boolean following,
    @RequestParam(defaultValue = "0") int page
  ) {
    return service.feed(p.getName(), memberId, following, page);
  }

  @GetMapping("/{id}")
  public JournalService.Post get(Principal p, @PathVariable UUID id) {
    return service.get(p.getName(), id);
  }

  @GetMapping
  public JournalService.Page list(
    Principal p,
    @RequestParam(required = false) UUID memberId,
    @RequestParam(defaultValue = "0") int page
  ) {
    return service.list(p.getName(), memberId, page);
  }

  @PutMapping("/{id}/like")
  public JournalService.Post like(
    Principal p,
    @PathVariable UUID id,
    @Valid @RequestBody Like like
  ) {
    return service.like(p.getName(), id, like.liked());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(Principal p, @PathVariable UUID id, @RequestParam long version) {
    service.delete(p.getName(), id, version);
  }

  @PostMapping("/{id}/hide")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void hide(Principal p, @PathVariable UUID id) {
    service.hide(p.getName(), id);
  }
}
