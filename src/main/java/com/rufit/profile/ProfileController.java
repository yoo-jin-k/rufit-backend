package com.rufit.profile;

import io.swagger.v3.oas.annotations.media.Schema;
import com.rufit.profile.UserProfile.Experience;
import com.rufit.profile.UserProfile.Goal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {
    private final ProfileService service;
    ProfileController(ProfileService service) { this.service=service; }
    @GetMapping public View get(Principal principal) { return service.get(principal.getName()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public View create(@Valid @RequestBody Fields fields,@RequestHeader(name="Idempotency-Key",required=false) UUID key,Principal principal) {
        return service.create(principal.getName(),key==null?UUID.randomUUID():key,fields);
    }
    @PutMapping public View update(@Valid @RequestBody Edit edit,Principal principal) { return service.update(principal.getName(),edit); }
    public record Fields(@NotBlank @Size(min=2,max=30) String nickname,@NotNull @Size(max=500) String bio,
        @Schema(nullable=true) Experience experience,@NotNull @Size(max=4) Set<@NotNull Goal> goals) {}
    public record Edit(@NotNull @PositiveOrZero Long version,@NotNull @Valid Fields profile) {}
    public record View(String nickname,String bio,@Schema(nullable=true) Experience experience,
        List<Goal> goals,long version,String visibility) {
        static View from(UserProfile p) { return new View(p.nickname,p.bio,p.experience,p.goals.stream().sorted().toList(),p.version,"PRIVATE"); }
    }
}
