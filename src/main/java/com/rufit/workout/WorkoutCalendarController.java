package com.rufit.workout;

import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/workouts/calendar")
public class WorkoutCalendarController {
    private final WorkoutCalendar calendar;
    public WorkoutCalendarController(WorkoutCalendar calendar) { this.calendar = calendar; }
    @GetMapping
    public WorkoutCalendar.MonthView month(@RequestParam String month,
        @RequestParam(defaultValue="Asia/Seoul") String zone, Principal principal) {
        return calendar.month(principal.getName(), month, zone);
    }
}
