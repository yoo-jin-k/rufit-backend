package com.rufit.workout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workouts")
public class WorkoutController {
    private final WorkoutService service;
    WorkoutController(WorkoutService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public View start(Principal principal) { return service.start(principal.getName()); }
    @GetMapping
    public History history(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size, Principal principal) { return service.history(principal.getName(), page, size); }
    public record History(List<View> items, int page, int size, long totalElements, boolean hasNext) {}
    @GetMapping("/{id}")
    public View get(@PathVariable UUID id, Principal principal) { return service.get(id, principal.getName()); }
    @PutMapping("/{id}/exercises")
    public View update(@PathVariable UUID id, @Valid @RequestBody Edit request, Principal principal) { return service.update(id, principal.getName(), request); }
    @PostMapping("/{id}/finish")
    public View finish(@PathVariable UUID id, @Valid @RequestBody Revision request, Principal principal) { return service.finish(id, principal.getName(), request.version()); }
    public record SetInput(@NotBlank @Size(max=80) String exerciseName,
        @NotNull @DecimalMin("0") @DecimalMax("1500") @Digits(integer=4, fraction=2) BigDecimal weight,
        @Min(1) @Max(1000) int reps) {}
    public record Edit(@NotNull @PositiveOrZero Long version, @NotEmpty @Size(max=300) List<@NotNull @Valid SetInput> sets) {}
    public record Revision(@NotNull @PositiveOrZero Long version) {}
    public record View(UUID id, Instant startedAt, Instant finishedAt, long version, List<SetInput> sets) {
        static View from(Workout w) { return new View(w.id(), w.startedAt(), w.finishedAt(), w.version(), w.entries().stream().map(e -> new SetInput(e.name(), e.weight(), e.reps())).toList()); }
    }
}
