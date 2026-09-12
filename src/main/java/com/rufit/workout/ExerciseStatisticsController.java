package com.rufit.workout;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class ExerciseStatisticsController {
    private final ExerciseStatistics statistics;
    ExerciseStatisticsController(ExerciseStatistics statistics) { this.statistics=statistics; }
    @GetMapping("/api/v1/workouts/exercise-statistics")
    public ExerciseStatistics.View get(@RequestParam(required=false) String exerciseName,@RequestParam(required=false) UUID exerciseId,Principal principal) {
        return statistics.get(principal.getName(),exerciseName,exerciseId);
    }
}
