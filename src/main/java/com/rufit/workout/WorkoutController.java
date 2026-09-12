package com.rufit.workout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workouts")
public class WorkoutController {
    private final WorkoutService service;
    WorkoutController(WorkoutService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public View start(@RequestHeader(name="Idempotency-Key", required=false) UUID key, Principal principal) {
        return service.start(principal.getName(), key == null ? UUID.randomUUID() : key);
    }
    @GetMapping("/active")
    public ResponseEntity<View> active(Principal principal) {
        return service.active(principal.getName()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
    @GetMapping
    public History history(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size, Principal principal) { return service.history(principal.getName(), page, size); }
    public record History(List<View> items, int page, int size, long totalElements, boolean hasNext) {}
    @GetMapping("/{id}")
    public View get(@PathVariable UUID id, Principal principal) { return service.get(id, principal.getName()); }
    @PutMapping("/{id}/exercises")
    public View update(@PathVariable UUID id, @Valid @RequestBody Edit request, Principal principal) { return service.update(id, principal.getName(), request); }
    @PutMapping("/{id}/sets/{setIndex}")
    public View editSet(@PathVariable UUID id, @PathVariable int setIndex, @Valid @RequestBody SetEdit request, Principal principal) {
        return service.editSet(id, principal.getName(), setIndex, request);
    }
    @PostMapping("/{id}/sets")
    public View appendSet(@PathVariable UUID id, @Valid @RequestBody SetEdit request, Principal principal) {
        return service.appendSet(id, principal.getName(), request);
    }
    @PostMapping("/{id}/sets/{setIndex}/remove")
    public View removeSet(@PathVariable UUID id, @PathVariable int setIndex, @Valid @RequestBody Revision request, Principal principal) {
        return service.removeSet(id, principal.getName(), setIndex, request.version());
    }
    @PostMapping("/{id}/finish")
    public View finish(@PathVariable UUID id, @Valid @RequestBody FinishRequest request, Principal principal) { return service.finish(id, principal.getName(), request.version(), Boolean.TRUE.equals(request.discardIncomplete())); }
    @PutMapping("/{id}/sets/{setIndex}/completion")
    public View complete(@PathVariable UUID id, @PathVariable int setIndex, @Valid @RequestBody Completion request, Principal principal) {
        return service.complete(id, principal.getName(), setIndex, request);
    }
    @PostMapping("/{id}/rest/skip")
    public View skipRest(@PathVariable UUID id, @Valid @RequestBody Revision request, Principal principal) {
        return service.skipRest(id, principal.getName(), request.version());
    }
    public record Completion(@NotNull @PositiveOrZero Long version, @NotNull Boolean completed, @Min(0) @Max(3600) int restSeconds) {}
    public record SetInput(@NotBlank @Size(max=80) String exerciseName,
        @NotNull @DecimalMin("0") @DecimalMax("1500") @Digits(integer=4, fraction=2) BigDecimal weight,
        @Min(1) @Max(1000) int reps) {}
    public record SetEdit(@NotNull @PositiveOrZero Long version, @NotNull @Valid SetInput set) {}
    public record Edit(@NotNull @PositiveOrZero Long version, @NotEmpty @Size(max=300) List<@NotNull @Valid SetInput> sets) {}
    public record FinishRequest(@NotNull @PositiveOrZero Long version, Boolean discardIncomplete) {}
    public record Revision(@NotNull @PositiveOrZero Long version) {}
    public record View(UUID id, Instant startedAt, Instant finishedAt, long version, List<SetInput> sets, List<Integer> completedSetIndexes, Instant restEndsAt) {
        static View from(Workout w) { return new View(w.id(), w.startedAt(), w.finishedAt(), w.version(), w.entries().stream().map(e -> new SetInput(e.name(), e.weight(), e.reps())).toList(), w.completedSetIndexes(), w.restEndsAt()); }
    }
}
