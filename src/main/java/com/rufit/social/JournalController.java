package com.rufit.social;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/social/posts")
public class JournalController {

  public enum Visibility {
    PRIVATE,
    FRIENDS,
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
