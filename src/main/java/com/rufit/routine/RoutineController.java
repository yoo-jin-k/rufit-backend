package com.rufit.routine;

import com.rufit.workout.WorkoutController;
import com.rufit.workout.WorkoutController.SetInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/routines")
public class RoutineController {
    private final RoutineService service;
    RoutineController(RoutineService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public View create(@Valid @RequestBody Create request, Principal principal) { return service.create(principal.getName(), request); }
    @GetMapping
    public List<View> list(@RequestParam(defaultValue="0") int page, Principal principal) { return service.list(principal.getName(), page); }
    @GetMapping("/{id}")
    public View get(@PathVariable UUID id, Principal principal) { return service.get(id, principal.getName()); }
    @PutMapping("/{id}")
    public View update(@PathVariable UUID id, @Valid @RequestBody Edit request, Principal principal) { return service.update(id, principal.getName(), request); }
    @PostMapping("/{id}/start") @ResponseStatus(HttpStatus.CREATED)
    public WorkoutController.View start(@PathVariable UUID id, Principal principal) { return service.start(id, principal.getName()); }
    public record Create(@NotBlank @Size(max=80) String title, @NotEmpty @Size(max=300) List<@NotNull @Valid SetInput> sets) {}
    public record Edit(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max=80) String title, @NotEmpty @Size(max=300) List<@NotNull @Valid SetInput> sets) {}
    public record View(UUID id, String title, long version, List<WorkoutController.SetInput> sets) {
        static View from(Routine r) { return new View(r.id, r.title, r.version, r.entries.stream().map(e -> new WorkoutController.SetInput(e.name(), e.weight(), e.reps())).toList()); }
    }
}
