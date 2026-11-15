package com.rufit.exercise;

import io.swagger.v3.oas.annotations.media.Schema;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 종목 즐겨찾기입니다. 추가·해제는 여러 번 보내도 결과가 같습니다.
 * 내가 볼 수 있는 종목(공용 또는 내 종목)만 담을 수 있습니다.
 */
@RestController
@RequestMapping("/api/v1/exercises")
public class ExerciseFavoritesController {

  private final ExerciseFavoritesService service;

  ExerciseFavoritesController(ExerciseFavoritesService service) {
    this.service = service;
  }

  @GetMapping("/favorites")
  public Favorites list(Principal principal) {
    return new Favorites(service.list(principal.getName()));
  }

  @PutMapping("/{id}/favorite")
  public Favorite add(@PathVariable UUID id, Principal principal) {
    return service.add(id, principal.getName());
  }

  @DeleteMapping("/{id}/favorite")
  public ResponseEntity<Void> remove(@PathVariable UUID id, Principal principal) {
    service.remove(id, principal.getName());
    return ResponseEntity.noContent().build();
  }

  @Schema(requiredProperties = { "exerciseId", "createdAt" })
  public record Favorite(UUID exerciseId, Instant createdAt) {}

  @Schema(requiredProperties = { "favorites" })
  public record Favorites(List<Favorite> favorites) {}
}
