package com.rufit.social;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 검색용 정보만 제공합니다. 비공개 프로필이나 내부 계정 식별자는 응답하지 않습니다. */
@RestController
@RequestMapping("/api/v1/social/directory")
public class SocialDirectoryController {

  private final SocialDirectoryService service;

  SocialDirectoryController(SocialDirectoryService service) {
    this.service = service;
  }

  @GetMapping("/me")
  public Settings get(Principal principal) {
    return service.get(principal.getName());
  }

  @PutMapping("/me")
  public Settings update(Principal principal, @Valid @RequestBody Edit edit) {
    return service.update(principal.getName(), edit);
  }

  /** 입력 중인 닉네임의 사용 가능 여부를 반환합니다. 대소문자와 앞뒤 공백은 구분하지 않습니다. */
  @GetMapping("/nickname-availability")
  public Availability nicknameAvailability(Principal principal, @RequestParam String nickname) {
    return service.availability(principal.getName(), nickname);
  }

  @GetMapping("/search")
  public Results search(
    Principal principal,
    @RequestParam String q,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size
  ) {
    return service.search(principal.getName(), q, page, size);
  }

  public record Edit(
    @NotBlank @Size(max = 30) String nickname,
    @NotNull Boolean discoverable,
    @NotNull @PositiveOrZero Long version
  ) {}

  @Schema(requiredProperties = { "memberId", "nickname", "discoverable", "version" })
  public record Settings(
    @Schema(nullable = true) UUID memberId,
    @Schema(nullable = true) String nickname,
    boolean discoverable,
    long version
  ) {}

  public record Member(UUID memberId, String nickname) {}

  /** 확인한 닉네임(앞뒤 공백 제거)과 사용 가능 여부입니다. */
  public record Availability(String nickname, boolean available) {}

  public record Results(List<Member> items, int page, int size, boolean hasNext) {}
}
