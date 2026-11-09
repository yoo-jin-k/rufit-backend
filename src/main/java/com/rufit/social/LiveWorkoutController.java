package com.rufit.social;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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

  public record Sharing(@NotNull Boolean enabled) {}

  public record Heartbeat(@NotNull Boolean active) {}

  @GetMapping
  public LiveWorkoutService.Snapshot get(Principal p) {
    return service.snapshot(p.getName());
  }

  @PutMapping("/sharing")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void sharing(Principal p, @Valid @RequestBody Sharing body) {
    service.sharing(p.getName(), body.enabled());
  }

  @PutMapping("/mine/{workoutId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void heartbeat(
    Principal p,
    @PathVariable String workoutId,
    @Valid @RequestBody Heartbeat body
  ) {
    service.heartbeat(p.getName(), workoutId, body.active());
  }

  @PostMapping("/{memberId}/{workoutId}/cheer")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cheer(Principal p, @PathVariable UUID memberId, @PathVariable String workoutId) {
    service.cheer(p.getName(), memberId, workoutId);
  }
}
