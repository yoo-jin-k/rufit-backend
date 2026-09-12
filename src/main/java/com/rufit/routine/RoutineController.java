package com.rufit.routine;

import io.swagger.v3.oas.annotations.media.Schema;
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
    public View create(@Valid @RequestBody Create request, @RequestHeader(name="Idempotency-Key", required=false) UUID key, Principal principal) { return service.create(principal.getName(), request, key == null ? UUID.randomUUID() : key); }
    @PostMapping("/{id}/copy") @ResponseStatus(HttpStatus.CREATED)
    public View copy(@PathVariable UUID id, @Valid @RequestBody Copy request,
        @RequestHeader(name="Idempotency-Key",required=false) UUID key, Principal principal) {
        return service.copy(id,principal.getName(),request,key==null?UUID.randomUUID():key);
    }
    @PostMapping("/from-workout/{workoutId}") @ResponseStatus(HttpStatus.CREATED)
    public View fromWorkout(@PathVariable UUID workoutId,@Valid @RequestBody Copy request,
        @RequestHeader(name="Idempotency-Key",required=false) UUID key,Principal principal) {
        return service.fromWorkout(workoutId,principal.getName(),request,key==null?UUID.randomUUID():key);
    }
    public record Copy(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=80) String title) {}
    @GetMapping
    public List<View> list(@RequestParam(defaultValue="0") int page, Principal principal) { return service.list(principal.getName(), page); }
    @GetMapping("/search")
    public Results search(@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size,Principal principal) {
        return service.search(principal.getName(),query,page,size);
    }
    @Schema(requiredProperties={"items","page","size","totalElements","hasNext"})
    public record Results(List<View> items,int page,int size,long totalElements,boolean hasNext) {}
    @GetMapping("/{id}")
    public View get(@PathVariable UUID id, Principal principal) { return service.get(id, principal.getName()); }
    @PutMapping("/{id}")
    public View update(@PathVariable UUID id, @Valid @RequestBody Edit request, Principal principal) { return service.update(id, principal.getName(), request); }
    @PostMapping("/{id}/delete") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @Valid @RequestBody DeleteRequest request, Principal principal) {
        service.delete(id, principal.getName(), request.version());
    }
    public record DeleteRequest(@NotNull @PositiveOrZero Long version, @NotNull @AssertTrue Boolean confirmed) {}
    @PostMapping("/{id}/start") @ResponseStatus(HttpStatus.CREATED)
    public WorkoutController.View start(@PathVariable UUID id, @RequestHeader(name="Idempotency-Key", required=false) UUID key, Principal principal) { return service.start(id, principal.getName(), key == null ? UUID.randomUUID() : key); }
    public record Create(@NotBlank @Size(max=80) String title, @NotEmpty @Size(max=300) List<@NotNull @Valid SetInput> sets) {}
    public record Edit(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max=80) String title, @NotEmpty @Size(max=300) List<@NotNull @Valid SetInput> sets) {}
    @Schema(requiredProperties={"id", "title", "version", "sets", "sourceRoutineId", "sourceWorkoutId"})
    public record View(UUID id, String title, long version, List<WorkoutController.SetInput> sets, @io.swagger.v3.oas.annotations.media.Schema(nullable=true) UUID sourceRoutineId, @Schema(nullable=true) UUID sourceWorkoutId) {
        static View from(Routine r) { return new View(r.id, r.title, r.version, r.entries.stream().map(e -> new WorkoutController.SetInput(e.name(), e.weight(), e.reps(), e.exerciseId())).toList(), r.sourceRoutineId, r.sourceWorkoutId); }
    }
}
