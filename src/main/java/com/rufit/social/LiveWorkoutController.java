package com.rufit.social;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 운동 기록을 변경하지 않고 친구에게 보이는 상태와 응원만 처리합니다. */
@RestController
@RequestMapping("/api/v1/social/live")
public class LiveWorkoutController {

  private final LiveWorkoutService service;

  LiveWorkoutController(LiveWorkoutService service) {
    this.service = service;
  }

  /** 공개 범위입니다. FRIENDS는 친구에게만, PUBLIC은 친구가 아닌 회원에게도 보입니다. */
  public enum Scope {
    FRIENDS,
    PUBLIC,
  }

  /** scope를 생략하면 지금 값을 유지합니다. 이전 앱은 enabled만 보냅니다. */
  public record Sharing(@NotNull Boolean enabled, Scope scope) {}

  /** volume은 진행 중 운동에서 완료한 세트의 무게×횟수 합계(kg)입니다. 생략하면 이전 값을 유지합니다. */
  public record Heartbeat(
    @NotNull Boolean active,
    @PositiveOrZero @Max(10_000_000) Double volume
  ) {}

  @GetMapping
  public LiveWorkoutService.Snapshot get(Principal p) {
    return service.snapshot(p.getName());
  }

  @PutMapping("/sharing")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void sharing(Principal p, @Valid @RequestBody Sharing body) {
    service.sharing(p.getName(), body.enabled(), body.scope());
  }

  @PutMapping("/mine/{workoutId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void heartbeat(
    Principal p,
    @PathVariable String workoutId,
    @Valid @RequestBody Heartbeat body
  ) {
    service.heartbeat(p.getName(), workoutId, body.active(), body.volume());
  }

  @PostMapping("/{memberId}/{workoutId}/cheer")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cheer(Principal p, @PathVariable UUID memberId, @PathVariable String workoutId) {
    service.cheer(p.getName(), memberId, workoutId);
  }
}
