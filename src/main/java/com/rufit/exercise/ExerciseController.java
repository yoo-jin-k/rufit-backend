package com.rufit.exercise;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/exercises")
public class ExerciseController {
    private final ExerciseService service;
    ExerciseController(ExerciseService service) { this.service=service; }
    @GetMapping
    public Results search(@RequestParam(defaultValue="") String query, @RequestParam(required=false) Exercise.BodyPart bodyPart,
        @RequestParam(required=false) Exercise.Equipment equipment, @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size, @RequestParam(defaultValue="false") boolean includeArchived, Principal principal) { return service.search(principal.getName(),query,includeArchived,bodyPart,equipment,page,size); }
    @GetMapping("/{id}") public View get(@PathVariable UUID id,Principal principal) { return service.get(id,principal.getName()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public View create(@Valid @RequestBody Create request,@RequestHeader(name="Idempotency-Key",required=false) UUID key,Principal principal) {
        return service.create(principal.getName(),key==null?UUID.randomUUID():key,request);
    }
    @PutMapping("/{id}") public View update(@PathVariable UUID id,@Valid @RequestBody Edit request,Principal principal) { return service.update(id,principal.getName(),request); }
    @PostMapping("/{id}/archive") public View archive(@PathVariable UUID id,@Valid @RequestBody Revision request,Principal principal) {
        return service.archive(id,principal.getName(),request.version());
    }
    @PostMapping("/{id}/restore") public View restore(@PathVariable UUID id,@Valid @RequestBody Revision request,Principal principal) {
        return service.restore(id,principal.getName(),request.version());
    }
    public record Create(@NotBlank @Size(max=80) String name,@NotNull Exercise.BodyPart bodyPart,@NotNull Exercise.Equipment equipment) {}
    public record Edit(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=80) String name,@NotNull Exercise.BodyPart bodyPart,@NotNull Exercise.Equipment equipment) {}
    public record Revision(@NotNull @PositiveOrZero Long version) {}
    @Schema(requiredProperties={"id", "name", "bodyPart", "equipment", "custom", "archivedAt", "version", "recommendedRestSeconds"})
    public record View(UUID id,String name,Exercise.BodyPart bodyPart,Exercise.Equipment equipment,boolean custom,@Schema(nullable=true) Instant archivedAt,long version,@Schema(nullable=true) Integer recommendedRestSeconds) {
        static View from(Exercise e) { return new View(e.id,e.name,e.bodyPart,e.equipment,e.ownerName!=null,e.archivedAt,e.version,e.recommendedRestSeconds); }
    }
    @Schema(requiredProperties={"items", "page", "size", "totalElements", "hasNext"})
    public record Results(List<View> items,int page,int size,long totalElements,boolean hasNext) {}
}
