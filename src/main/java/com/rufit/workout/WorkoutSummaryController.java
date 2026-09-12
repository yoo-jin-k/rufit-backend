package com.rufit.workout;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class WorkoutSummaryController {
    private final WorkoutSummary summary;
    WorkoutSummaryController(WorkoutSummary summary) { this.summary=summary; }
    @GetMapping("/api/v1/workouts/{id}/summary")
    public WorkoutSummary.View get(@PathVariable UUID id,Principal principal) { return summary.get(id,principal.getName()); }
}
